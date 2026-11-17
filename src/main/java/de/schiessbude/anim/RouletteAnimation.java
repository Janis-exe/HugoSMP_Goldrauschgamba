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
import java.util.Collections;
import java.util.List;

/**
 * Die Roulette-Animation (im Erz-Test: Gold, Eisen, Redstone, Diamant, Lapis).
 * <p>
 * Ablauf: Ein Croupier-Schieber schiebt den Chip-Stapel aufs Einsatzfeld. Eine Hand dreht das Rad am Griff in der
 * Mitte an und wirft die Kugel ein. Die Kugel kreist auf der Bahn, fällt in die Fächer, hüpft über ein paar Stege,
 * bleibt zur Spannung kurz in einem wertvolleren Fach liegen und springt dann in das Fach mit deinem Gewinn.
 * Das Rad läuft aus, bis dein Fach vorne steht. Dann zoomt die Kamera heran und der Gewinn wird groß enthüllt.
 * <p>
 * Das Rad wird von schräg oben gezeigt: gezeichnet wird es flach und dann senkrecht auf {@link #SQ} gestaucht.
 */
public final class RouletteAnimation extends CasinoAnimation {
	// ---- Layout in virtuellen Einheiten (passt zu tools/generate_textures.py) ----
	static final float WCX = 320f, WCY = 194f, SQ = 0.56f;
	/** Vergrößerung des Rads gegenüber den Grafik-Maßen (Schüssel 304 x 196, Rad 232 x 232). */
	static final float K = 1.22f;
	static final float WHEEL_R = 116f * K, R_TRACK = 127f * K, R_ICON = 94f * K, R_BALL = 106f * K;
	static final int POCKETS = 12;
	static final float STEP = (float) (Math.PI * 2 / POCKETS);
	static final float CHIP_X = 540f, CHIP_Y = 302f;
	static final float BALL = 10f * K, ICON = 23f * K;
	static final int[] CHIP_COLORS = {0xFFFFC93C, 0xFFB060FF, 0xFFE63A4A, 0xFF3FA7FF, 0xFF4CDB5A};

	static final Identifier T_BG = Gfx.id("textures/gui/roulette_background.png");
	static final Identifier T_BOWL = Gfx.id("textures/gui/roulette_bowl.png");
	static final Identifier T_WHEEL = Gfx.id("textures/gui/roulette_wheel.png");
	static final Identifier T_POCKET = Gfx.id("textures/gui/roulette_pocket.png");
	static final Identifier T_FRETS = Gfx.id("textures/gui/roulette_frets.png");
	static final Identifier T_TURRET = Gfx.id("textures/gui/roulette_turret.png");
	static final Identifier T_BALL = Gfx.id("textures/gui/ball.png");
	static final Identifier T_CHIP = Gfx.id("textures/gui/chip.png");
	static final Identifier T_RAKE = Gfx.id("textures/gui/rake.png");

	private final PrizeDefinition[] pockets = new PrizeDefinition[POCKETS];
	private final int winIdx;

	private final float tRakeIn, tRakeArrive, tRakeBack, tRakeGone;
	private final float tHandIn, tSpin, tLaunch, tDrop, tTease, tTeaseEnd, tRest, tWheelStop;
	private final float theta0, spinTotal;
	/** Kugel beim Rollen: absoluter Winkel = rollEnd + rollTotal * (1 - f(p)). */
	private final float rollEnd, rollTotal;
	private final List<Hop> hops = new ArrayList<>();

