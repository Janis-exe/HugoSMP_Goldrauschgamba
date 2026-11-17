package de.schiessbude.trigger;

import de.schiessbude.config.ModConfig;
import de.schiessbude.config.PrizeDefinition;
import de.schiessbude.config.Reward;
import de.schiessbude.config.ServerSound;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Erkennt Geld-Drops des Servers: Beim Abbauen erscheint über dem Block ein schwebendes goldenes Schild
 * (ein Text-Display), z. B. {@code +66,000 $}. Es gibt dazu keinen Chat-, Titel- oder Actionbar-Text.
 * <p>
 * Der Text des Schilds besteht aus vielen Sonderzeichen einer eigenen Server-Schrift (goldener Hintergrund, Unicode
 * "Private Use") und dem lesbaren Betrag. Die Sonderzeichen werden entfernt, dann wird der Betrag gesucht.
 * <p>
 * Damit Drops anderer Spieler nichts auslösen, zählt ein Schild nur, wenn es nah bei dir ist und du gerade selbst
 * einen Block abgebaut hast. Jedes Schild löst höchstens einmal aus.
 * <p>
 * Sounds: Der Server spielt beim Drop eigene Sounds ab (direkt nach dem goldenen Nugget-Display, noch bevor das
 * Schild mit dem Betrag kommt, und einen am Ende). Damit sie nichts verraten, werden genau diese Sounds kurz
 * zurückgehalten: Wird der Drop erkannt, laufen sie erst bei der Enthüllung in der Animation. Sonst werden sie sofort
 * nachgeholt. Normale Minecraft-Sounds (z. B. Abbau-Geräusche) werden nie angehalten.
 * <p>
 * Sicherheit: Es wird nur gelesen. Der Text wird in der Länge begrenzt, von Steuerzeichen befreit und nur angezeigt.
 */
public final class MoneyDrops {
	private static final Logger LOG = LoggerFactory.getLogger("schiessbude");
	/** So viele Zeichen des Schild-Texts werden höchstens angesehen (der Hintergrund allein sind einige Tausend). */
	private static final int MAX_RAW = 20_000;
	/** Betrag: "+66,000 $", tolerant für "+1.2M $", "+1,2 Mio $", "+66 000$" und "+$66,000". */
	private static final Pattern MONEY_AFTER = Pattern.compile(
			"\\+\\s*([0-9](?:[0-9.,' ]*[0-9])?)\\s*(k|K|m|M|b|B|Mio|Mrd|Mio\\.|Mrd\\.)?\\s*\\$");
	private static final Pattern MONEY_BEFORE = Pattern.compile(
			"\\+\\s*\\$\\s*([0-9](?:[0-9.,']*[0-9])?)\\s*(k|K|m|M|b|B|Mio|Mrd)?(?![A-Za-z])");
	/** Ab hier zählt ein eigener Abbau nicht mehr als "gerade eben". */
	private static final long MINING_WINDOW_NANOS = 5_000_000_000L;

	private static final Set<Integer> TRIGGERED = new LinkedHashSet<>();
	private static final Set<String> LOGGED = new LinkedHashSet<>();
	private static long lastOwnBreak;
	private static boolean brokeBlock;

	/** Nach dem Nugget-Display so lange auf das Schild mit dem Betrag warten (Sounds solange zurückhalten). */
	private static final long ARM_NANOS = 600_000_000L;
	/** Nach dem erkannten Schild auch den Schluss-Sound des Servers noch abfangen. */
	private static final long TAIL_NANOS = 1_400_000_000L;
	private static final int MAX_SOUNDS = 8;
	private static boolean capturing, assigned, replaying;
	private static long captureStart, captureUntil;
	private static int captureEntity;
	private static List<ServerSound> captured;

	private MoneyDrops() {
	}

	/** Du hast gerade selbst einen Block abgebaut. */
	public static void onOwnBlockBroken() {
		lastOwnBreak = System.nanoTime();
		brokeBlock = true;
	}

