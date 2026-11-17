package de.schiessbude.anim;

import de.schiessbude.config.ModConfig;
import de.schiessbude.config.PrizeDefinition;
import de.schiessbude.config.Rarity;
import de.schiessbude.config.Reward;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Die Slotmaschinen-Animation (im Erz-Test: Kohle).
 * <p>
 * Ablauf: Eine Hand greift den Hebel rechts an der Maschine und zieht ihn runter. Die drei Walzen drehen sich
 * und bleiben nacheinander stehen, jede mit deinem Gewinn auf der Gewinnlinie. Die letzte Walze kriecht langsam aus,
 * bleibt zur Spannung erst eine Reihe zu früh stehen und rutscht dann auf den Gewinn. Münzen fliegen,
 * die Kamera zoomt heran und der Gewinn wird groß enthüllt.
 */
public final class SlotMachineAnimation extends CasinoAnimation {
	// ---- Layout in virtuellen Einheiten (passt zu tools/generate_textures.py) ----
	static final float MX = 160f, MY = 48f, MW = 320f, MH = 292f;
	static final float WIN_X = 205f, WIN_Y = 124f, WIN_W = 230f, WIN_H = 126f;
	static final float[] REEL_X = {WIN_X + WIN_W / 6f, WIN_X + WIN_W / 2f, WIN_X + WIN_W * 5f / 6f};
	/** Gewinnlinie (Mitte des Fensters), Walzen-Radius, Winkel zwischen zwei Symbolen. */
	static final float PAY_Y = 187f, REEL_R = 63f, STEP = 0.6f, SYMBOL = 30f;
	static final float PIVOT_X = 467f, PIVOT_Y = 226f, LEVER_L = 112f, KNOB = 26f;
	static final float CROWN_X = 320f, CROWN_Y = 112f, CROWN_RX = 108f, CROWN_RY = 46f;
	static final float TRAY_X = 320f, TRAY_Y = 311f;
	static final int STRIP = 22;
	static final float ACCEL = 0.3f, SPIN_SPEED = 13f;

	static final Identifier T_BG = Gfx.id("textures/gui/slot_background.png");
	static final Identifier T_MACHINE = Gfx.id("textures/gui/slot_machine.png");
	static final Identifier T_GLASS = Gfx.id("textures/gui/slot_glass.png");
	static final Identifier T_KNOB = Gfx.id("textures/gui/lever_knob.png");

	private final Reel[] reels = new Reel[3];
	private final float tPullStart, tPullDown, tLeverBack;
	private final List<float[]> suspense = new ArrayList<>();
	private final List<Coin> coins = new ArrayList<>();