	public RouletteAnimation(Reward reward, long seed, boolean chained, float revealSeconds) {
		super(reward, seed, chained);
		PrizeDefinition win = reward.prize();
		winIdx = rnd.nextInt(POCKETS);
		fillPockets(win);

		// --- Einsatz: Schieber bringt die Chips ---
		tRakeIn = t0 - 0.05f;
		tRakeArrive = t0 + 0.6f;
		tRakeBack = t0 + 0.78f;
		tRakeGone = t0 + 1.18f;
		cue(tRakeIn + 0.05f, SoundEvents.WOOL_PLACE, 1.3f, 0.5f);
		cue(tRakeArrive, SoundEvents.NOTE_BLOCK_HAT, 1.0f, 0.5f);
		cue(tRakeArrive + 0.05f, SoundEvents.NOTE_BLOCK_HAT, 1.3f, 0.4f);
		cue(tRakeArrive + 0.1f, SoundEvents.DECORATED_POT_HIT, 2.0f, 0.35f);

		// --- Hand dreht das Rad und wirft die Kugel ein ---
		tHandIn = t0 + 0.55f;
		tSpin = t0 + 1.05f;
		tLaunch = tSpin + 0.45f;
		cue(tSpin, SoundEvents.GRINDSTONE_USE, 1.3f, 0.5f);
		cue(tLaunch, SoundEvents.PLAYER_ATTACK_SWEEP, 1.8f, 0.4f);

		// --- Kugel: rollen, fallen, hüpfen, Spannung, Gewinn ---
		tDrop = tLaunch + 2.7f;
		float base = winIdx * STEP;
		float hold = rarity == Rarity.LEGENDARY ? 1.05f : rarity == Rarity.EPIC ? 0.9f : 0.65f;
		float t1 = tDrop + 0.3f, t2 = t1 + 0.22f;
		tTease = t2 + 0.2f;
		tTeaseEnd = tTease + hold;
		float t5 = tTeaseEnd + 0.3f;
		tRest = t5 + 0.14f;
		hops.add(new Hop(tDrop, t1, base + 4.2f * STEP, base + 3 * STEP, 8f, R_TRACK - 6f * K, R_BALL, true));
		hops.add(new Hop(t1, t2, base + 3 * STEP, base + 2 * STEP, 6f, R_BALL, R_BALL, false));
		hops.add(new Hop(t2, tTease, base + 2 * STEP, base + STEP, 4.5f, R_BALL, R_BALL, false));
		hops.add(new Hop(tTeaseEnd, t5, base + STEP, base, 10f, R_BALL, R_BALL, false));
		hops.add(new Hop(t5, tRest, base, base, 2.5f, R_BALL, R_BALL, false));
		for (Hop h : hops) {
			cue(h.t1, SoundEvents.STONE_BUTTON_CLICK_ON, 1.6f + rnd.nextFloat() * 0.3f, 0.45f);
			cue(h.t1, SoundEvents.NOTE_BLOCK_HAT, 1.4f, 0.3f);
		}
		cue(tTease, SoundEvents.NOTE_BLOCK_BELL, 1.2f, 0.35f);
		heartbeat(tTease + 0.1f, tTeaseEnd);
		cue(tRest, SoundEvents.STONE_BUTTON_CLICK_ON, 1.3f, 0.7f);
		cue(tRest, SoundEvents.NOTE_BLOCK_PLING, 1.5f, 0.6f);

		float win0 = tRest + 0.08f;
		tWheelStop = win0 + 0.3f;

		// --- Rad: am Ende steht das Gewinnfach vorne (Winkel 90° = zum Betrachter) ---
		theta0 = rnd.nextFloat() * 6.2832f;
		float target = (float) (Math.PI / 2) - base;
		float diff = (float) ((target - theta0) % (Math.PI * 2));
		if (diff < 0f) diff += (float) (Math.PI * 2);
		spinTotal = diff + (float) (Math.PI * 2 * 3);

		// --- Rollen: Start links (180°) auf der Bahn, Ende genau dort, wo das erste Hüpfen beginnt ---
		rollEnd = wheelAngle(tDrop) + hops.getFirst().rho0;
		float minRoll = (float) (Math.PI * 2 * 5);
		float r = (float) Math.PI - rollEnd;
		while (r < minRoll) r += (float) (Math.PI * 2);
		rollTotal = r;
		scheduleRollTicks();

		finishTimeline(win0, revealSeconds);
	}

