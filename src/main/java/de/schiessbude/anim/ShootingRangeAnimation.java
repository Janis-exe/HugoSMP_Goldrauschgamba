package de.schiessbude.anim;

import de.schiessbude.config.PrizeDefinition;
import de.schiessbude.config.Rarity;
import de.schiessbude.config.Reward;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Die Schießbuden-Animation (im Erz-Test: Kupfer).
 * <p>
 * Ablauf: Schießbude erscheint, Zielscheiben mit verschiedenen Gewinnen klappen hoch, die Pistole in der Mitte
 * schießt eine Scheibe nach der anderen um, bis nur noch dein Gewinn steht. Der letzte Schuss läuft jedes Mal
 * anders ab (Täuschung, langes Zielen, Pendeln oder Ladehemmung). Dann zoomt die Kamera heran und der Gewinn wird groß enthüllt.
 */
public final class ShootingRangeAnimation extends CasinoAnimation {
	// ---- Layout in virtuellen Einheiten (passt zu tools/generate_textures.py) ----
	/** x, Oberkante des Podests, Stufe (0 = hinten). */
	static final float[][] SLOTS = {
			{250, 124, 0}, {320, 124, 0}, {390, 124, 0},
			{150, 171, 1}, {490, 171, 1},
			{100, 221, 2}, {540, 221, 2}
	};
	static final float[] TIER_SCALE = {0.86f, 0.93f, 1.0f};
	static final float BOARD = 56f, RING = 64f, STICK = 16f, ICON = 31f;
	static final float PIVOT_X = 320f, PIVOT_Y = 318f;
	static final float PISTOL_W = 64f, PISTOL_H = 150f, PISTOL_PX = 32f, PISTOL_PY = 126f, MUZZLE = 120f;
	static final float BULLET_TIME = 0.06f;
	static final float FALL_TIME = 0.36f;

	// ---- Texturen ----
	static final Identifier T_BG = Gfx.id("textures/gui/background.png");
	static final Identifier T_BOARD = Gfx.id("textures/gui/target_board.png");
	static final Identifier T_PISTOL = Gfx.id("textures/gui/pistol.png");
	static final Identifier T_MOUNT = Gfx.id("textures/gui/mount.png");
	static final Identifier T_FLASH = Gfx.id("textures/gui/muzzle_flash.png");

	private final List<Target> targets = new ArrayList<>();
	private final Target winner;
	private final List<Shot> shots = new ArrayList<>();
	private final List<float[]> gunKeys = new ArrayList<>();
	private final List<float[]> suspense = new ArrayList<>();

