package de.schiessbude.gui;

import de.schiessbude.SchiessbudeClient;
import de.schiessbude.anim.CasinoAnimation;
import de.schiessbude.config.AnimationType;
import de.schiessbude.config.ModConfig;
import de.schiessbude.config.Reward;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Random;

/**
 * Vorschau der Animation ohne Welt (z. B. aus dem Hauptmenü über Mod Menu).
 * Klick = überspringen, ESC = schließen. Läuft in Schleife.
 */
public class PreviewScreen extends Screen {
	private final Screen parent;
	private final String forcedPrize;
	private final AnimationType type;
	private final Random random = new Random();
	private CasinoAnimation anim;
	private long last = -1;

	public PreviewScreen(Screen parent, AnimationType type) {
		this(parent, null, type);
	}

	/**
	 * @param forcedPrize Gewinn-ID oder null (zufällig)
	 * @param type        Animation; {@link AnimationType#RANDOM} wechselt bei jeder Runde
	 */
	public PreviewScreen(Screen parent, String forcedPrize, AnimationType type) {
		super(Component.literal("Schießbuden-Vorschau"));
		this.parent = parent;
		this.forcedPrize = forcedPrize;
		this.type = type == null ? AnimationType.RANDOM : type;
		restart();
	}

	private void restart() {
		Reward reward = SchiessbudeClient.createTestReward("Vorschau", forcedPrize, type);
		anim = CasinoAnimation.create(type.resolve(random), reward, System.nanoTime(), false, ModConfig.get().revealSeconds);
		last = -1;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// Die Animation deckt alles ab
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		long now = System.nanoTime();
		float dt = last < 0 ? 0f : Math.min(0.1f, (now - last) / 1_000_000_000f);
		last = now;
		ModConfig cfg = ModConfig.get();
		anim.advance(dt * cfg.speed, cfg.volume);
		if (anim.isFinished(false)) restart();
		anim.render(graphics, this.width, this.height, 0, "Klick");
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		anim.skip();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}
}