	/** Fächer füllen: Gewinn-Fach, daneben der "Lockvogel" (wertvollster anderer Gewinn), Rest nach Gewichtung. */
	private void fillPockets(PrizeDefinition win) {
		List<PrizeDefinition> prizes = ModConfig.get().prizes;
		PrizeDefinition tease = win;
		for (PrizeDefinition p : prizes) {
			if (p != win && (tease == win || p.rarity.ordinal() > tease.rarity.ordinal())) tease = p;
		}

		List<PrizeDefinition> pool = new ArrayList<>();
		int n = prizes.size();
		if (n >= POCKETS) {
			while (pool.size() < POCKETS) pool.add(prizes.get(rnd.nextInt(n)));
		} else {
			// jeder Gewinn mindestens einmal, der Rest anteilig nach Gewichtung (größter Rest zuerst)
			int[] counts = new int[n];
			double total = 0;
			for (PrizeDefinition p : prizes) total += Math.max(1, p.testWeight);
			int rest = POCKETS - n;
			double[] frac = new double[n];
			int given = 0;
			for (int i = 0; i < n; i++) {
				double share = rest * Math.max(1, prizes.get(i).testWeight) / total;
				counts[i] = 1 + (int) Math.floor(share);
				frac[i] = share - Math.floor(share);
				given += counts[i] - 1;
			}
			while (given < rest) {
				int best = 0;
				for (int i = 1; i < n; i++) if (frac[i] > frac[best]) best = i;
				counts[best]++;
				frac[best] = -1;
				given++;
			}
			for (int i = 0; i < n; i++) for (int c = 0; c < counts[i]; c++) pool.add(prizes.get(i));
		}
		pool.remove(win);
		if (tease != win) pool.remove(tease);
		while (pool.size() > POCKETS - 2) pool.remove(pool.size() - 1);
		while (pool.size() < POCKETS - 2) pool.add(ModConfig.get().randomPrize(rnd));
		Collections.shuffle(pool, rnd);

		pockets[winIdx] = win;
		pockets[(winIdx + 1) % POCKETS] = tease;
		PrizeDefinition prev = tease;
		for (int k = 2; k < POCKETS; k++) {
			// möglichst keine zwei gleichen Fächer nebeneinander
			int pick = 0;
			PrizeDefinition next = k == POCKETS - 1 ? win : null;
			for (int i = 0; i < pool.size(); i++) {
				if (pool.get(i) != prev && pool.get(i) != next) {
					pick = i;
					break;
				}
			}
			prev = pool.remove(pick);
			pockets[(winIdx + k) % POCKETS] = prev;
		}
	}

	/** Leises Klackern, wenn die Kugel über die Rauten der Bahn rollt. */
	private void scheduleRollTicks() {
		double seg = Math.PI / 4;
		long last = (long) Math.floor(ballAngle(tLaunch) / seg);
		float lastTick = -1f;
		for (float time = tLaunch; time < tDrop; time += 1f / 240f) {
			long now = (long) Math.floor(ballAngle(time) / seg);
			if (now != last) {
				last = now;
				if (time - lastTick > 0.045f) {
					float speed = 1f - Gfx.progress(time, tLaunch, tDrop - tLaunch);
					cue(time, SoundEvents.NOTE_BLOCK_HAT, 1.9f, 0.08f + 0.1f * speed);
					lastTick = time;
				}
			}
		}
	}

	// ---- Bewegung ---------------------------------------------------------------------------------------------------

	private float wheelAngle(float time) {
		if (time <= tSpin) return theta0 - 0.12f * (tSpin - time);
		float u = 1f - Gfx.progress(time, tSpin, tWheelStop - tSpin);
		return theta0 + spinTotal * (1f - u * u * u);
	}

	private float wheelSpeed(float time) {
		return Math.abs(wheelAngle(time + 0.01f) - wheelAngle(time - 0.01f)) / 0.02f;
	}

	/** Absoluter Winkel der Kugel beim Rollen auf der Bahn (gegen die Drehrichtung des Rads). */
	private float ballAngle(float time) {
		float p = Gfx.progress(time, tLaunch, tDrop - tLaunch);
		float u = 1f - p;
		float f = 0.85f * (1f - u * u) + 0.15f * p;
		return rollEnd + rollTotal * (1f - f);
	}

	/** Kugel: {absoluter Winkel, Radius, Höhe über dem Rad}. */
	private float[] ball(float time) {
		if (time < tDrop) {
			float r = R_TRACK - 6f * K * Gfx.smooth(Gfx.progress(time, tDrop - 0.5f, 0.5f));
			return new float[]{ballAngle(time), r, 0f};
		}
		float wheel = wheelAngle(time);
		for (Hop h : hops) {
			if (time < h.t1) {
				if (time < h.t0) break; // zwischen zwei Sprüngen
				float p = Gfx.progress(time, h.t0, h.t1 - h.t0);
				float e = h.easeOut ? Gfx.easeOutCubic(p) : Gfx.smooth(p);
				float rho = Gfx.lerp(h.rho0, h.rho1, e);
				float r = Gfx.lerp(h.r0, h.r1, e) + (h.easeOut ? 0f : 3f * (float) Math.sin(p * Math.PI));
				return new float[]{wheel + rho, r, h.height * (float) Math.sin(p * Math.PI)};
			}
		}
		if (time >= tTease && time < tTeaseEnd) {
			// liegt im Lockvogel-Fach und wackelt
			float wob = 0.05f * (float) Math.sin((time - tTease) * 26f) * (1f - Gfx.progress(time, tTease, 0.5f) * 0.6f);
			return new float[]{wheel + hops.get(2).rho1 + wob, R_BALL, 0f};
		}
		return new float[]{wheel + hops.getLast().rho1, R_BALL, 0f};
	}