	public SlotMachineAnimation(Reward reward, long seed, boolean chained, float revealSeconds) {
		super(reward, seed, chained);
		PrizeDefinition win = reward.prize();

		// Maschine geht an: Lichter-Arpeggio
		for (int i = 0; i < 3; i++) cue(t0 + i * 0.08f, SoundEvents.NOTE_BLOCK_PLING, 0.9f + i * 0.22f, 0.3f);

		// --- Hebel ---
		tPullStart = t0 + 0.32f;
		tPullDown = tPullStart + 0.3f;
		tLeverBack = tPullDown + 0.12f;
		cue(tPullStart, SoundEvents.LEVER_CLICK, 0.7f, 0.6f);
		cue(tPullDown, SoundEvents.LEVER_CLICK, 1.0f, 0.8f);
		cue(tPullDown, SoundEvents.PLAYER_ATTACK_SWEEP, 1.6f, 0.35f);
		cue(tLeverBack + 0.12f, SoundEvents.IRON_DOOR_CLOSE, 1.9f, 0.2f);

		// --- Walzen ---
		boolean legendary = rarity == Rarity.LEGENDARY;
		float lastHold = rarity.atLeast(Rarity.EPIC) ? 1.05f : 0.75f;
		for (int i = 0; i < 3; i++) reels[i] = new Reel(rnd.nextInt(STRIP));
		float start = tPullDown + 0.02f;
		reels[0].plan(start, start + 1.28f, 0.42f, false, 0f);
		reels[1].plan(start + 0.07f, reels[0].tStop + 0.35f, 0.45f, legendary, 0.45f);
		float decel3 = Math.max(reels[1].tStop + 0.35f, reels[1].settled + 0.12f);
		reels[2].plan(start + 0.14f, decel3, 1.2f, true, lastHold);
		for (Reel r : reels) r.fillStrip(win);

		for (int i = 0; i < 3; i++) {
			Reel r = reels[i];
			cue(r.tStop, SoundEvents.NOTE_BLOCK_BASEDRUM, 1.2f, 0.7f);
			cue(r.tStop, SoundEvents.STONE_BUTTON_CLICK_ON, 0.9f, 0.6f);
			if (r.nudgeAt < 0f) {
				cue(r.tStop + 0.03f, SoundEvents.NOTE_BLOCK_PLING, 1.0f + 0.25f * i, 0.5f);
			} else {
				// Knapp daneben ... Herzklopfen ... und doch!
				cue(r.tStop + 0.03f, SoundEvents.NOTE_BLOCK_BASS, 0.7f, 0.6f);
				suspense.add(new float[]{r.tStop, r.nudgeAt});
				heartbeat(r.tStop + 0.1f, r.nudgeAt);
				cue(r.nudgeAt, SoundEvents.STONE_BUTTON_CLICK_ON, 1.3f, 0.7f);
				cue(r.nudgeAt + 0.05f, SoundEvents.NOTE_BLOCK_PLING, 1.0f + 0.25f * i, 0.6f);
			}
		}
		scheduleTicks();

		float win0 = reels[2].settled + 0.05f;
		// Münzregen
		int coinCount = 10 + rarity.ordinal() * 7;
		for (int i = 0; i < coinCount; i++) coins.add(new Coin(rnd, TRAY_X, TRAY_Y - 4f, i * 0.025f));
		cue(win0, SoundEvents.ARMOR_EQUIP_GOLD, 1.2f, 0.6f);
		for (int i = 0; i < 6 + rarity.ordinal() * 2; i++) {
			cue(win0 + 0.05f + i * 0.07f, SoundEvents.NOTE_BLOCK_CHIME, 1.5f + rnd.nextFloat() * 0.5f, 0.28f);
		}
		finishTimeline(win0, revealSeconds);
	}