	/** Ein Text-Display hat (neuen) Text bekommen. Wird vom TextDisplayMixin auf dem Haupt-Thread aufgerufen. */
	public static void onTextDisplay(Entity entity, Component text) {
		if (entity == null || text == null || !entity.level().isClientSide()) return;
		ModConfig cfg = ModConfig.get();
		if (!cfg.triggerMoneyDrops && !cfg.logPackets) return;

		String plain = readable(text.getString());
		if (plain == null) return;

		Entity camera = Minecraft.getInstance().getCameraEntity();
		double distance = camera == null ? Double.MAX_VALUE : Math.sqrt(camera.distanceToSqr(entity));
		boolean near = distance <= cfg.moneyDropMaxDistance;
		// Debug: Schilder in deiner Nähe ins Paket-Log, damit man neue Formate (z. B. andere Drops) findet
		if (cfg.logPackets && near && remember(LOGGED, entity.getId() + ":" + plain, 128)) {
			PacketTriggers.log(PacketTriggers.Source.TEXT_DISPLAY,
					plain + "  (" + String.format(Locale.ROOT, "%.1f", distance) + " Blöcke entfernt)");
		}

		Matcher m = MONEY_AFTER.matcher(plain);
		boolean found = m.find();
		if (!found) {
			m = MONEY_BEFORE.matcher(plain);
			found = m.find();
		}
		if (!found) return;
		long amount = parseAmount(m.group(1), m.group(2));
		if (amount <= 0) return;
		if (!remember(TRIGGERED, entity.getId(), 64)) return;

		if (!cfg.triggerMoneyDrops || !cfg.enabled) {
			finishCapture();
			return;
		}
		if (!near) {
			LOG.info("Geld-Drop {} ignoriert: {} Blöcke entfernt (Drop eines anderen Spielers?)", m.group().trim(),
					String.format(Locale.ROOT, "%.1f", distance));
			return;
		}
		if (cfg.moneyDropNeedsMining && (!brokeBlock || System.nanoTime() - lastOwnBreak > MINING_WINDOW_NANOS)) {
			LOG.info("Geld-Drop {} ignoriert: du hast gerade keinen Block abgebaut", m.group().trim());
			return;
		}
		PrizeDefinition prize = cfg.prizeForMoney(amount);
		if (prize == null) return;
		String detail = PacketTriggers.clean(m.group().trim(), PacketTriggers.MAX_DETAIL);
		// zurückgehaltene Server-Sounds gehören zu diesem Fund und laufen erst bei der Enthüllung
		List<ServerSound> sounds = capturing && !assigned ? captured : null;
		Reward reward = new Reward(prize, detail, PacketTriggers.Source.MONEY_DROP.label, null, null, sounds);
		boolean queued = PacketTriggers.fire(reward, PacketTriggers.Source.MONEY_DROP);
		if (sounds != null && queued) {
			assigned = true;
			captureUntil = System.nanoTime() + TAIL_NANOS;
		} else {
			finishCapture();
		}
	}

	// ---- Server-Sounds zurückhalten -------------------------------------------------------------------------------

	/** Ein Item-Display hat ein Item bekommen. Goldnugget in deiner Nähe direkt nach dem Abbauen = Geld-Drop beginnt. */
	public static void onItemDisplay(Entity entity, ItemStack stack) {
		if (entity == null || stack == null || !entity.level().isClientSide()) return;
		ModConfig cfg = ModConfig.get();
		if (!cfg.enabled || !cfg.triggerMoneyDrops || !cfg.moneyDropSoundAtReveal) return;
		if (!stack.is(Items.GOLD_NUGGET)) return;
		// der Server schickt das Item mehrmals: nur das erste Mal zählt
		if (capturing && entity.getId() == captureEntity) return;
		Entity camera = Minecraft.getInstance().getCameraEntity();
		if (camera == null || camera.distanceToSqr(entity) > (double) cfg.moneyDropMaxDistance * cfg.moneyDropMaxDistance) return;
		if (cfg.moneyDropNeedsMining && (!brokeBlock || System.nanoTime() - lastOwnBreak > MINING_WINDOW_NANOS)) return;
		finishCapture();
		captured = new ArrayList<>();
		assigned = false;
		capturing = true;
		captureEntity = entity.getId();
		captureStart = System.nanoTime();
		captureUntil = captureStart + ARM_NANOS;
	}

	/**
	 * Wird für jeden Sound aufgerufen, bevor er startet (SoundManagerMixin). true = zurückhalten.
	 * Nur eigene Server-Sounds (nicht im Minecraft-Register), nicht an Entities gebunden, nur kurz nach dem Drop.
	 */
	public static boolean interceptSound(SoundInstance sound) {
		if (replaying || !capturing || sound == null) return false;
		long now = System.nanoTime();
		if (now > captureUntil) {
			finishCapture();
			return false;
		}
		if (sound instanceof EntityBoundSoundInstance) return false;
		Identifier id = sound.getIdentifier();
		if (id == null || BuiltInRegistries.SOUND_EVENT.containsKey(id)) return false;
		if (captured.size() >= MAX_SOUNDS) return false;
		captured.add(new ServerSound(sound, (now - captureStart) / 1_000_000_000f));
		LOG.info("Geld-Drop: Server-Sound {} zurückgehalten", id);
		return true;
	}

