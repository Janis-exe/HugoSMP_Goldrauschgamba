package de.schiessbude.anim;

import de.schiessbude.config.AnimationType;
import de.schiessbude.config.ModConfig;
import de.schiessbude.config.Reward;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Warteschlange der Funde. Es läuft immer nur eine Animation; alle weiteren werden der Reihe nach abgespielt.
 */
public final class AnimationManager {
	private static final Logger LOG = LoggerFactory.getLogger("schiessbude");
	private static final Deque<Reward> QUEUE = new ArrayDeque<>();
	private static final Random RANDOM = new Random();
	private static CasinoAnimation current;
	private static long lastFrameNanos = -1L;
	private static long seedCounter = System.nanoTime();
	/** Name der Überspringen-Taste für den Hinweis (wird vom Client gesetzt). */
	public static java.util.function.Supplier<String> skipKeyName = () -> null;

	private AnimationManager() {
	}

	/** Fügt einen Fund hinzu. Läuft gerade eine Animation, wird er hinten angestellt. */
	public static void enqueue(Reward reward) {
		ModConfig cfg = ModConfig.get();
		if (!cfg.enabled) return;
		if (!reward.prize().rarity.atLeast(cfg.minRarity)) {
			LOG.debug("Fund {} unter Mindest-Seltenheit - keine Animation", reward.prize().id);
			return;
		}
		LOG.info("Schießbude: Fund '{}' ({}) aus {} in die Warteschlange ({})", reward.prize().name, reward.detail(), reward.source(),
				reward.animation() != null ? reward.animation().displayName : "Server-Fund");
		QUEUE.addLast(reward);
		if (current == null) startNext(false);
	}

	private static void startNext(boolean chained) {
		Reward next = QUEUE.pollFirst();
		if (next == null) {
			current = null;
			return;
		}
		ModConfig cfg = ModConfig.get();
		AnimationType type = (next.animation() != null ? next.animation() : cfg.serverAnimation).resolve(RANDOM);
		current = CasinoAnimation.create(type, next, seedCounter++, chained, cfg.revealSeconds);
		lastFrameNanos = -1L;
	}

	/** Läuft gerade eine Animation (oder wartet eine)? */
	public static boolean isActive() {
		return current != null;
	}

	/** Soll Chat/Titel/Actionbar/Bossleiste versteckt werden? */
	public static boolean shouldHideVanillaText() {
		return current != null && ModConfig.get().hideChatAndTitles;
	}

	public static int queuedCount() {
		return QUEUE.size();
	}

	public static void clear() {
		QUEUE.clear();
		current = null;
	}

	public static void skip() {
		if (current != null) current.skip();
	}

	/** Pausiert, solange ein Menü (außer dem Chat) offen ist - so verpasst man keine Animation. */
	private static boolean isPaused(Minecraft mc) {
		Screen screen = mc.gui.screen();
		if (screen != null && !(screen instanceof ChatScreen)) return true;
		return mc.isPaused();
	}

	/** Wird vom HUD in jedem Frame aufgerufen. */
	public static void render(GuiGraphicsExtractor g) {
		if (current == null) return;
		Minecraft mc = Minecraft.getInstance();
		long now = System.nanoTime();
		float dt = lastFrameNanos < 0 ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
		lastFrameNanos = now;
		dt = Math.min(dt, 0.1f); // nach Rucklern / unsichtbarer Zeit nicht springen

		ModConfig cfg = ModConfig.get();
		if (!isPaused(mc)) {
			current.advance(dt * cfg.speed, cfg.volume);
		}
		if (current.isFinished(!QUEUE.isEmpty())) {
			startNext(true);
			if (current == null) return;
		}
		g.nextStratum();
		current.render(g, g.guiWidth(), g.guiHeight(), QUEUE.size(), skipKeyName.get());
	}
}
