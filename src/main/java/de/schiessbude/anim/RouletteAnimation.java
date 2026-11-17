package de.schiessbude.anim;

import de.schiessbude.config.ModConfig;
import de.schiessbude.config.PrizeDefinition;
import de.schiessbude.config.Rarity;
import de.schiessbude.config.Reward;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Die Roulette-Animation (im Erz-Test: Gold, Eisen, Redstone, Diamant, Lapis).
 * <p>
 * Ablauf: Neben dem Rad liegt ein Einsatzfeld mit einem Feld pro Gewinn. Eine Hand setzt Chip-Stapel auf mehrere
 * Felder, greift dann den Griff in der Radmitte, dreht das Rad an und wirft die Kugel ein. Die Kugel kreist auf der
 * Bahn, fällt in die Fächer, hüpft über ein paar Stege, bleibt zur Spannung kurz in einem wertvolleren Fach liegen
 * (dessen Feld blinkt) und springt dann in das Fach mit deinem Gewinn. Das Rad läuft aus, bis dein Fach vorne steht,
 * dein Feld leuchtet auf, und die Kamera zoomt auf Fach und Feld.
 * <p>
 * Das Rad wird von schräg oben gezeigt: gezeichnet wird es flach und dann senkrecht auf {@link #SQ} gestaucht.
 * Das Einsatzfeld liegt schräg auf dem Tisch (Scherung {@link #SHEAR}).
 */
public final class RouletteAnimation extends CasinoAnimation {
	// ---- Layout in virtuellen Einheiten (passt zu tools/generate_textures.py) ----
	static final float WCX = 196f, WCY = 200f, SQ = 0.56f;
	/** Vergrößerung des Rads gegenüber den Grafik-Maßen (Schüssel 304 x 196, Rad 232 x 232). */
	static final float K = 1.0f;
	static final float WHEEL_R = 116f * K, R_TRACK = 127f * K, R_ICON = 94f * K, R_BALL = 106f * K;
	static final int POCKETS = 12;
	static final float STEP = (float) (Math.PI * 2 / POCKETS);
	static final float BALL = 10f * K, ICON = 23f * K;
	/** Einsatzfeld: obere Mitte, Breite, Neigung, Rand. */
	static final float BX = 452f, BY = 124f, BW = 186f, SHEAR = 0.24f, PAD = 6f;
	/** Chip-Stapel liegen rechts im Feld. */
	static final float STACK_LX = BW / 2f - 28f;
	static final int[] CHIP_COLORS = {0xFFFFC93C, 0xFFB060FF, 0xFFE63A4A, 0xFF3FA7FF, 0xFF4CDB5A};

	static final Identifier T_BG = Gfx.id("textures/gui/roulette_background.png");
	static final Identifier T_BOWL = Gfx.id("textures/gui/roulette_bowl.png");
	static final Identifier T_WHEEL = Gfx.id("textures/gui/roulette_wheel.png");
	static final Identifier T_POCKET = Gfx.id("textures/gui/roulette_pocket.png");
	static final Identifier T_FRETS = Gfx.id("textures/gui/roulette_frets.png");
	static final Identifier T_TURRET = Gfx.id("textures/gui/roulette_turret.png");
	static final Identifier T_BALL = Gfx.id("textures/gui/ball.png");
	static final Identifier T_CHIP = Gfx.id("textures/gui/chip.png");

	private final PrizeDefinition[] pockets = new PrizeDefinition[POCKETS];
	private final int winIdx;

	/** Felder des Einsatzfelds (eins pro Gewinn) und die gesetzten Stapel. */
	private final List<PrizeDefinition> fields;
	private final float fieldH;
	private final int winField, teaseField;
	private final List<Bet> bets = new ArrayList<>();

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

		// --- Einsatzfeld: ein Feld pro Gewinn; gesetzt wird auf den Gewinn, den Lockvogel und ein weiteres Feld ---
		fields = new ArrayList<>(ModConfig.get().prizes);
		fieldH = Math.min(34f, 172f / fields.size());
		winField = fieldOf(win);
		teaseField = fieldOf(pockets[(winIdx + 1) % POCKETS]);
		List<Integer> chosen = new ArrayList<>();
		chosen.add(winField);
		if (!chosen.contains(teaseField)) chosen.add(teaseField);
		List<Integer> others = new ArrayList<>();
		for (int i = 0; i < fields.size(); i++) if (!chosen.contains(i)) others.add(i);
		Collections.shuffle(others, rnd);
		if (!others.isEmpty()) chosen.add(others.getFirst());
		Collections.sort(chosen);

		// --- Die Hand setzt die Chips (von hinten nach vorne) ---
		tHandIn = t0 - 0.05f;
		List<Integer> colors = new ArrayList<>();
		for (int c : CHIP_COLORS) colors.add(c);
		Collections.shuffle(colors, rnd);
		for (int i = 0; i < chosen.size(); i++) {
			float at = t0 + 0.3f + i * 0.24f;
			bets.add(new Bet(chosen.get(i), at, colors.get(i % colors.size()), 2 + rnd.nextInt(3)));
			cue(at, SoundEvents.NOTE_BLOCK_HAT, 1.1f + 0.1f * i, 0.5f);
			cue(at + 0.03f, SoundEvents.DECORATED_POT_HIT, 1.9f, 0.35f);
		}

		// --- Hand dreht das Rad und wirft die Kugel ein ---
		tSpin = bets.getLast().at + 0.42f;
		tLaunch = tSpin + 0.45f;
		cue(tSpin, SoundEvents.GRINDSTONE_USE, 1.3f, 0.5f);
		cue(tLaunch, SoundEvents.PLAYER_ATTACK_SWEEP, 1.8f, 0.4f);

		// --- Kugel: rollen, fallen, hüpfen, Spannung, Gewinn ---
		tDrop = tLaunch + 2.5f;
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
		// Gewinnfeld leuchtet auf, Chips klimpern
		for (int i = 0; i < 4; i++) cue(win0 + 0.06f + i * 0.06f, SoundEvents.NOTE_BLOCK_HAT, 1.6f + i * 0.1f, 0.35f);

		// --- Rad: am Ende steht das Gewinnfach vorne (Winkel 90° = zum Betrachter) ---
		theta0 = rnd.nextFloat() * 6.2832f;
		float target = (float) (Math.PI / 2) - base;
		float diff = (float) ((target - theta0) % (Math.PI * 2));
		if (diff < 0f) diff += (float) (Math.PI * 2);
		spinTotal = diff + (float) (Math.PI * 2 * 3);

		// --- Rollen: Start rechts (0°) auf der Bahn, Ende genau dort, wo das erste Hüpfen beginnt ---
		rollEnd = wheelAngle(tDrop) + hops.getFirst().rho0;
		float minRoll = (float) (Math.PI * 2 * 5);
		float r = -rollEnd;
		while (r < minRoll) r += (float) (Math.PI * 2);
		rollTotal = r;
		scheduleRollTicks();

		finishTimeline(win0, revealSeconds);
	}

	private int fieldOf(PrizeDefinition p) {
		int i = fields.indexOf(p);
		return Math.max(0, i);
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

	// ---- Einsatzfeld-Geometrie ----------------------------------------------------------------------------------------

	private float boardH() {
		return fields.size() * fieldH + 2f * PAD;
	}

	/** Mitte eines Felds in Brett-Koordinaten (y). */
	private float fieldCY(int i) {
		return PAD + (i + 0.5f) * fieldH;
	}

	/** Brett-Koordinaten (lx, ly) -> Szene. */
	private static float boardX(float lx, float ly) {
		return BX + lx + SHEAR * ly;
	}

	private static float boardY(float ly) {
		return BY + ly;
	}

	// ---- Kamera -----------------------------------------------------------------------------------------------------

	/** Am Ende zeigt die Kamera Gewinnfach (vorne am Rad) und Gewinnfeld zusammen. */
	@Override
	protected float focusX() {
		float ly = fieldCY(winField);
		return (WCX + boardX(0f, ly)) / 2f;
	}

	@Override
	protected float focusY() {
		float ly = fieldCY(winField);
		return (WCY + SQ * R_ICON + boardY(ly)) / 2f;
	}

	@Override
	protected float focusZoom() {
		float ly = fieldCY(winField);
		float dx = Math.abs(boardX(0f, ly) - WCX);
		float dy = Math.abs(boardY(ly) - (WCY + SQ * R_ICON));
		return Math.min(1.6f, Math.min(VW / (dx + 230f), VH / (dy + 130f)));
	}

	@Override
	protected float sceneShake() {
		float amount = 0f;
		for (Hop h : hops) {
			float dt = t - h.t1;
			if (dt >= 0f && dt < 0.12f) amount = Math.max(amount, 1.1f * (1f - dt / 0.12f));
		}
		for (Bet b : bets) {
			float dt = t - b.at;
			if (dt >= 0f && dt < 0.1f) amount = Math.max(amount, 0.6f * (1f - dt / 0.1f));
		}
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

		drawBoard(g);
		drawChipStacks(g);

		Gfx.tex(g, T_BOWL, WCX - 152f * K, WCY - 87f * K, 304f * K, 196f * K, Gfx.argb(0xFFFFFFFF, 1f));
		float wheel = wheelAngle(t);
		drawWheel(g, wheel);
		if (t >= tWin) drawWinnerPocket(g, wheel);
		if (t >= tTease && t < tTeaseEnd) drawTeaseGlow(g, wheel);
		drawBall(g);
		drawCroupierHand(g);
		drawSign(g);
	}

	/** Das Einsatzfeld: schräg auf dem Tisch liegendes Brett mit einem Feld pro Gewinn. */
	private void drawBoard(GuiGraphicsExtractor g) {
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(BX, BY);
		p.mul(new Matrix3x2f(1f, 0f, SHEAR, 1f, 0f, 0f));
		float h = boardH();
		float x0 = -BW / 2f;
		Gfx.rect(g, x0 + 4f, 5f, BW, h, Gfx.argb(0xFF06261A, 0.45f));
		Gfx.rect(g, x0 - 4f, -4f, BW + 8f, h + 8f, Gfx.argb(OUTLINE, 1f));
		Gfx.rect(g, x0 - 2.5f, -2.5f, BW + 5f, h + 5f, Gfx.argb(GOLD, 1f));
		Gfx.rect(g, x0, 0f, BW, h, Gfx.argb(0xFF14563C, 1f));
		Gfx.outlinedText(g, "EINSATZ", 0f, -12f, 0.9f, GOLD, OUTLINE, 1f);

		boolean won = t >= tWin;
		boolean teasing = t >= tTease && t < tTeaseEnd;
		for (int i = 0; i < fields.size(); i++) {
			PrizeDefinition prize = fields.get(i);
			float top = PAD + i * fieldH;
			float cy = top + fieldH / 2f;
			int col = prize.rarity.color;
			float light = 0.32f;
			if (won && i == winField) {
				light = Gfx.lerp(0.32f, 0.8f + 0.12f * (float) Math.sin((t - tWin) * 10f), Gfx.progress(t, tWin, 0.25f));
			} else if (teasing && i == teaseField) {
				light = 0.4f + 0.45f * (0.5f + 0.5f * (float) Math.sin((t - tTease) * 16f));
			} else if (won) {
				light = Gfx.lerp(0.32f, 0.18f, Gfx.progress(t, tWin, 0.3f));
			}
			Gfx.rect(g, x0 + PAD, top + 1f, BW - 2f * PAD, fieldH - 2f, Gfx.argb(Gfx.lerpColor(0xFF1E5A40, col, light), 1f));
			if (i > 0) Gfx.rect(g, x0 + PAD, top - 0.6f, BW - 2f * PAD, 1.2f, Gfx.argb(0xFFE8C870, 0.9f));
			float icon = Math.min(26f, fieldH * 0.8f);
			PrizeIcon.of(prize).draw(g, x0 + PAD + 4f + icon / 2f, cy, icon, icon * 0.82f, 0f, Gfx.argb(0xFFFFFFFF, 1f));
			String name = prize.name.toUpperCase(Locale.GERMAN);
			float scale = Math.min(Math.min(1f, fieldH / 24f), 84f / Math.max(1, Gfx.textWidth(name)));
			Gfx.outlinedText(g, name, -6f, cy, scale, 0xFFFFFFFF, OUTLINE, 1f);
			// Markierung für den Stapel
			Gfx.rect(g, STACK_LX - 13f, cy - 0.5f, 26f, 1f, Gfx.argb(0xFFE8C870, 0.35f));
		}
		// Rahmen um das Gewinnfeld
		if (won) {
			float hp = Gfx.progress(t, tWin, 0.25f);
			float top = PAD + winField * fieldH;
			int edge = Gfx.argb(Gfx.lerpColor(rarity.color, 0xFFFFFFFF, 0.5f), hp);
			Gfx.rect(g, x0 + PAD - 1f, top - 0.5f, BW - 2f * PAD + 2f, 2f, edge);
			Gfx.rect(g, x0 + PAD - 1f, top + fieldH - 1.5f, BW - 2f * PAD + 2f, 2f, edge);
			Gfx.rect(g, x0 + PAD - 1f, top - 0.5f, 2f, fieldH + 1f, edge);
			Gfx.rect(g, BW / 2f - PAD - 1f, top - 0.5f, 2f, fieldH + 1f, edge);
		}
		p.popMatrix();

		if (t >= tTease && t < tTeaseEnd && teaseField != winField) {
			float ly = fieldCY(teaseField);
			float f = Gfx.progress(t, tTease, 0.15f) * (1f - Gfx.progress(t, tTeaseEnd - 0.1f, 0.1f));
			float pulse = 0.6f + 0.4f * (float) Math.sin((t - tTease) * 16f);
			Gfx.texCentered(g, T_GLOW, boardX(0f, ly), boardY(ly), BW * 1.4f, fieldH * 2.8f, 0f,
					Gfx.argb(fields.get(teaseField).rarity.color, 0.5f * f * pulse));
		}
		if (won) {
			float ly = fieldCY(winField);
			float hp = Gfx.progress(t, tWin, 0.3f);
			float pulse = 1f + 0.08f * (float) Math.sin(t * 7f);
			Gfx.texCentered(g, T_GLOW, boardX(0f, ly), boardY(ly), BW * 1.5f * pulse, fieldH * 3.2f * pulse, 0f, Gfx.argb(rarity.color, 0.55f * hp));
			drawSparkles(g, boardX(-30f, ly), boardY(ly), 40f, 6, t - tWin);
		}
	}

	private void drawChipStacks(GuiGraphicsExtractor g) {
		boolean won = t >= tWin;
		for (Bet b : bets) {
			if (t < b.at) continue;
			float ly = fieldCY(b.field);
			float x = boardX(STACK_LX, ly), y = boardY(ly);
			float drop = 1f - Gfx.easeOutBack(Gfx.progress(t, b.at, 0.16f));
			float a = 1f;
			float jump = 0f;
			if (won) {
				if (b.field == winField) {
					float since = t - tWin;
					jump = since < 0.5f ? (float) Math.abs(Math.sin(since * 12f)) * 4f * (1f - since / 0.5f) : 0f;
					Gfx.texCentered(g, T_GLOW, x, y - 6f, 50, 36, 0f, Gfx.argb(GOLD, 0.6f * Gfx.progress(t, tWin, 0.25f)));
				} else {
					a = 1f - 0.45f * Gfx.progress(t, tWin, 0.3f);
				}
			}
			drawStack(g, x, y - drop * 10f - jump, b.color, b.chips, a);
		}
	}

	/** Chip-Stapel; (x, y) = Mitte des untersten Chips. */
	private static void drawStack(GuiGraphicsExtractor g, float x, float y, int color, int chips, float a) {
		for (int j = 0; j < chips; j++) {
			int col = j == chips - 1 ? Gfx.lerpColor(color, 0xFFFFFFFF, 0.15f) : color;
			Gfx.tex(g, T_CHIP, x - 14.4f, y - 8.8f - j * 2.7f, 28.8f, 20.8f, Gfx.argb(col, a));
		}
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
		Gfx.texCentered(g, T_RAYS, x, y, 150 * pulse * hp, 150 * pulse * hp, t * 0.8f, Gfx.argb(rarity.color, 0.5f * hp));
		Gfx.texCentered(g, T_GLOW, x, y, 105 * pulse, 82 * pulse, 0f, Gfx.argb(rarity.color, 0.9f * hp));
		float bob = (float) Math.sin((t - tWin) * 5f) * 2f * hp;
		float size = ICON * (1f + 0.4f * Gfx.easeOutBack(hp)) * pulse;
		PrizeIcon.of(pockets[winIdx]).draw(g, x, y - 4f * hp + bob, size, size * Gfx.lerp(SQ * 1.3f, 1f, hp), 0f, Gfx.argb(0xFFFFFFFF, 1f));
		drawSparkles(g, x, y, 28f, 6, t - tWin);
	}

	private void drawTeaseGlow(GuiGraphicsExtractor g, float wheel) {
		int tease = (winIdx + 1) % POCKETS;
		float a = pocketAngle(tease, wheel);
		float f = Gfx.progress(t, tTease, 0.15f) * (1f - Gfx.progress(t, tTeaseEnd - 0.1f, 0.1f));
		float pulse = 0.6f + 0.4f * (float) Math.sin((t - tTease) * 16f);
		Gfx.texCentered(g, T_GLOW, px(a, R_ICON), py(a, R_ICON), 80, 58, 0f, Gfx.argb(pockets[tease].rarity.color, 0.85f * f * pulse));
	}

	private void drawBall(GuiGraphicsExtractor g) {
		if (t < tSpin + 0.2f) return;
		if (t < tLaunch) {
			// liegt in der Hand
			float[] h = handPos(t);
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

	// ---- Hand -------------------------------------------------------------------------------------------------------

	/** Wo die Hand einen Stapel absetzt (Greifpunkt über dem Stapel). */
	private float[] betSpot(Bet b) {
		float ly = fieldCY(b.field);
		return new float[]{boardX(STACK_LX, ly) + 2f, boardY(ly) - 12f};
	}

	/** Greifpunkt der Hand und ihr Winkel (Ärmel zeigt nach rechts unten). */
	private float[] handPos(float time) {
		float turretX = WCX + 2f, turretY = WCY - 2f;
		float launchX = px(0f, R_TRACK) - 4f, launchY = WCY - 2f;
		Bet first = bets.getFirst();
		if (time < first.at) {
			float[] s = betSpot(first);
			float in = Gfx.easeOutCubic(Gfx.progress(time, tHandIn, first.at - 0.04f - tHandIn));
			return new float[]{Gfx.lerp(VW + 170f, s[0], in), Gfx.lerp(s[1] + 80f, s[1], in) + dip(time), 0.35f};
		}
		for (int i = 0; i < bets.size() - 1; i++) {
			Bet b = bets.get(i), n = bets.get(i + 1);
			if (time < n.at) {
				float[] s0 = betSpot(b), s1 = betSpot(n);
				float m = Gfx.easeInOutCubic(Gfx.progress(time, b.at + 0.06f, n.at - 0.04f - b.at - 0.06f));
				return new float[]{Gfx.lerp(s0[0], s1[0], m), Gfx.lerp(s0[1], s1[1], m) - (float) Math.sin(m * Math.PI) * 6f + dip(time), 0.35f};
			}
		}
		Bet last = bets.getLast();
		if (time < tSpin) {
			float[] s = betSpot(last);
			float m = Gfx.easeInOutCubic(Gfx.progress(time, last.at + 0.06f, tSpin - 0.06f - last.at - 0.06f));
			return new float[]{Gfx.lerp(s[0], turretX, m), Gfx.lerp(s[1], turretY, m) - (float) Math.sin(m * Math.PI) * 18f + dip(time),
					Gfx.lerp(0.35f, 0.5f, m)};
		}
		if (time < tSpin + 0.2f) {
			float twist = Math.min(0.7f, wheelAngle(time) - wheelAngle(tSpin));
			return new float[]{turretX, turretY, 0.5f + twist};
		}
		if (time < tLaunch) {
			float m = Gfx.easeInOutCubic(Gfx.progress(time, tSpin + 0.2f, tLaunch - tSpin - 0.2f));
			return new float[]{Gfx.lerp(turretX, launchX, m), Gfx.lerp(turretY, launchY, m) - (float) Math.sin(m * Math.PI) * 14f, Gfx.lerp(1.2f, 0.25f, m)};
		}
		float out = Gfx.easeInCubic(Gfx.progress(time, tLaunch, 0.45f));
		float flick = (float) Math.sin(Gfx.progress(time, tLaunch, 0.15f) * Math.PI) * 0.35f;
		return new float[]{Gfx.lerp(launchX, VW + 160f, out), Gfx.lerp(launchY, 300f, out), 0.25f - flick + out * 0.3f};
	}

	/** Kurzes Absenken der Hand beim Absetzen eines Stapels. */
	private float dip(float time) {
		float d = 0f;
		for (Bet b : bets) {
			float p = (time - (b.at - 0.06f)) / 0.14f;
			if (p > 0f && p < 1f) d += 6f * (float) Math.sin(p * Math.PI);
		}
		return d;
	}

	private void drawCroupierHand(GuiGraphicsExtractor g) {
		if (t < tHandIn || t > tLaunch + 0.45f) return;
		float[] h = handPos(t);
		// Stapel, den die Hand gerade trägt
		for (Bet b : bets) {
			if (t < b.at) {
				drawStack(g, h[0] - 1f, h[1] + 12f, b.color, b.chips, 1f);
				break;
			}
		}
		drawHand(g, h[0], h[1], h[2], 1f, false);
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** Ein gesetzter Stapel: Feld, Zeitpunkt, Farbe, Anzahl Chips. */
	private record Bet(int field, float at, int color, int chips) {
	}

	/** Ein Sprung der Kugel relativ zum Rad: von Fach zu Fach (Winkel rho), mit Höhe und Radius. */
	private record Hop(float t0, float t1, float rho0, float rho1, float height, float r0, float r1, boolean easeOut) {
	}
}