	private float pocketAngle(int i, float wheel) {
		return wheel + i * STEP;
	}

	private float px(float angle, float r) {
		return WCX + r * (float) Math.cos(angle);
	}

	private float py(float angle, float r) {
		return WCY + SQ * r * (float) Math.sin(angle);
	}

	// ---- Kamera -----------------------------------------------------------------------------------------------------

	@Override
	protected float focusX() {
		return WCX;
	}

	@Override
	protected float focusY() {
		return WCY + SQ * R_ICON + 2f;
	}

	@Override
	protected float focusZoom() {
		return 1.8f;
	}

	@Override
	protected float sceneShake() {
		float amount = 0f;
		for (Hop h : hops) {
			float dt = t - h.t1;
			if (dt >= 0f && dt < 0.12f) amount = Math.max(amount, 1.1f * (1f - dt / 0.12f));
		}
		float dt = t - tRakeArrive;
		if (dt >= 0f && dt < 0.12f) amount = Math.max(amount, 0.8f * (1f - dt / 0.12f));
		if (t >= tTease && t <= tTeaseEnd) amount = Math.max(amount, 0.5f);
		return amount;
	}

	// ---- Zeichnen ---------------------------------------------------------------------------------------------------

	@Override
	protected void drawScene(GuiGraphicsExtractor g) {
		Gfx.tex(g, T_BG, 0, 0, VW, VH, Gfx.argb(0xFFFFFFFF, 1f));
		drawAwningBulbs(g);
		for (int i = 0; i < 2; i++) {
			float flicker = 0.55f + 0.08f * (float) Math.sin(t * 13f + i * 2f);
			Gfx.texCentered(g, T_GLOW, i == 0 ? 150 : 490, 82, 60, 30, 0f, Gfx.argb(0xFFFFE6A0, flicker));
		}
		Gfx.centeredText(g, "EINSATZ", CHIP_X, CHIP_Y + 25f, 0.8f, 0xFFE8C870, 0.85f);

		Gfx.tex(g, T_BOWL, WCX - 152f * K, WCY - 87f * K, 304f * K, 196f * K, Gfx.argb(0xFFFFFFFF, 1f));
		float wheel = wheelAngle(t);
		drawWheel(g, wheel);
		if (t >= tWin) drawWinnerPocket(g, wheel);
		if (t >= tTease && t < tTeaseEnd) drawTeaseGlow(g, wheel);
		drawBall(g);
		drawChips(g);
		drawRake(g);
		drawCroupierHand(g, wheel);
		drawSign(g);
	}