	/** Leises Rattern der Walzen: ein Klick pro Symbol der Walze, die am längsten läuft. */
	private void scheduleTicks() {
		int[] last = new int[3];
		for (int i = 0; i < 3; i++) last[i] = Math.round(reels[i].pos(0f));
		float lastTick = -1f;
		float end = reels[2].settled;
		for (float time = reels[0].tStart; time <= end; time += 1f / 240f) {
			for (int i = 2; i >= 0; i--) {
				Reel r = reels[i];
				if (time < r.tStart || time > r.tStop) continue;
				int now = Math.round(r.pos(time));
				if (now != last[i]) {
					last[i] = now;
					if (time - lastTick > 0.05f) {
						cue(time, SoundEvents.NOTE_BLOCK_HAT, 1.5f + rnd.nextFloat() * 0.2f, 0.16f);
						lastTick = time;
					}
				}
				break;
			}
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	@Override
	protected float focusX() {
		return REEL_X[1];
	}

	@Override
	protected float focusY() {
		return PAY_Y;
	}

	@Override
	protected float focusZoom() {
		return 1.7f;
	}

	@Override
	protected float sceneShake() {
		float amount = 0f;
		for (Reel r : reels) {
			amount = Math.max(amount, kick(t - r.tStop, 0.14f, 1.4f));
			if (r.nudgeAt >= 0f) amount = Math.max(amount, kick(t - r.nudgeAt, 0.16f, 1.8f));
		}
		amount = Math.max(amount, kick(t - tPullDown, 0.15f, 1.2f));
		if (inSuspense()) amount = Math.max(amount, 0.5f);
		return amount;
	}

	private static float kick(float dt, float duration, float strength) {
		return dt >= 0f && dt < duration ? strength * (1f - dt / duration) : 0f;
	}

	private boolean inSuspense() {
		for (float[] w : suspense) {
			if (t >= w[0] && t <= w[1]) return true;
		}
		return false;
	}

	@Override
	protected void drawScene(GuiGraphicsExtractor g) {
		Gfx.tex(g, T_BG, 0, 0, VW, VH, Gfx.argb(0xFFFFFFFF, 1f));
		drawAwningBulbs(g);

		float appear = chained ? 1f : Gfx.easeOutBack(Gfx.progress(t, 0.05f, 0.5f));
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(0f, (1f - appear) * 70f);

		Gfx.tex(g, T_MACHINE, MX, MY, MW, MH, Gfx.argb(0xFFFFFFFF, 1f));
		drawCrown(g);
		for (int i = 0; i < 3; i++) drawReel(g, i);
		drawPayline(g);
		Gfx.tex(g, T_GLASS, WIN_X, WIN_Y, WIN_W, WIN_H, Gfx.argb(0xFFFFFFFF, 1f));
		drawLever(g);
		drawPullingHand(g);
		p.popMatrix();

		if (t >= tWin) {
			for (Coin c : coins) c.draw(g, t - tWin);
		}
		drawSign(g);
	}

	private void drawCrown(GuiGraphicsExtractor g) {
		boolean won = t >= tWin;
		boolean tense = inSuspense();
		int step = (int) Math.floor(t * (won ? 14f : tense ? 10f : 5f));
		for (int i = 0; i < 9; i++) {
			double a = Math.toRadians(196 + i * 148.0 / 8);
			float x = CROWN_X + CROWN_RX * (float) Math.cos(a);
			float y = CROWN_Y + CROWN_RY * (float) Math.sin(a);
			boolean on;
			int col;
			if (won) {
				on = (i + step) % 3 != 0;
				col = Gfx.lerpColor(0xFFFFE9A0, rarity.color, 0.7f);
			} else if (tense) {
				on = step % 2 == 0;
				col = 0xFFFF5060;
			} else {
				on = (i + step) % 2 == 0;
				col = 0xFFFFE9A0;
			}
			drawBulb(g, x, y, on, col, 0.9f);
		}
		int textCol = GOLD;
		float scale = 1.45f;
		if (won) {
			float f = (float) (Math.sin((t - tWin) * 14f) * 0.5 + 0.5);
			textCol = Gfx.lerpColor(rarity.color, 0xFFFFFFFF, f * 0.6f);
			scale *= 1f + 0.08f * (float) Math.sin((t - tWin) * 9f) * (1f - Gfx.progress(t, tWin, 1.2f));
		} else if (tense) {
			textCol = Gfx.lerpColor(GOLD, 0xFFFF5060, (float) (Math.sin(t * 20f) * 0.5 + 0.5));
		}
		Gfx.outlinedText(g, "JACKPOT", CROWN_X, 99f, scale, textCol, OUTLINE, 1f);
	}

	private void drawReel(GuiGraphicsExtractor g, int i) {
		Reel r = reels[i];
		float pos = r.pos(t);
		float blur = Gfx.clamp01((Math.abs(r.speed(t)) - 2.5f) / 8f);
		boolean won = t >= tWin;
		float x = REEL_X[i];
		int center = Math.round(pos);
		for (int k = center - 3; k <= center + 3; k++) {
			float phi = (pos - k) * STEP;
			if (Math.abs(phi) > 1.45f) continue;
			float c = (float) Math.cos(phi);
			float y = PAY_Y + REEL_R * (float) Math.sin(phi);
			PrizeDefinition prize = r.strip[Math.floorMod(k, STRIP)];
			int shade = Gfx.lerpColor(0xFF5A4A66, 0xFFFFFFFF, c);
			boolean winSymbol = won && k == r.stopIndex;
			float pulse = 1f;
			if (winSymbol) {
				float hp = Gfx.progress(t, tWin, 0.3f);
				pulse = 1f + 0.1f * (float) Math.sin((t - tWin) * 8f) * hp;
				Gfx.texCentered(g, T_GLOW, x, y, 86 * pulse, 70 * pulse, 0f, Gfx.argb(rarity.color, 0.85f * hp));
			} else {
				Gfx.texCentered(g, T_GLOW, x, y, 54, 54 * c, 0f, Gfx.argb(prize.rarity.color, glowAlpha(prize.rarity) * c * (1f - blur)));
			}
			float w = SYMBOL * pulse;
			float h = SYMBOL * c * pulse * (1f + blur * 0.9f);
			PrizeIcon icon = PrizeIcon.of(prize);
			if (blur > 0.05f) {
				icon.draw(g, x, y - 6f * blur, w, h, 0f, Gfx.argb(shade, 0.3f * blur));
				icon.draw(g, x, y + 6f * blur, w, h, 0f, Gfx.argb(shade, 0.3f * blur));
			}
			icon.draw(g, x, y, w, h, 0f, Gfx.argb(shade, 1f - 0.45f * blur));
		}
		if (won) drawSparkles(g, x, PAY_Y, 24f, 4, t - tWin + i);
	}

	private void drawPayline(GuiGraphicsExtractor g) {
		if (t < tWin) {
			Gfx.rect(g, WIN_X + 2f, PAY_Y - 0.9f, WIN_W - 4f, 1.8f, Gfx.argb(0xFFE63A4A, 0.55f));
			return;
		}
		float hp = Gfx.progress(t, tWin, 0.25f);
		float pulse = 0.6f + 0.4f * (float) Math.sin((t - tWin) * 10f);
		Gfx.rect(g, WIN_X + 2f, PAY_Y - 2.5f, WIN_W - 4f, 5f, Gfx.argb(rarity.color, 0.35f * hp * pulse));
		Gfx.rect(g, WIN_X + 2f, PAY_Y - 1f, WIN_W - 4f, 2f, Gfx.argb(Gfx.lerpColor(rarity.color, 0xFFFFFFFF, 0.5f), 0.8f * hp));
	}

	// ---- Hebel und Hand -----------------------------------------------------------------------------------------------

	/** 0 = Hebel oben, 1 = ganz unten. */
	private float lever(float time) {
		if (time < tPullStart) return 0f;
		if (time < tPullDown) return Gfx.easeInOutCubic(Gfx.progress(time, tPullStart, tPullDown - tPullStart));
		if (time < tLeverBack) return 1f;
		return 1f - Gfx.easeOutElastic(Gfx.progress(time, tLeverBack, 0.7f));
	}

	/** Knauf-Position: Der Hebel kippt nach vorne zum Betrachter (daher wird er kürzer und der Knauf größer). */
	private float[] knob(float time) {
		float theta = lever(time) * 2.55f;
		return new float[]{
				PIVOT_X + 5f * (float) Math.sin(theta),
				PIVOT_Y - LEVER_L * (float) Math.cos(theta),
				1f + 0.22f * (float) Math.sin(theta)
		};
	}

	private void drawLever(GuiGraphicsExtractor g) {
		float[] k = knob(t);
		float d = k[2];
		Gfx.line(g, PIVOT_X, PIVOT_Y, k[0], k[1], 8f * d, Gfx.argb(OUTLINE, 1f));
		Gfx.line(g, PIVOT_X, PIVOT_Y, k[0], k[1], 4.6f * d, Gfx.argb(0xFFC8D0EC, 1f));
		Gfx.line(g, PIVOT_X - 1f, PIVOT_Y, k[0] - 1f, k[1], 1.4f * d, Gfx.argb(0xFFF4F6FF, 1f));
		Gfx.texCentered(g, T_DISC, PIVOT_X, PIVOT_Y, 13f, 13f, 0f, Gfx.argb(OUTLINE, 1f));
		Gfx.texCentered(g, T_DISC, PIVOT_X, PIVOT_Y, 9f, 9f, 0f, Gfx.argb(0xFF8A90B8, 1f));
		Gfx.texCentered(g, T_KNOB, k[0], k[1], KNOB * d, KNOB * d, 0f, Gfx.argb(0xFFFFFFFF, 1f));
	}

	private void drawPullingHand(GuiGraphicsExtractor g) {
		float in = Gfx.easeOutCubic(Gfx.progress(t, t0 - 0.05f, 0.34f));
		float out = Gfx.easeInCubic(Gfx.progress(t, tPullDown + 0.06f, 0.35f));
		if (in <= 0f || out >= 1f) return;
		float grip = Math.min(t, tPullDown);
		float[] k = knob(grip);
		float x = Gfx.lerp(VW + 170f, k[0] + 2f, in) + out * 230f;
		float y = Gfx.lerp(k[1] - 40f, k[1], in) + out * 50f;
		float angle = -0.25f + 0.55f * lever(grip);
		drawHand(g, x, y, angle, k[2], false);
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** Eine Walze: Symbolstreifen und Bewegungsplan. Position in Symbolen; ganze Zahl = Symbol auf der Linie. */
	private final class Reel {
		final PrizeDefinition[] strip = new PrizeDefinition[STRIP];
		final int posStart;
		float tStart, tDecel, decelDur, v, tStop, restPos;
		/** Near-Miss: bleibt erst eine Reihe zu früh stehen und rutscht zu diesem Zeitpunkt nach. */
		float nudgeAt = -1f;
		float settled;
		int stopIndex;

		Reel(int posStart) {
			this.posStart = posStart;
		}

		void plan(float tStart, float tDecel, float decelDur, boolean nearMiss, float hold) {
			this.tStart = tStart;
			this.tDecel = tDecel;
			this.decelDur = decelDur;
			// Weg so wählen, dass die Walze genau auf einem Symbol stehen bleibt
			float unit = 0.5f * ACCEL + (tDecel - tStart - ACCEL) + 0.5f * decelDur;
			int dist = Math.max(1, Math.round(SPIN_SPEED * unit));
			v = dist / unit;
			tStop = tDecel + decelDur;
			restPos = posStart + dist;
			if (nearMiss) {
				stopIndex = posStart + dist + 1;
				nudgeAt = tStop + hold;
				settled = nudgeAt + 0.3f;
			} else {
				stopIndex = posStart + dist;
				settled = tStop + 0.3f;
			}
		}

		void fillStrip(PrizeDefinition win) {
			List<PrizeDefinition> all = ModConfig.get().prizes;
			for (int i = 0; i < STRIP; i++) {
				// halb gleichverteilt (damit auch seltene Symbole vorbeiflitzen), halb nach Gewichtung
				strip[i] = rnd.nextBoolean() ? all.get(rnd.nextInt(all.size())) : ModConfig.get().randomPrize(rnd);
			}
			// Nachbarn des Gewinns sind nie der Gewinn selbst: es gibt nur eine Gewinnlinie
			for (int d = -2; d <= 2; d++) {
				int idx = Math.floorMod(stopIndex + d, STRIP);
				if (d != 0 && strip[idx] == win) strip[idx] = randomOther(win);
			}
			strip[Math.floorMod(stopIndex, STRIP)] = win;
		}

		float pos(float time) {
			if (time < tStart - 0.12f) return posStart;
			if (time < tStart) { // kurz Ausholen
				float p = (time - (tStart - 0.12f)) / 0.12f;
				return posStart - 0.22f * (float) Math.sin(p * Math.PI);
			}
			float dt = time - tStart;
			if (dt < ACCEL) return posStart + 0.5f * v * dt * dt / ACCEL;
			float base = posStart + 0.5f * v * ACCEL;
			if (time < tDecel) return base + v * (time - tStart - ACCEL);
			base += v * (tDecel - tStart - ACCEL);
			float d = time - tDecel;
			if (d < decelDur) return base + v * (d - d * d / (2f * decelDur));
			if (nudgeAt >= 0f && time >= nudgeAt) {
				return restPos + Gfx.easeOutBack(Gfx.progress(time, nudgeAt, 0.22f));
			}
			return restPos + bounce(time - tStop);
		}

		/** Kleines Nachfedern beim Einrasten. */
		private static float bounce(float since) {
			if (since < 0f || since > 0.3f) return 0f;
			float p = since / 0.3f;
			return 0.14f * (float) Math.sin(p * Math.PI) * (1f - p);
		}

		float speed(float time) {
			return (pos(time + 0.01f) - pos(time - 0.01f)) / 0.02f;
		}
	}
}
