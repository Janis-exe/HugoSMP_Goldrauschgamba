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
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Wertet die Text-Pakete aus, die der Client vom Server empfängt
 * (Systemnachricht im Chat, Actionbar, Titel, Untertitel) und startet bei einem Fund die Animation.
 */
public final class PacketTriggers {
	private static final Logger LOG = LoggerFactory.getLogger("schiessbude");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

	public enum Source {
		CHAT("Chat"), ACTIONBAR("Actionbar"), TITLE("Titel"), SUBTITLE("Untertitel"), PAYLOAD("Server-Paket"), TEST("Test");
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
				fire(new Reward(prize, prize.extractDetail(plain), source.label), source);
				return;
			}
		}
	}

	/** Eigenes Server-Paket "schiessbude:reward" mit Inhalt "gewinn_id" oder "gewinn_id|Detail". */
	public static void onPayload(String data) {
		ModConfig cfg = ModConfig.get();
		if (cfg.logPackets) log(Source.PAYLOAD, data);
		String[] parts = data.split("\\|", 2);
		PrizeDefinition prize = cfg.findPrize(parts[0].trim());
		if (prize == null) {
			LOG.warn("Server-Paket mit unbekanntem Gewinn: '{}'", parts[0]);
			return;
		}
		String detail = parts.length > 1 ? parts[1].trim() : null;
		fire(new Reward(prize, detail, Source.PAYLOAD.label), Source.PAYLOAD);
	}

	private static void fire(Reward reward, Source source) {
		long now = System.currentTimeMillis();
		ModConfig cfg = ModConfig.get();
		// Gleicher Fund gleichzeitig als Chat UND Titel? Nur einmal abspielen.
		if (reward.prize().id.equals(lastPrizeId) && source != lastSource && now - lastMillis <= cfg.duplicateWindowMs) {
			return;
		}
		lastPrizeId = reward.prize().id;
		lastSource = source;
		lastMillis = now;
		AnimationManager.enqueue(reward);
	}

	private static void log(Source source, String plain) {
		String line = "[" + LocalTime.now().format(TIME) + "] " + source.label.toUpperCase() + ": " + plain.replace('\n', ' ');
		LOG.info("[Paket] {}", line);
		try {
			Path dir = ModConfig.dataDir();
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("pakete.log"), line + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOG.warn("Konnte Paket-Log nicht schreiben", e);
		}
	}
}