	private void drawWheel(GuiGraphicsExtractor g, float wheel) {
		float blur = Gfx.clamp01((wheelSpeed(t) - 4f) / 8f);
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(WCX, WCY);
		p.scale(1f, SQ);
		p.rotate(wheel);
		Gfx.texCentered(g, T_WHEEL, 0, 0, WHEEL_R * 2, WHEEL_R * 2, 0f, Gfx.argb(0xFFFFFFFF, 1f));
		for (int i = 0; i < POCKETS; i++) {
			int tint = Gfx.lerpColor(pockets[i].rarity.color, 0xFFFFFFFF, 0.15f);
			Gfx.texCentered(g, T_POCKET, 0, 0, WHEEL_R * 2, WHEEL_R * 2, i * STEP, Gfx.argb(tint, 1f));
		}
		for (int i = 0; i < POCKETS; i++) {
			p.pushMatrix();
			p.rotate(i * STEP);
			p.translate(R_ICON, 0f);
			p.rotate((float) (-Math.PI / 2));
			p.scale(1f, 1.3f);
			PrizeIcon.of(pockets[i]).draw(g, 0f, 0f, ICON, ICON, 0f, Gfx.argb(0xFFFFFFFF, 1f - 0.5f * blur));
			p.popMatrix();
		}
		Gfx.texCentered(g, T_FRETS, 0, 0, WHEEL_R * 2, WHEEL_R * 2, 0f, Gfx.argb(0xFFFFFFFF, 1f));
		Gfx.texCentered(g, T_TURRET, 0, 0, 56 * K, 56 * K, 0f, Gfx.argb(0xFFFFFFFF, 1f));
		p.popMatrix();
		// Knauf auf dem Griff (steht nach oben, wird nicht gestaucht)
		Gfx.texCentered(g, T_DISC, WCX, WCY - 3f, 14f, 14f, 0f, Gfx.argb(OUTLINE, 1f));
		Gfx.texCentered(g, T_DISC, WCX, WCY - 3f, 10.5f, 10.5f, 0f, Gfx.argb(GOLD, 1f));
		Gfx.texCentered(g, T_DISC, WCX - 1.8f, WCY - 4.8f, 4f, 4f, 0f, Gfx.argb(0xFFFFF4C0, 1f));
	}

	private void drawWinnerPocket(GuiGraphicsExtractor g, float wheel) {
		float a = pocketAngle(winIdx, wheel);
		float x = px(a, R_ICON), y = py(a, R_ICON);
		float hp = Gfx.progress(t, tWin, 0.4f);
		float pulse = 1f + (float) Math.sin(t * 7f) * 0.08f;
		Gfx.texCentered(g, T_RAYS, x, y, 160 * pulse * hp, 160 * pulse * hp, t * 0.8f, Gfx.argb(rarity.color, 0.5f * hp));
		Gfx.texCentered(g, T_GLOW, x, y, 115 * pulse, 90 * pulse, 0f, Gfx.argb(rarity.color, 0.9f * hp));
		float bob = (float) Math.sin((t - tWin) * 5f) * 2f * hp;
		float size = ICON * (1f + 0.35f * Gfx.easeOutBack(hp)) * pulse;
		PrizeIcon.of(pockets[winIdx]).draw(g, x, y - 4f * hp + bob, size, size * Gfx.lerp(SQ * 1.3f, 1f, hp), 0f, Gfx.argb(0xFFFFFFFF, 1f));
		drawSparkles(g, x, y, 30f, 6, t - tWin);
	}

	private void drawTeaseGlow(GuiGraphicsExtractor g, float wheel) {
		int tease = (winIdx + 1) % POCKETS;
		float a = pocketAngle(tease, wheel);
		float f = Gfx.progress(t, tTease, 0.15f) * (1f - Gfx.progress(t, tTeaseEnd - 0.1f, 0.1f));
		float pulse = 0.6f + 0.4f * (float) Math.sin((t - tTease) * 16f);
		Gfx.texCentered(g, T_GLOW, px(a, R_ICON), py(a, R_ICON), 80, 58, 0f, Gfx.argb(pockets[tease].rarity.color, 0.7f * f * pulse));
	}

	private void drawBall(GuiGraphicsExtractor g) {
		if (t < tSpin + 0.2f) return;
		if (t < tLaunch) {
			// liegt in der Hand
			float[] h = handPos(t, wheelAngle(t));
			Gfx.texCentered(g, T_BALL, h[0] - 2f, h[1] - 2f, BALL, BALL, 0f, Gfx.argb(0xFFFFFFFF, 1f));
			return;
		}
		float[] b = ball(t);
		float x = px(b[0], b[1]), y = py(b[0], b[1]);
		if (t < tDrop) {
			// Bewegungsunschärfe beim schnellen Rollen
			float speed = Math.abs(ballAngle(t + 0.01f) - ballAngle(t - 0.01f)) / 0.02f;
			float trail = Gfx.clamp01((speed - 5f) / 15f);
			for (int k = 1; k <= 3 && trail > 0.05f; k++) {
				float a = ballAngle(t - k * 0.012f);
				Gfx.texCentered(g, T_BALL, px(a, b[1]), py(a, b[1]), BALL, BALL, 0f, Gfx.argb(0xFFFFFFFF, 0.35f * trail / k));
			}
		}
		if (b[2] > 0.3f) {
			Gfx.texCentered(g, T_DISC, x, y + 1.5f, BALL * 0.9f, BALL * 0.45f, 0f, Gfx.argb(0xFF000000, 0.35f));
		}
		Gfx.texCentered(g, T_BALL, x, y - b[2], BALL, BALL, 0f, Gfx.argb(0xFFFFFFFF, 1f));
	}