	public ShootingRangeAnimation(Reward reward, long seed, boolean chained, float revealSeconds) {
		super(reward, seed, chained);

		// --- Ziele verteilen: der Gewinn steht auf einem zufälligen Platz, die anderen sind Köder ---
		int winnerSlot = rnd.nextInt(SLOTS.length);
		List<PrizeDefinition> decoyPrizes = pickDecoyPrizes(SLOTS.length - 1);
		int d = 0;
		for (int i = 0; i < SLOTS.length; i++) {
			boolean isWinner = i == winnerSlot;
			PrizeDefinition prize = isWinner ? reward.prize() : decoyPrizes.get(d++);
			targets.add(new Target(i, prize, isWinner, rnd.nextFloat() * 6.28f));
		}
		this.winner = targets.get(winnerSlot);

		// Aufklapp-Reihenfolge: hinten links -> vorne rechts
		float popStart = t0;
		for (int i = 0; i < targets.size(); i++) {
			Target tg = targets.get(i);
			tg.popTime = popStart + i * 0.085f;
			cue(tg.popTime + 0.05f, SoundEvents.NOTE_BLOCK_PLING, 0.85f + i * 0.09f, 0.35f);
		}

		// --- Schuss-Plan ---
		List<Target> decoys = new ArrayList<>(targets);
		decoys.remove(winner);
		Collections.shuffle(decoys, rnd);

		float time = popStart + targets.size() * 0.085f + 0.45f;
		cue(time - 0.12f, SoundEvents.CROSSBOW_LOADING_END, 1.0f, 0.8f);
		float angle = 0f;
		key(0f, 0f);
		key(time, 0f);
		int n = decoys.size();
		for (int k = 0; k < n; k++) {
			Target target = decoys.get(k);
			boolean last = k == n - 1;
			// Spannung vor dem letzten Schuss, jedes Mal anders; bei Episch/Legendär manchmal schon davor
			boolean preDrama = !last && k == n - 2 && rnd.nextFloat() < (rarity.atLeast(Rarity.EPIC) ? 0.5f : 0.2f);
			if (last) {
				time = lastShotDrama(time, target);
			} else if (preDrama) {
				// kurz auf deinen Gewinn zielen, dann doch einen anderen treffen
				float onWinner = time + 0.38f;
				key(onWinner, angleOf(winner));
				float hold = 0.35f + 0.3f * rnd.nextFloat();
				suspense.add(new float[]{onWinner, onWinner + hold});
				heartbeat(onWinner, onWinner + hold);
				time = onWinner + hold;
				key(time, angleOf(winner));
				time += 0.2f;
				key(time, angleOf(target));
				time += 0.1f;
			} else {
				float aim = Gfx.lerp(0.46f, 0.26f, n <= 2 ? 1f : (float) k / (n - 2));
				time += aim;
				key(time, angleOf(target));
				time += 0.07f;
			}
			key(time, angleOf(target));
			Shot shot = new Shot(target, time, rnd);
			shots.add(shot);
			target.hitTime = time + BULLET_TIME;
			cue(time, SoundEvents.FIREWORK_ROCKET_BLAST, 1.45f + rnd.nextFloat() * 0.2f, 0.75f);
			cue(time, SoundEvents.NOTE_BLOCK_SNARE, 1.6f, 0.5f);
			cue(target.hitTime, SoundEvents.ARROW_HIT, 0.9f + rnd.nextFloat() * 0.2f, 0.8f);
			cue(target.hitTime + 0.02f, SoundEvents.WOOD_BREAK, 1.1f, 0.5f);
			angle = angleOf(target);
			time += 0.16f;
		}
		float lastFall = time + BULLET_TIME + FALL_TIME - 0.1f;
		key(time + 0.1f, angle);
		key(time + 0.6f, angleOf(winner));
		finishTimeline(lastFall, revealSeconds);
	}