	/** Jeden Tick: abgelaufenes Zurückhalten beenden (nicht erkannte Drops: Sounds sofort nachholen). */
	public static void tick() {
		if (capturing && System.nanoTime() > captureUntil) finishCapture();
	}

	/** Beendet das Zurückhalten. Sounds, die zu keinem Fund gehören, werden jetzt abgespielt. */
	private static void finishCapture() {
		if (capturing && !assigned && captured != null && !captured.isEmpty()) {
			LOG.info("Geld-Drop: {} zurückgehaltene Sounds gehören zu keinem Fund und werden jetzt abgespielt", captured.size());
			for (ServerSound s : captured) replay(s);
		}
		capturing = false;
		assigned = false;
		captured = null;
	}

	/** Spielt einen zurückgehaltenen Sound ab (bei der Enthüllung oder als Nachholen). */
	public static void replay(ServerSound s) {
		LOG.info("Geld-Drop: Server-Sound {} wird abgespielt", s.sound().getIdentifier());
		replaying = true;
		try {
			Minecraft.getInstance().getSoundManager().play(s.sound());
		} finally {
			replaying = false;
		}
	}

	/** Entfernt die Hintergrund-Zeichen (Private Use) und Formatierung, begrenzt die Länge. Leer -> null. */
	static String readable(String raw) {
		if (raw == null || raw.isEmpty()) return null;
		StringBuilder sb = new StringBuilder();
		int end = Math.min(raw.length(), MAX_RAW);
		for (int i = 0; i < end && sb.length() < PacketTriggers.MAX_TEXT; ) {
			int cp = raw.codePointAt(i);
			i += Character.charCount(cp);
			int type = Character.getType(cp);
			if (type == Character.PRIVATE_USE || type == Character.SURROGATE || type == Character.UNASSIGNED) continue;
			sb.appendCodePoint(cp);
		}
		String s = ChatFormatting.stripFormatting(sb.toString());
		return PacketTriggers.clean(s == null ? null : s.replaceAll("\\s+", " "), PacketTriggers.MAX_TEXT);
	}

	/**
	 * "66,000" -> 66000, "1.2" + "M" -> 1200000, "1,2" + "Mio" -> 1200000, "2.500.000" -> 2500000.
	 * Ein Trenner mit genau drei Ziffern dahinter ist ein Tausender-Trenner, sonst ein Dezimalpunkt.
	 */
	static long parseAmount(String number, String suffix) {
		String digits = number.replace(" ", "").replace("'", "");
		int lastSep = Math.max(digits.lastIndexOf('.'), digits.lastIndexOf(','));
		// Die Zeichen sind durch das Muster auf Ziffern und Trenner beschränkt: parseDouble kann hier nicht scheitern
		double value;
		if (lastSep >= 0 && digits.length() - lastSep - 1 != 3) {
			String whole = digits.substring(0, lastSep).replace(".", "").replace(",", "");
			value = Double.parseDouble((whole.isEmpty() ? "0" : whole) + "." + digits.substring(lastSep + 1));
		} else {
			value = Double.parseDouble(digits.replace(".", "").replace(",", ""));
		}
		if (suffix != null) {
			String s = suffix.toLowerCase(Locale.ROOT);
			if (s.startsWith("k")) value *= 1_000d;
			else if (s.startsWith("m")) value *= s.startsWith("mrd") ? 1_000_000_000d : 1_000_000d;
			else if (s.startsWith("b")) value *= 1_000_000_000d;
		}
		if (value > 1e15) return -1;
		return Math.round(value);
	}

	/** Merkt sich einen Eintrag (begrenzte Liste). false = war schon bekannt. */
	private static <T> boolean remember(Set<T> set, T key, int max) {
		if (!set.add(key)) return false;
		if (set.size() > max) {
			Iterator<T> it = set.iterator();
			it.next();
			it.remove();
		}
		return true;
	}

	/** Beim Verlassen des Servers vergessen. */
	public static void reset() {
		capturing = false;
		assigned = false;
		captured = null;
		TRIGGERED.clear();
		LOGGED.clear();
		brokeBlock = false;
	}
}
