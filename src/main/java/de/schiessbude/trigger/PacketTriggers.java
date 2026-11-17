package de.schiessbude.trigger;

import de.schiessbude.anim.AnimationManager;
import de.schiessbude.config.ModConfig;
import de.schiessbude.config.PrizeDefinition;
import de.schiessbude.config.Reward;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Wertet die Text-Pakete aus, die der Client vom Server empfängt
 * (Systemnachricht im Chat, Actionbar, Titel, Untertitel) und startet bei einem Fund die Animation.
 * Geld-Drops (schwebende Schilder) erkennt {@link MoneyDrops}.
 * <p>
 * Sicherheit: Alles, was vom Server kommt, wird nur gelesen, in der Länge begrenzt und als Text angezeigt.
 * Nichts davon wird ausgeführt, als Dateipfad benutzt oder irgendwohin gesendet.
 */
public final class PacketTriggers {
	private static final Logger LOG = LoggerFactory.getLogger("schiessbude");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	/** Nur so viel Text wird untersucht: Fund-Nachrichten sind kurz, riesige Nachrichten können das Spiel nicht einfrieren. */
	public static final int MAX_TEXT = 512;
	/** Längste Detail-Angabe (Betrag, Spawner-Typ), die angezeigt wird. */
	public static final int MAX_DETAIL = 48;
	/** Paket-Log: ab dieser Größe wird es nach pakete.alt.log verschoben (höchstens 2 x 1 MB auf der Platte). */
	private static final long MAX_LOG_BYTES = 1_000_000L;
	private static final Pattern PRIZE_ID = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");
	private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\p{Cf}\\u00A7]");

	public enum Source {
		CHAT("Chat"), ACTIONBAR("Actionbar"), TITLE("Titel"), SUBTITLE("Untertitel"), PAYLOAD("Server-Paket"), TEST("Test"),
		MONEY_DROP("Geld-Drop"), TEXT_DISPLAY("Text-Display");
		public final String label;

		Source(String label) {
			this.label = label;
		}
	}

	private static String lastPrizeId;
	private static Source lastSource;
	private static long lastMillis;

	private PacketTriggers() {
	}

	public static void onText(Component component, Source source) {
		if (component == null) return;
		String plain = ChatFormatting.stripFormatting(component.getString());
		if (plain == null || plain.isBlank()) return;
		if (plain.length() > MAX_TEXT) plain = plain.substring(0, MAX_TEXT);
		ModConfig cfg = ModConfig.get();
		if (cfg.logPackets) log(source, plain);

		boolean sourceEnabled = switch (source) {
			case CHAT -> cfg.triggerChat;
			case ACTIONBAR -> cfg.triggerActionbar;
			case TITLE -> cfg.triggerTitle;
			case SUBTITLE -> cfg.triggerSubtitle;
			default -> true;
		};
		if (!sourceEnabled || !cfg.enabled) return;
		if (!cfg.passesFilters(plain)) return;

		for (PrizeDefinition prize : cfg.prizes) {
			if (prize.matches(plain)) {
				// Variante (z. B. "Star-Axt", "Kuh-Spawner") aus der ganzen Nachricht erkennen
				fire(new Reward(prize, clean(prize.extractDetail(plain), MAX_DETAIL), source.label, null,
						prize.findVariant(plain), null), source);
				return;
			}
		}
	}

	/** Eigenes Server-Paket "schiessbude:reward" mit Inhalt "gewinn_id" oder "gewinn_id|Detail". */
	public static void onPayload(String data) {
		ModConfig cfg = ModConfig.get();
		if (cfg.logPackets) log(Source.PAYLOAD, data);
		if (!cfg.allowServerPayload || !cfg.enabled) return;
		String[] parts = data.split("\\|", 2);
		String id = parts[0].trim();
		PrizeDefinition prize = PRIZE_ID.matcher(id).matches() ? cfg.findPrize(id) : null;
		if (prize == null) {
			LOG.warn("Server-Paket mit unbekanntem Gewinn ignoriert: '{}'", clean(id, 32));
			return;
		}
		String detail = parts.length > 1 ? clean(parts[1], MAX_DETAIL) : null;
		fire(new Reward(prize, detail, Source.PAYLOAD.label), Source.PAYLOAD);
	}

	/** false = keine Animation (doppelt, abgeschaltet, Warteschlange voll ...). */
	static boolean fire(Reward reward, Source source) {
		long now = System.currentTimeMillis();
		ModConfig cfg = ModConfig.get();
		// Gleicher Fund gleichzeitig als Chat UND Titel? Nur einmal abspielen.
		if (reward.prize().id.equals(lastPrizeId) && source != lastSource && now - lastMillis <= cfg.duplicateWindowMs) {
			return false;
		}
		lastPrizeId = reward.prize().id;
		lastSource = source;
		lastMillis = now;
		return AnimationManager.enqueue(reward);
	}

	/** Entfernt Steuerzeichen und Farbcodes und kürzt auf {@code max} Zeichen. Null/leer bleibt null. */
	public static String clean(String text, int max) {
		if (text == null) return null;
		String s = CONTROL.matcher(text).replaceAll(" ").trim();
		if (s.length() > max) s = s.substring(0, max).trim();
		return s.isEmpty() ? null : s;
	}

	static void log(Source source, String plain) {
		String text = CONTROL.matcher(plain.length() > MAX_TEXT ? plain.substring(0, MAX_TEXT) : plain).replaceAll(" ");
		String line = "[" + LocalTime.now().format(TIME) + "] " + source.label.toUpperCase() + ": " + text;
		LOG.info("[Paket] {}", line);
		try {
			// fester Ort: config/schiessbude/pakete.log - der Server hat keinen Einfluss auf Pfad oder Dateiname
			Path dir = ModConfig.dataDir();
			Files.createDirectories(dir);
			Path file = dir.resolve("pakete.log");
			if (Files.exists(file) && Files.size(file) > MAX_LOG_BYTES) {
				Files.move(file, dir.resolve("pakete.alt.log"), StandardCopyOption.REPLACE_EXISTING);
			}
			Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOG.warn("Konnte Paket-Log nicht schreiben", e);
		}
	}
}