	/**
	 * Der letzte Schuss, zufällig einer von vier Abläufen. Gibt den Zeitpunkt zurück, an dem die Pistole
	 * auf das letzte Köder-Ziel zeigt und gleich schießt.
	 */
	private float lastShotDrama(float time, Target target) {
		float base = rarity.atLeast(Rarity.EPIC) ? 1.0f : 0.72f;
		float hold = base * (0.7f + 0.6f * rnd.nextFloat());
		float a = angleOf(target), w = angleOf(winner);
		switch (rnd.nextInt(4)) {
			case 0 -> {
				// Täuschung: erst auf deinen Gewinn zielen ... dann blitzschnell zum Köder
				float on = time + 0.42f;
				key(on, w);
				suspense.add(new float[]{on, on + hold});
				heartbeat(on, on + hold);
				time = on + hold;
				key(time, w);
				time += 0.2f;
				key(time, a);
				time += 0.1f;
			}
			case 1 -> {
				// Direkt auf den Köder zielen und lange warten
				float on = time + 0.4f;
				key(on, a);
				suspense.add(new float[]{on, on + hold});
				heartbeat(on, on + hold);
				time = on + hold;
			}
			case 2 -> {
				// Pendeln zwischen deinem Gewinn und dem Köder, immer langsamer
				time += 0.35f;
				key(time, w);
				float from = time;
				int swings = rnd.nextBoolean() ? 3 : 5; // ungerade: der letzte Schwenk endet beim Köder
				for (int i = 0; i < swings; i++) {
					time += 0.2f + i * 0.08f;
					key(time, i % 2 == 0 ? a : w);
				}
				time += 0.12f;
				key(time, a);
				suspense.add(new float[]{from, time});
				heartbeat(from, time);
			}
			default -> {
				// Ladehemmung: zielen, *klick* ... nichts ... dann der Schuss
				float on = time + 0.38f;
				key(on, a);
				time = on + 0.2f;
				key(time, a);
				cue(time, SoundEvents.DISPENSER_FAIL, 1.3f, 0.9f);
				suspense.add(new float[]{time, time + hold});
				heartbeat(time + 0.1f, time + hold);
				time += hold;
			}
		}
		return time;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Zeichnen
	// ---------------------------------------------------------------------------------------------------------------

	@Override
	protected float focusX() {
		return winner.x();
	}

	@Override
	protected float focusY() {
		return winner.boardCenterY();
	}

	@Override
	protected float sceneShake() {
		float amount = 0f;
		for (Shot shot : shots) {
			float dt = t - shot.fireTime;
			if (dt >= 0f && dt < 0.18f) amount = Math.max(amount, 2.2f * (1f - dt / 0.18f));
		}
		for (float[] w : suspense) {
			if (t >= w[0] && t <= w[1]) amount = Math.max(amount, 0.5f);
		}
		return amount;
	}

	@Override
	protected void drawScene(GuiGraphicsExtractor g) {
		Gfx.tex(g, T_BG, 0, 0, VW, VH, Gfx.argb(0xFFFFFFFF, 1f));
		drawAwningBulbs(g);

		// Ziele (hinten nach vorne)
		for (int tier = 0; tier <= 2; tier++) {
			for (Target tg : targets) {
				if (tg.tier() == tier) drawTarget(g, tg);
			}
		}

		// Trümmer und Funken
		for (Shot shot : shots) drawDebris(g, shot);

		// Leuchtspur der Kugel
		for (Shot shot : shots) {
			float dt = t - shot.fireTime;
			if (dt < 0f || dt > BULLET_TIME + 0.05f) continue;
			float[] muzzle = muzzlePos(shot.fireTime);
			float tx = shot.target.x(), ty = shot.target.boardCenterY();
			float head = Gfx.clamp01(dt / BULLET_TIME);
			float tail = Gfx.clamp01((dt - 0.03f) / BULLET_TIME);
			float hx = Gfx.lerp(muzzle[0], tx, head), hy = Gfx.lerp(muzzle[1], ty, head);
			float qx = Gfx.lerp(muzzle[0], tx, tail), qy = Gfx.lerp(muzzle[1], ty, tail);
			Gfx.line(g, qx, qy, hx, hy, 5f, Gfx.argb(0xFFFFB020, 0.85f));
			Gfx.line(g, qx, qy, hx, hy, 2f, Gfx.argb(0xFFFFFFFF, 1f));
			Gfx.texCentered(g, T_GLOW, hx, hy, 22, 22, 0f, Gfx.argb(0xFFFFE070, 0.9f));
		}

		drawGun(g);
		drawSign(g);
	}

	private void drawTarget(GuiGraphicsExtractor g, Target tg) {
		if (t < tg.popTime) return;
		float sc = TIER_SCALE[tg.tier()];
		float pop = Gfx.easeOutBack(Gfx.progress(t, tg.popTime, 0.32f));
		float scaleX = Gfx.lerp(0.55f, 1f, Gfx.clamp01(pop));
		float scaleY = pop;
		float sway = (float) Math.sin(t * 2.1f + tg.phase) * 0.035f;
		float liftY = 0f;
		float whiteFlash = 0f;

		if (tg.hitTime >= 0f && t >= tg.hitTime) {
			float dt = t - tg.hitTime;
			whiteFlash = 1f - Gfx.progress(dt, 0f, 0.1f);
			liftY = -(float) Math.sin(Gfx.progress(dt, 0f, 0.1f) * Math.PI) * 4f;
			float fall = Gfx.easeInCubic(Gfx.progress(dt, 0.05f, FALL_TIME));
			scaleY *= (float) Math.cos(fall * Math.PI / 2.0);
			sway += (float) Math.sin(dt * 40f) * 0.08f * (1f - Gfx.progress(dt, 0f, 0.2f));
			if (fall >= 0.999f) {
				// Umgekippt: nur noch die flache Kante
				float w = BOARD * sc;
				Gfx.rect(g, tg.x() - w / 2f - 1f, tg.baseY() - 3.5f, w + 2f, 4.5f, Gfx.argb(OUTLINE, 1f));
				Gfx.rect(g, tg.x() - w / 2f, tg.baseY() - 2.5f, w, 2.5f, Gfx.argb(0xFFE63A4A, 1f));
				return;
			}
		}

		// Gewinner hervorheben, sobald alle anderen gefallen sind
		boolean highlight = tg.winner && t >= tWin;
		float bob = 0f;
		if (highlight) {
			float hp = Gfx.progress(t, tWin, 0.4f);
			bob = (float) Math.sin((t - tWin) * 5f) * 2.5f * hp;
			float pulse = 1f + (float) Math.sin(t * 7f) * 0.08f;
			float cy = tg.boardCenterY();
			Gfx.texCentered(g, T_RAYS, tg.x(), cy, 150 * sc * pulse * hp, 150 * sc * pulse * hp, t * 0.8f, Gfx.argb(rarity.color, 0.55f * hp));
			Gfx.texCentered(g, T_GLOW, tg.x(), cy, 120 * sc * pulse, 120 * sc * pulse, 0f, Gfx.argb(rarity.color, 0.9f * hp));
			if (t >= tZoomStart) {
				float zp = Gfx.progress(t, tZoomStart, tReveal - tZoomStart);
				sway += (float) Math.sin(t * 55f) * 0.05f * zp;
			}
		}

		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(tg.x(), tg.baseY() + liftY + bob);
		p.rotate(sway);
		p.scale(scaleX * sc, Math.max(0.001f, scaleY) * sc);

		float boardCy = -(STICK + BOARD / 2f);
		// Stiel
		Gfx.rect(g, -3.2f, -STICK - 4f, 6.4f, STICK + 4f, Gfx.argb(OUTLINE, 1f));
		Gfx.rect(g, -1.7f, -STICK - 3f, 3.4f, STICK + 3f, Gfx.argb(0xFF9B6A44, 1f));
		// Seltenheits-Schein (zeigt, wie wertvoll das Ziel ist)
		float glowA = glowAlpha(tg.prize.rarity);
		Gfx.texCentered(g, T_GLOW, 0, boardCy, BOARD * 1.7f, BOARD * 1.7f, 0f, Gfx.argb(tg.prize.rarity.color, glowA));
		Gfx.texCentered(g, T_RING, 0, boardCy, RING, RING, 0f, Gfx.argb(tg.prize.rarity.color, 1f));
		Gfx.texCentered(g, T_BOARD, 0, boardCy, BOARD, BOARD, 0f, Gfx.argb(0xFFFFFFFF, 1f));
		icon(tg.prize).draw(g, 0, boardCy, ICON, 0f, 1f);
		if (whiteFlash > 0f) {
			Gfx.texCentered(g, T_DISC, 0, boardCy, BOARD, BOARD, 0f, Gfx.argb(0xFFFFFFFF, whiteFlash));
		}
		p.popMatrix();

		// Funkeln um den Gewinner
		if (highlight) drawSparkles(g, tg.x(), tg.boardCenterY(), 34f * sc, 6, t - tWin);
	}

	private void drawDebris(GuiGraphicsExtractor g, Shot shot) {
		float dt = t - shot.target.hitTime;
		if (dt < 0f || dt > 0.9f) return;
		float cx = shot.target.x(), cy = shot.target.boardCenterY();
		// Aufprall-Blitz
		if (dt < 0.12f) {
			float f = 1f - dt / 0.12f;
			Gfx.texCentered(g, T_GLOW, cx, cy, 70 * f + 20, 70 * f + 20, 0f, Gfx.argb(0xFFFFF0B0, f));
			Gfx.texCentered(g, T_SPARKLE, cx, cy, 40 * f + 8, 40 * f + 8, shot.spin, Gfx.argb(0xFFFFFFFF, f));
		}
		int rc = shot.target.prize.rarity.color;
		for (int i = 0; i < shot.debris.length; i++) {
			float[] d = shot.debris[i];
			float life = 0.55f + d[4] * 0.35f;
			if (dt > life) continue;
			float x = cx + d[0] * dt;
			float y = cy + d[1] * dt + 0.5f * 620f * dt * dt;
			float a = 1f - dt / life;
			int col = switch (i % 3) {
				case 0 -> 0xFFFFFFFF;
				case 1 -> 0xFFE63A4A;
				default -> rc;
			};
			Matrix3x2fStack p = g.pose();
			p.pushMatrix();
			p.translate(x, y);
			p.rotate(d[3] * dt * 8f);
			float size = d[2];
			Gfx.rect(g, -size / 2f - 0.8f, -size / 2f - 0.8f, size + 1.6f, size + 1.6f, Gfx.argb(OUTLINE, a));
			Gfx.rect(g, -size / 2f, -size / 2f, size, size, Gfx.argb(col, a));
			p.popMatrix();
		}
	}

	private float gunAngle(float time) {
		float angle;
		if (time <= gunKeys.getFirst()[0]) {
			angle = gunKeys.getFirst()[1];
		} else if (time >= gunKeys.getLast()[0]) {
			angle = gunKeys.getLast()[1];
		} else {
			angle = gunKeys.getLast()[1];
			for (int i = 0; i < gunKeys.size() - 1; i++) {
				float[] a = gunKeys.get(i), b = gunKeys.get(i + 1);
				if (time >= a[0] && time <= b[0]) {
					float f = b[0] - a[0] < 1e-4f ? 1f : (time - a[0]) / (b[0] - a[0]);
					angle = Gfx.lerp(a[1], b[1], Gfx.smooth(f));
					break;
				}
			}
		}
		for (float[] w : suspense) {
			if (time >= w[0] && time <= w[1]) {
				float k = Gfx.progress(time, w[0], 0.15f);
				angle += (float) Math.sin(time * 38f) * 0.018f * k;
			}
		}
		return angle;
	}

	private float recoil(float time) {
		float r = 0f;
		for (Shot shot : shots) {
			float dt = time - shot.fireTime;
			if (dt >= 0f && dt < 0.28f) {
				float f = 1f - dt / 0.28f;
				r = Math.max(r, 9f * f * f);
			}
		}
		return r;
	}

	private float[] muzzlePos(float time) {
		float a = gunAngle(time);
		float dist = MUZZLE - recoil(time);
		return new float[]{PIVOT_X + (float) Math.sin(a) * dist, PIVOT_Y - (float) Math.cos(a) * dist};
	}

	private void drawGun(GuiGraphicsExtractor g) {
		float a = gunAngle(t);
		float kick = recoil(t);
		float appear = chained ? 1f : Gfx.easeOutBack(Gfx.progress(t, 0.2f, 0.45f));
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(PIVOT_X, PIVOT_Y + (1f - appear) * 70f);
		p.rotate(a);
		p.translate(0, kick);
		float cock = Gfx.progress(t, gunKeys.get(1)[0] - 0.15f, 0.15f);
		float squash = 1f + (float) Math.sin(cock * Math.PI) * 0.05f;
		p.scale(1f / squash, squash);
		Gfx.tex(g, T_PISTOL, -PISTOL_PX, -PISTOL_PY, PISTOL_W, PISTOL_H, Gfx.argb(0xFFFFFFFF, 1f));
		p.popMatrix();

		// Sockel mit Kristall
		Gfx.tex(g, T_MOUNT, PIVOT_X - 66, VH - 62 + (1f - appear) * 70f, 132, 62, Gfx.argb(0xFFFFFFFF, 1f));
		boolean tense = false;
		for (float[] w : suspense) tense |= t >= w[0] && t <= w[1];
		float gem = tense ? 0.6f + 0.4f * (float) Math.sin(t * 20f) : 0.25f + 0.15f * (float) Math.sin(t * 3f);
		Gfx.texCentered(g, T_GLOW, PIVOT_X, VH - 22, 46, 46, 0f, Gfx.argb(0xFFFF66E0, gem));

		// Mündungsfeuer
		for (Shot shot : shots) {
			float dt = t - shot.fireTime;
			if (dt < 0f || dt > 0.1f) continue;
			float f = dt < 0.03f ? dt / 0.03f : 1f - (dt - 0.03f) / 0.07f;
			float[] m = muzzlePos(t);
			float ox = (float) Math.sin(a) * 10f, oy = -(float) Math.cos(a) * 10f;
			Gfx.texCentered(g, T_GLOW, m[0] + ox, m[1] + oy, 90 * f, 90 * f, 0f, Gfx.argb(0xFFFFD050, f * 0.9f));
			Gfx.texCentered(g, T_FLASH, m[0] + ox, m[1] + oy, 50 * f + 6, 50 * f + 6, shot.spin, Gfx.argb(0xFFFFFFFF, Math.min(1f, f * 1.3f)));
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static float angleOf(Target tg) {
		return (float) Math.atan2(tg.x() - PIVOT_X, PIVOT_Y - tg.boardCenterY());
	}

	private void key(float time, float angle) {
		if (!gunKeys.isEmpty() && time < gunKeys.getLast()[0]) time = gunKeys.getLast()[0];
		gunKeys.add(new float[]{time, angle});
	}

	static final class Target {
		final int slot;
		final PrizeDefinition prize;
		final boolean winner;
		final float phase;
		float popTime;
		float hitTime = -1f;

		Target(int slot, PrizeDefinition prize, boolean winner, float phase) {
			this.slot = slot;
			this.prize = prize;
			this.winner = winner;
			this.phase = phase;
		}

		int tier() {
			return (int) SLOTS[slot][2];
		}

		float x() {
			return SLOTS[slot][0];
		}

		float baseY() {
			return SLOTS[slot][1] + 5f;
		}

		float boardCenterY() {
			return baseY() - (STICK + BOARD / 2f) * TIER_SCALE[tier()];
		}
	}

	static final class Shot {
		final Target target;
		final float fireTime;
		final float spin;
		/** vx, vy, Größe, Drehung, Lebensdauer-Zufall */
		final float[][] debris = new float[12][5];

		Shot(Target target, float fireTime, Random rnd) {
			this.target = target;
			this.fireTime = fireTime;
			this.spin = rnd.nextFloat() * 6.28f;
			for (float[] d : debris) {
				double a = rnd.nextDouble() * Math.PI * 2;
				float speed = 70f + rnd.nextFloat() * 150f;
				d[0] = (float) Math.cos(a) * speed;
				d[1] = (float) Math.sin(a) * speed - 110f;
				d[2] = 2.5f + rnd.nextFloat() * 3f;
				d[3] = rnd.nextFloat() * 2f - 1f;
				d[4] = rnd.nextFloat();
			}
		}
	}
}
