package de.schiessbude.trigger;

import de.schiessbude.config.ModConfig;
import de.schiessbude.config.PrizeDefinition;
import de.schiessbude.config.PrizeVariant;
import de.schiessbude.config.Reward;
import de.schiessbude.config.ServerSound;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Erkennt Drops des Servers beim Abbauen. Auf HugiSMP erscheint ein Fund nicht im Chat, sondern als schwebende
 * Anzeige direkt über dem abgebauten Block: ein Item-Display (z. B. Goldnugget, Spawner, Star-Tool) und darunter ein
 * Text-Display (z. B. {@code +66,000 $}).
 * <p>
 * Ein Display zählt nur, wenn es <b>direkt an einem Block auftaucht, den du gerade abgebaut oder angeschlagen hast</b>.
 * So lösen Hologramme (z. B. über Spawnern, die in Sichtweite kommen) und Drops anderer Spieler nichts aus.
 * <ul>
 *   <li>Text mit Betrag ("+66,000 $", "+1.2M $"): Geld-Gewinn passend zum Betrag (bis 75.000 $ Goldnugget ...).</li>
 *   <li>Text oder Item mit dem Namen eines Gewinns ("Star-Spitzhacke", "Spawner" ...): dieser Gewinn mit Variante.</li>
 *   <li>Nur ein Gold-Item ohne lesbaren Betrag: Geld-Gewinn ohne Betrag (damit kein Drop verloren geht).</li>
 * </ul>
 * Jeder Drop löst höchstens einmal aus. Alle Drops an deinem Block stehen im Fund-Protokoll (funde.log).
 * <p>
 * Sounds: Der Server spielt beim Drop eigene Sounds ab, direkt nach dem Item-Display und noch bevor der Text kommt.
 * Damit sie nichts verraten, werden genau diese Sounds kurz zurückgehalten und laufen erst bei der Enthüllung in der
 * Animation. Gehört nichts dazu, werden sie sofort nachgeholt. Normale Minecraft-Sounds werden nie angehalten.
 * <p>
 * Sicherheit: Es wird nur gelesen. Texte werden in der Länge begrenzt, von Steuerzeichen befreit und nur angezeigt.
 */
public final class Drops {
	private static final Logger LOG = LoggerFactory.getLogger("schiessbude");
	/** So viele Zeichen des Schild-Texts werden höchstens angesehen (der Hintergrund allein sind einige Tausend). */
	private static final int MAX_RAW = 20_000;
	/** Betrag: "+66,000 $", tolerant für "66,000 $", "+1.2M $", "+1,2 Mio $", "+66 000$". */
	private static final Pattern MONEY_AFTER = Pattern.compile(
			"\\+?\\s*([0-9](?:[0-9.,' ]*[0-9])?)\\s*(k|K|m|M|b|B|Mio\\.?|Mrd\\.?)?\\s*\\$");
	/** Betrag mit Dollar vorne: "+$66,000", "$1.2M". */
	private static final Pattern MONEY_BEFORE = Pattern.compile(
			"\\$\\s*([0-9](?:[0-9.,']*[0-9])?)\\s*(k|K|m|M|b|B|Mio|Mrd)?(?![A-Za-z0-9])");
	/** So lange zählt ein abgebauter/angeschlagener Block als "gerade eben". */
	private static final long MINING_WINDOW_NANOS = 4_000_000_000L;
	/** So nah (Blöcke) muss ein Display an einem gerade abgebauten Block erscheinen. */
	private static final double NEAR_BLOCK = 3.5;
	/** Item-Display ohne passenden Text: so lange auf den Text warten, dann über das Item auslösen. */
	private static final long ITEM_WAIT_NANOS = 700_000_000L;
	/** Zwei Anzeigen so nah beieinander und so kurz nacheinander gehören zum selben Drop (beim Spawner-Drop steht das
	 * Schild knapp 2 Blöcke neben dem Item). */
	private static final double SAME_DROP = 3.0;
	private static final long SAME_DROP_NANOS = 2_500_000_000L;