	private float rakeHeadX(float time) {
		if (time < tRakeArrive) return Gfx.lerp(VW + 60f, CHIP_X + 22f, Gfx.easeOutCubic(Gfx.progress(time, tRakeIn, tRakeArrive - tRakeIn)));
		if (time < tRakeBack) return CHIP_X + 22f;
		return Gfx.lerp(CHIP_X + 22f, VW + 120f, Gfx.easeInCubic(Gfx.progress(time, tRakeBack, tRakeGone - tRakeBack)));
	}

	private void drawChips(GuiGraphicsExtractor g) {
		float x = Math.min(rakeHeadX(Math.min(t, tRakeArrive)) - 22f, VW + 40f);
		if (x > VW + 20f) return;
		float since = t - tRakeArrive;
		for (int i = 0; i < CHIP_COLORS.length; i++) {
			float wob = since > 0f && since < 0.4f ? (float) Math.sin(since * 30f + i) * i * 0.5f * (1f - since / 0.4f) : 0f;
			Gfx.tex(g, T_CHIP, x - 18f + wob, CHIP_Y - 11f - i * 3.3f, 36f, 26f, Gfx.argb(CHIP_COLORS[i], 1f));
		}
		if (t >= tWin) {
			float hp = Gfx.progress(t, tWin, 0.3f);
			Gfx.texCentered(g, T_GLOW, x, CHIP_Y - 8f, 70, 44, 0f, Gfx.argb(GOLD, 0.45f * hp));
		}
	}

	private void drawRake(GuiGraphicsExtractor g) {
		if (t < tRakeIn || t > tRakeGone) return;
		float hx = rakeHeadX(t);
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(hx, CHIP_Y + 2f);
		p.rotate(-0.05f);
		Gfx.tex(g, T_RAKE, -10f, -30f, 330f, 60f, Gfx.argb(0xFFFFFFFF, 1f));
		p.popMatrix();
	}

	/** Greifpunkt der Hand und ihr Winkel. */
	private float[] handPos(float time, float wheel) {
		float turretX = WCX, turretY = WCY - 2f;
		float launchX = px((float) Math.PI, R_TRACK) + 4f, launchY = WCY - 2f;
		if (time < tSpin) {
			float in = Gfx.easeOutCubic(Gfx.progress(time, tHandIn, tSpin - 0.08f - tHandIn));
			return new float[]{Gfx.lerp(-120f, turretX, in), Gfx.lerp(60f, turretY, in), 0.5f};
		}
		if (time < tSpin + 0.2f) {
			float twist = Math.min(0.7f, wheel - wheelAngle(tSpin));
			return new float[]{turretX, turretY, 0.5f + twist};
		}
		if (time < tLaunch) {
			float m = Gfx.easeInOutCubic(Gfx.progress(time, tSpin + 0.2f, tLaunch - tSpin - 0.2f));
			return new float[]{Gfx.lerp(turretX, launchX, m), Gfx.lerp(turretY, launchY, m) - (float) Math.sin(m * Math.PI) * 14f, Gfx.lerp(1.2f, 0.3f, m)};
		}
		float out = Gfx.easeInCubic(Gfx.progress(time, tLaunch, 0.45f));
		float flick = (float) Math.sin(Gfx.progress(time, tLaunch, 0.15f) * Math.PI) * 0.35f;
		return new float[]{Gfx.lerp(launchX, -140f, out), Gfx.lerp(launchY, 110f, out), 0.3f + flick + out * 0.3f};
	}

	private void drawCroupierHand(GuiGraphicsExtractor g, float wheel) {
		if (t < tHandIn || t > tLaunch + 0.45f) return;
		float[] h = handPos(t, wheel);
		drawHand(g, h[0], h[1], h[2], 1f, true);
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** Ein Sprung der Kugel relativ zum Rad: von Fach zu Fach (Winkel rho), mit Höhe und Radius. */
	private record Hop(float t0, float t1, float rho0, float rho1, float height, float r0, float r1, boolean easeOut) {
	}
}