	private record Spot(double x, double y, double z, long nanos) {
		double dist(Entity e) {
			double dx = e.getX() - x, dy = e.getY() - y, dz = e.getZ() - z;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	/** Ein Item-Display, das auf seinen Text wartet. */
	private record PendingItem(Spot spot, PrizeDefinition prize, PrizeVariant variant, String label) {
	}

	private static final Deque<Spot> MINED = new ArrayDeque<>();
	private static final Deque<Spot> FIRED = new ArrayDeque<>();
	private static final List<PendingItem> PENDING = new ArrayList<>();
	private static final Set<Integer> TRIGGERED = new LinkedHashSet<>();
	private static final Set<String> LOGGED = new LinkedHashSet<>();

	/** Nach dem Item-Display so lange Server-Sounds zurückhalten (bis der Text kommt). */
	private static final long ARM_NANOS = 900_000_000L;
	/** Nach dem erkannten Drop auch den Schluss-Sound des Servers noch abfangen. */
	private static final long TAIL_NANOS = 1_400_000_000L;
	private static final int MAX_SOUNDS = 8;
	private static boolean capturing, assigned, replaying;
	private static long captureStart, captureUntil;
	private static List<ServerSound> captured;

	private Drops() {
	}

	// ---- Eigener Abbau --------------------------------------------------------------------------------------------

	/** Du hast einen Block abgebaut oder angeschlagen. */
	public static void onBlockMined(BlockPos pos) {
		if (pos == null) return;
		MINED.addLast(new Spot(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, System.nanoTime()));
		while (MINED.size() > 24) MINED.removeFirst();
	}

	/** Abstand eines Displays zum nächsten Block, den du in den letzten Sekunden abgebaut hast (sonst unendlich). */
	private static double distanceToMined(Entity e) {
		long now = System.nanoTime();
		double best = Double.MAX_VALUE;
		for (Spot s : MINED) {
			if (now - s.nanos() <= MINING_WINDOW_NANOS) best = Math.min(best, s.dist(e));
		}
		return best;
	}

	private static double distanceToCamera(Entity e) {
		Entity camera = Minecraft.getInstance().getCameraEntity();
		return camera == null ? Double.MAX_VALUE : Math.sqrt(camera.distanceToSqr(e));
	}

	/** Darf ein Display an dieser Stelle einen Fund auslösen? null = ja, sonst der Grund. */
	private static String rejectReason(ModConfig cfg, double toCamera, double toMined) {
		if (toCamera > cfg.moneyDropMaxDistance) {
			return String.format(Locale.ROOT, "%.1f Blöcke von dir entfernt (Drop eines anderen Spielers?)", toCamera);
		}
		if (cfg.moneyDropNeedsMining && toMined > NEAR_BLOCK) {
			return "nicht an einem Block, den du gerade abgebaut hast (Hologramm?)";
		}
		return null;
	}

	// ---- Text-Displays --------------------------------------------------------------------------------------------

	/** Ein Text-Display hat (neuen) Text bekommen. Wird vom TextDisplayMixin auf dem Haupt-Thread aufgerufen. */
	public static void onTextDisplay(Entity entity, Component text) {
		if (entity == null || text == null || !entity.level().isClientSide()) return;
		ModConfig cfg = ModConfig.get();
		String plain = readable(text.getString());
		if (plain == null) return;

		double toCamera = distanceToCamera(entity), toMined = distanceToMined(entity);
		boolean atMinedBlock = toMined <= NEAR_BLOCK;
		if (atMinedBlock && remember(LOGGED, "t" + entity.getId() + ":" + plain, 128)) {
			PacketTriggers.logFind("DROP-TEXT am abgebauten Block: " + plain);
		}
		if (cfg.logPackets && toCamera <= cfg.moneyDropMaxDistance && remember(LOGGED, "p" + entity.getId() + ":" + plain, 128)) {
			PacketTriggers.log(PacketTriggers.Source.TEXT_DISPLAY,
					plain + "  (" + String.format(Locale.ROOT, "%.1f", toCamera) + " Blöcke entfernt)");
		}

		// Was steht drauf? Zuerst ein Betrag, sonst der Name eines Gewinns
		PrizeDefinition prize = null;
		PrizeVariant variant = null;
		String detail = null;
		Matcher m = MONEY_AFTER.matcher(plain);
		boolean money = m.find();
		if (!money) {
			m = MONEY_BEFORE.matcher(plain);
			money = m.find();
		}
		if (money) {
			long amount = parseAmount(m.group(1), m.group(2));
			if (amount > 0) {
				prize = cfg.prizeForMoney(amount);
				detail = m.group().trim();
				if (!detail.startsWith("+") && !detail.startsWith("$")) detail = "+" + detail;
			}
		}
		if (prize == null) {
			for (PrizeDefinition p : cfg.prizes) {
				PrizeVariant v = p.findVariant(plain);
				if (v != null || p.matches(plain)) {
					prize = p;
					variant = v;
					// ohne eigenes Bild (z. B. "Skeleton Spawner"): den Text des Servers anzeigen
					if (v == null) detail = plain;
					break;
				}
			}
		}
		if (prize == null) return;
		if (!remember(TRIGGERED, entity.getId(), 64)) return;

		if (!cfg.triggerDrops || !cfg.enabled) {
			finishCapture();
			return;
		}
		String reason = rejectReason(cfg, toCamera, toMined);
		if (reason != null) {
			LOG.info("Drop '{}' ignoriert: {}", plain, reason);
			PacketTriggers.logFind("IGNORIERT Drop-Text '" + plain + "': " + reason);
			return;
		}
		// Gehört ein Item-Display dazu? Dann dessen Variante übernehmen (z. B. Spawner-Typ) und es nicht doppelt zählen
		PendingItem item = takePending(entity);
		if (item != null && variant == null && item.prize() == prize && item.variant() != null) {
			variant = item.variant();
			detail = null;
		}
		if (firedNear(entity)) return;
		fireDrop(prize, variant, detail, entity, "Text '" + plain + "'");
	}

	// ---- Item-Displays --------------------------------------------------------------------------------------------

	/** Ein Item-Display hat ein Item bekommen (ItemDisplayMixin). Startet ggf. das Zurückhalten der Server-Sounds. */
	public static void onItemDisplay(Entity entity, ItemStack stack) {
		if (entity == null || stack == null || stack.isEmpty() || !entity.level().isClientSide()) return;
		ModConfig cfg = ModConfig.get();
		double toCamera = distanceToCamera(entity), toMined = distanceToMined(entity);
		if (toMined > NEAR_BLOCK) return; // nur Drops an deinem Block interessieren
		// der Server schickt das Item mehrmals: nur das erste Mal zählt
		if (!remember(LOGGED, "i" + entity.getId(), 128)) return;

		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		String name = readable(stack.getHoverName().getString());
		String label = id + (name == null ? "" : " \"" + name + "\"");
		PacketTriggers.logFind("DROP-ITEM am abgebauten Block: " + label);
		if (!cfg.enabled || !cfg.triggerDrops || rejectReason(cfg, toCamera, toMined) != null) return;

		// Server-Sounds ab jetzt kurz zurückhalten: sie kommen direkt nach diesem Item
		if (cfg.moneyDropSoundAtReveal) {
			finishCapture();
			captured = new ArrayList<>();
			assigned = false;
			capturing = true;
			captureStart = System.nanoTime();
			captureUntil = captureStart + ARM_NANOS;
		}

		// Gewinn am Item erkennen; ausgelöst wird erst, wenn kein passender Text kommt
		String itemText = (name == null ? "" : name + " ") + id.getPath().replace('_', ' ');
		PrizeDefinition prize = null;
		PrizeVariant variant = null;
		for (PrizeDefinition p : cfg.prizes) {
			if (p.testMoneyMax > 0) continue; // Geld-Gewinne kommen über den Betrag im Text
			PrizeVariant v = p.findVariant(itemText);
			if (v != null || p.matches(itemText)) {
				prize = p;
				variant = v;
				break;
			}
		}
		if (prize == null) prize = moneyPrizeForItem(cfg, stack);
		if (prize == null) return;
		PENDING.add(new PendingItem(spotOf(entity), prize, variant, label));
	}

	/** Gold-Item ohne lesbaren Betrag: Goldnugget = kleinster Geld-Gewinn, Goldbarren/-block = der nächstgrößere. */
	private static PrizeDefinition moneyPrizeForItem(ModConfig cfg, ItemStack stack) {
		if (stack.is(Items.GOLD_NUGGET)) return cfg.prizeForMoney(1);
		if (stack.is(Items.GOLD_INGOT) || stack.is(Items.GOLD_BLOCK) || stack.is(Items.RAW_GOLD)) {
			PrizeDefinition small = cfg.prizeForMoney(1);
			return small == null ? null : cfg.prizeForMoney(small.testMoneyMax + 1);
		}
		return null;
	}

	/** Jeden Tick: Item-Displays ohne Text auslösen, abgelaufenes Zurückhalten von Sounds beenden. */
	public static void tick() {
		long now = System.nanoTime();
		Iterator<PendingItem> it = PENDING.iterator();
		while (it.hasNext()) {
			PendingItem p = it.next();
			if (now - p.spot().nanos() < ITEM_WAIT_NANOS) continue;
			it.remove();
			if (firedNear(p.spot())) continue;
			fireDrop(p.prize(), p.variant(), null, p.spot(), "Item " + p.label());
		}
		if (capturing && now > captureUntil) finishCapture();
	}

	private static PendingItem takePending(Entity e) {
		for (Iterator<PendingItem> it = PENDING.iterator(); it.hasNext(); ) {
			PendingItem p = it.next();
			if (p.spot().dist(e) <= SAME_DROP) {
				it.remove();
				return p;
			}
		}
		return null;
	}

	// ---- Auslösen ---------------------------------------------------------------------------------------------------

	private static void fireDrop(PrizeDefinition prize, PrizeVariant variant, String detail, Entity at, String why) {
		fireDrop(prize, variant, detail, spotOf(at), why);
	}

	private static void fireDrop(PrizeDefinition prize, PrizeVariant variant, String detail, Spot at, String why) {
		FIRED.addLast(at);
		while (FIRED.size() > 16) FIRED.removeFirst();
		// zurückgehaltene Server-Sounds gehören zu diesem Fund und laufen erst bei der Enthüllung
		List<ServerSound> sounds = capturing && !assigned ? captured : null;
		Reward reward = new Reward(prize, PacketTriggers.clean(detail, PacketTriggers.MAX_DETAIL),
				PacketTriggers.Source.MONEY_DROP.label, null, variant, sounds);
		boolean queued = PacketTriggers.fire(reward, PacketTriggers.Source.MONEY_DROP, why);
		if (sounds != null && queued) {
			assigned = true;
			captureUntil = System.nanoTime() + TAIL_NANOS;
		} else {
			finishCapture();
		}
	}

	private static Spot spotOf(Entity e) {
		return new Spot(e.getX(), e.getY(), e.getZ(), System.nanoTime());
	}

	private static boolean firedNear(Entity e) {
		return firedNear(spotOf(e));
	}

	private static boolean firedNear(Spot s) {
		long now = System.nanoTime();
		for (Spot f : FIRED) {
			double dx = f.x() - s.x(), dy = f.y() - s.y(), dz = f.z() - s.z();
			if (now - f.nanos() <= SAME_DROP_NANOS && Math.sqrt(dx * dx + dy * dy + dz * dz) <= SAME_DROP) return true;
		}
		return false;
	}

	// ---- Server-Sounds zurückhalten -------------------------------------------------------------------------------

	/**
	 * Wird für jeden Sound aufgerufen, bevor er startet (SoundManagerMixin). true = zurückhalten.
	 * Nur eigene Server-Sounds (nicht im Minecraft-Register), nicht an Entities gebunden, nur kurz nach einem Drop.
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
		LOG.info("Drop: Server-Sound {} zurückgehalten", id);
		return true;
	}

	/** Beendet das Zurückhalten. Sounds, die zu keinem Fund gehören, werden jetzt abgespielt. */
	private static void finishCapture() {
		if (capturing && !assigned && captured != null && !captured.isEmpty()) {
			LOG.info("Drop: {} zurückgehaltene Sounds gehören zu keinem Fund und werden jetzt abgespielt", captured.size());
			for (ServerSound s : captured) replay(s);
		}
		capturing = false;
		assigned = false;
		captured = null;
	}

	/** Spielt einen zurückgehaltenen Sound ab (bei der Enthüllung oder als Nachholen). */
	public static void replay(ServerSound s) {
		LOG.info("Drop: Server-Sound {} wird abgespielt", s.sound().getIdentifier());
		replaying = true;
		try {
			Minecraft.getInstance().getSoundManager().play(s.sound());
		} finally {
			replaying = false;
		}
	}

	// ---- Hilfen -----------------------------------------------------------------------------------------------------

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
		MINED.clear();
		FIRED.clear();
		PENDING.clear();
		TRIGGERED.clear();
		LOGGED.clear();
	}
}
