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
 * Der Tisch wird von oben gezeigt wie ein echter Roulette-Tisch: links das Rad, rechts das Wettfeld mit einem
 * Feld pro Gewinn (abwechselnd rot und schwarz), darunter die Chip-Reihe.
 * <p>
 * Ablauf: Eine Hand nimmt Chips aus der Reihe und setzt Stapel auf mehrere Felder, greift dann den Griff in der
 * Radmitte, dreht das Rad an und wirft die Kugel ein. Die Kugel kreist auf der
 * Bahn, fällt in die Fächer, hüpft über ein paar Stege, bleibt zur Spannung kurz in einem wertvolleren Fach liegen
 * (dessen Feld blinkt) und springt dann in das Fach mit deinem Gewinn. Das Rad läuft aus, bis dein Fach vorne steht,
 * dein Feld leuchtet auf, und die Kamera zoomt auf Fach und Feld.
 */
public final class RouletteAnimation extends CasinoAnimation {
	// ---- Layout in virtuellen Einheiten (passt zu tools/generate_textures.py) ----
	static final float WCX = 160f, WCY = 210f, SQ = 1f;
	/** Vergrößerung des Rads gegenüber den Grafik-Maßen (Kessel 300 x 300, Rad 232 x 232). */
	static final float K = 0.82f;
	static final float WHEEL_R = 116f * K, R_TRACK = 127f * K, R_ICON = 94f * K, R_BALL = 106f * K;
	static final int POCKETS = 12;
	static final float STEP = (float) (Math.PI * 2 / POCKETS);
	static final float BALL = 9f, ICON = 23f * K;
	/** Wettfeld: linke obere Ecke der Felder, Höhe; die Breite eines Felds hängt von der Anzahl der Gewinne ab. */
	static final float GX = 350f, GY = 112f, CH = 150f;
	/** Chip-Reihe unter dem Wettfeld. */
	static final float RACK_Y = 300f;
	static final int[] CHIP_COLORS = {0xFFE63A4A, 0xFF3FA7FF, 0xFF34343C, 0xFF4CDB5A, 0xFFFFC93C, 0xFFFF8A2B, 0xFFFF6FC8, 0xFFC0306A, 0xFF7A1E2E};

	static final Identifier T_BG = Gfx.id("textures/gui/roulette_background.png");
	static final Identifier T_BOWL = Gfx.id("textures/gui/roulette_bowl.png");
	static final Identifier T_WHEEL = Gfx.id("textures/gui/roulette_wheel.png");
	static final Identifier T_POCKET = Gfx.id("textures/gui/roulette_pocket.png");
	static final Identifier T_FRETS = Gfx.id("textures/gui/roulette_frets.png");
	static final Identifier T_TURRET = Gfx.id("textures/gui/roulette_turret.png");
	static final Identifier T_BALL = Gfx.id("textures/gui/ball.png");
	static final Identifier T_CHIP = Gfx.id("textures/gui/chip.png");
	static final Identifier T_CHIP_MARKS = Gfx.id("textures/gui/chip_marks.png");

	private final PrizeDefinition[] pockets = new PrizeDefinition[POCKETS];
	private final int winIdx;
	/**
	 * Wie das Ende abläuft: +1 = Kugel bleibt erst im Fach davor liegen (Lockvogel) und hüpft dann ins Gewinnfach,
	 * -1 = sie springt erst übers Gewinnfach ins Fach dahinter und hüpft zurück, 0 = sie fällt direkt hinein.
	 */
	private final int teaseOffset;
	/** Winkel (relativ zum Rad), in dem die Kugel während der Spannungspause liegt. */
	private float teaseRho;

	/** Felder des Einsatzfelds (eins pro Gewinn) und die gesetzten Stapel. */
	private final List<PrizeDefinition> fields;
	private final float fieldW;
	private final int winField, teaseField;
	private final List<Bet> bets = new ArrayList<>();

	private final float tHandIn, tRack, tSpin, tLaunch, tDrop, tTease, tTeaseEnd, tRest, tWheelStop;
	private final float theta0, spinTotal;
	/** Kugel beim Rollen: absoluter Winkel = rollEnd + rollTotal * (1 - f(p)). */
	private final float rollEnd, rollTotal;
	private final List<Hop> hops = new ArrayList<>();

	public RouletteAnimation(Reward reward, long seed, boolean chained, float revealSeconds) {
		super(reward, seed, chained);
		PrizeDefinition win = reward.prize();
		winIdx = rnd.nextInt(POCKETS);
		float roll = rnd.nextFloat();
		teaseOffset = roll < 0.34f ? 0 : roll < 0.67f ? 1 : -1;
		fillPockets(win);
		if (teaseOffset < 0) mirrorPockets();

		// --- Einsatzfeld: ein Feld pro Gewinn; gesetzt wird auf den Gewinn, den Lockvogel und ein weiteres Feld ---
		fields = new ArrayList<>(ModConfig.get().prizes);
		fieldW = Math.min(52f, 256f / fields.size());
		winField = fieldOf(win);
		teaseField = teaseOffset == 0 ? -1 : fieldOf(pockets[Math.floorMod(winIdx + teaseOffset, POCKETS)]);
		List<Integer> chosen = new ArrayList<>();
		chosen.add(winField);
		if (teaseField >= 0 && !chosen.contains(teaseField)) chosen.add(teaseField);
		List<Integer> others = new ArrayList<>();
		for (int i = 0; i < fields.size(); i++) if (!chosen.contains(i)) others.add(i);
		Collections.shuffle(others, rnd);
		for (int i = 0; chosen.size() < 3 && i < others.size(); i++) chosen.add(others.get(i));
		Collections.sort(chosen);

		// --- Die Hand nimmt Chips aus der Reihe und setzt sie (von links nach rechts) ---
		tHandIn = t0 - 0.1f;
		tRack = t0 + 0.2f;
		cue(tRack, SoundEvents.NOTE_BLOCK_HAT, 1.3f, 0.4f);
		List<Integer> colors = new ArrayList<>();
		for (int c : CHIP_COLORS) colors.add(c);
		Collections.shuffle(colors, rnd);
		for (int i = 0; i < chosen.size(); i++) {
			float at = t0 + 0.48f + i * 0.22f;
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
		float hold = (rarity == Rarity.LEGENDARY ? 1.05f : rarity == Rarity.EPIC ? 0.9f : 0.65f) * (0.7f + 0.6f * rnd.nextFloat());
		float t1 = tDrop + 0.3f, t2 = t1 + 0.22f;
		hops.add(new Hop(tDrop, t1, base + 4.2f * STEP, base + 3 * STEP, 8f, R_TRACK - 6f * K, R_BALL, true));
		hops.add(new Hop(t1, t2, base + 3 * STEP, base + 2 * STEP, 6f, R_BALL, R_BALL, false));
		float t5;
		if (teaseOffset > 0) {
			// bleibt im Fach davor liegen ... und hüpft doch noch weiter
			tTease = t2 + 0.2f;
			hops.add(new Hop(t2, tTease, base + 2 * STEP, base + STEP, 4.5f, R_BALL, R_BALL, false));
			tTeaseEnd = tTease + hold;
			t5 = tTeaseEnd + 0.3f;
			hops.add(new Hop(tTeaseEnd, t5, base + STEP, base, 10f, R_BALL, R_BALL, false));
		} else if (teaseOffset < 0) {
			// springt übers Gewinnfach hinweg ... und kullert zurück
			float t3 = t2 + 0.2f;
			hops.add(new Hop(t2, t3, base + 2 * STEP, base + STEP, 4.5f, R_BALL, R_BALL, false));
			tTease = t3 + 0.3f;
			hops.add(new Hop(t3, tTease, base + STEP, base - STEP, 9f, R_BALL, R_BALL, false));
			tTeaseEnd = tTease + hold;
			t5 = tTeaseEnd + 0.32f;
			hops.add(new Hop(tTeaseEnd, t5, base - STEP, base, 8f, R_BALL, R_BALL, false));
		} else {
			// fällt direkt hinein, wackelt kurz ... bleibt es liegen?
			float t3 = t2 + 0.2f;
			hops.add(new Hop(t2, t3, base + 2 * STEP, base + STEP, 4.5f, R_BALL, R_BALL, false));
			tTease = t3 + 0.24f;
			hops.add(new Hop(t3, tTease, base + STEP, base, 4f, R_BALL, R_BALL, false));
			tTeaseEnd = tTease + hold * 0.6f;
			t5 = tTeaseEnd + 0.18f;
			hops.add(new Hop(tTeaseEnd, t5, base, base, 3.5f, R_BALL, R_BALL, false));
		}
		teaseRho = base + teaseOffset * STEP;
		tRest = t5 + 0.14f;
		hops.add(new Hop(t5, tRest, base, base, 2.5f, R_BALL, R_BALL, false));
		for (Hop h : hops) {
			cue(h.t1, SoundEvents.STONE_BUTTON_CLICK_ON, 1.6f + rnd.nextFloat() * 0.3f, 0.45f);
			cue(h.t1, SoundEvents.NOTE_BLOCK_HAT, 1.4f, 0.3f);
		}
		if (teaseOffset != 0) cue(tTease, SoundEvents.NOTE_BLOCK_BELL, 1.2f, 0.35f);
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

	/** Spiegelt die Fächer am Gewinnfach: der Lockvogel liegt dann im Fach dahinter (winIdx - 1). */
	private void mirrorPockets() {
		PrizeDefinition[] copy = pockets.clone();
		for (int d = 0; d < POCKETS; d++) {
			pockets[Math.floorMod(winIdx - d, POCKETS)] = copy[Math.floorMod(winIdx + d, POCKETS)];
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
			return new float[]{wheel + teaseRho + wob, R_BALL, 0f};
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

	// ---- Wettfeld-Geometrie ----------------------------------------------------------------------------------------------

	private float fieldX(int i) {
		return GX + (i + 0.5f) * fieldW;
	}

	/** Stapel liegen im unteren Teil des Felds. */
	private static float stackY() {
		return GY + CH - 21f;
	}

	// ---- Kamera -----------------------------------------------------------------------------------------------------

	/** Am Ende zeigt die Kamera Gewinnfach (unten am Rad) und Gewinnfeld zusammen. */
	@Override
	protected float focusX() {
		return (WCX + fieldX(winField)) / 2f;
	}

	@Override
	protected float focusY() {
		return (WCY + R_ICON + GY + CH / 2f) / 2f;
	}

	@Override
	protected float focusZoom() {
		float dx = Math.abs(fieldX(winField) - WCX);
		float dy = Math.abs(GY + CH / 2f - (WCY + R_ICON));
		return Math.min(1.6f, Math.min(VW / (dx + 200f), VH / (dy + 170f)));
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

		drawBoard(g);
		drawRack(g);
		drawChipStacks(g);

		Gfx.tex(g, T_BOWL, WCX - 150f * K, WCY - 150f * K, 300f * K, 300f * K, Gfx.argb(0xFFFFFFFF, 1f));
		float wheel = wheelAngle(t);
		drawWheel(g, wheel);
		if (t >= tWin) drawWinnerPocket(g, wheel);
		if (teaseOffset != 0 && t >= tTease && t < tTeaseEnd) drawTeaseGlow(g, wheel);
		drawBall(g);
		drawCroupierHand(g);
		drawSign(g);
	}

	/** Das Wettfeld von oben: Felder abwechselnd rot und schwarz, weiße Linien, links die Spitze wie beim "0"-Feld. */
	private void drawBoard(GuiGraphicsExtractor g) {
		int line = Gfx.argb(0xFFEDE6C8, 0.95f);
		float w = fields.size() * fieldW;
		Gfx.outlinedText(g, "EINSATZ", GX + w / 2f, GY - 11f, 1f, GOLD, OUTLINE, 1f);
		// Spitze links mit Goldmünze
		Gfx.line(g, GX, GY, GX - 28f, GY + CH / 2f, 1.6f, line);
		Gfx.line(g, GX - 28f, GY + CH / 2f, GX, GY + CH, 1.6f, line);
		Gfx.texCentered(g, T_COIN, GX - 11f, GY + CH / 2f, 15f, 15f, 0f, Gfx.argb(0xFFFFFFFF, 1f));

		boolean won = t >= tWin;
		boolean teasing = t >= tTease && t < tTeaseEnd;
		for (int i = 0; i < fields.size(); i++) {
			PrizeDefinition prize = fields.get(i);
			float x = GX + i * fieldW;
			int base = i % 2 == 0 ? 0xFFB3202E : 0xFF1F1D24;
			int fill = base;
			if (won && i == winField) {
				float f = Gfx.progress(t, tWin, 0.25f) * (0.75f + 0.2f * (float) Math.sin((t - tWin) * 10f));
				fill = Gfx.lerpColor(base, Gfx.lerpColor(rarity.color, 0xFFFFFFFF, 0.2f), f);
			} else if (teasing && i == teaseField) {
				fill = Gfx.lerpColor(base, prize.rarity.color, 0.25f + 0.45f * (0.5f + 0.5f * (float) Math.sin((t - tTease) * 16f)));
			} else if (won) {
				fill = Gfx.lerpColor(base, 0xFF101010, 0.35f * Gfx.progress(t, tWin, 0.3f));
			}
			Gfx.rect(g, x, GY, fieldW, CH, Gfx.argb(fill, 1f));
			// Seltenheits-Streifen unten verbindet das Feld mit der Farbe des Fachs im Rad
			Gfx.rect(g, x + 2f, GY + CH - 7f, fieldW - 4f, 5f, Gfx.argb(prize.rarity.color, 1f));
			float icon = Math.min(30f, fieldW * 0.68f);
			icon(prize).draw(g, x + fieldW / 2f, GY + 22f, icon, icon, 0f, Gfx.argb(0xFFFFFFFF, 1f));
			// Name senkrecht wie die Zahlen auf einem echten Tisch
			String name = prize.name.toUpperCase(Locale.GERMAN);
			float scale = Math.min(Math.min(1.1f, fieldW / 14f), 66f / Math.max(1, Gfx.textWidth(name)));
			Matrix3x2fStack p = g.pose();
			p.pushMatrix();
			p.translate(x + fieldW / 2f, GY + 72f);
			p.rotate((float) (-Math.PI / 2));
			Gfx.outlinedText(g, name, 0f, 0f, scale, 0xFFFFFFFF, OUTLINE, 1f);
			p.popMatrix();
		}
		// Linien
		Gfx.rect(g, GX, GY - 0.8f, w, 1.6f, line);
		Gfx.rect(g, GX, GY + CH - 0.8f, w, 1.6f, line);
		for (int i = 0; i <= fields.size(); i++) Gfx.rect(g, GX + i * fieldW - 0.8f, GY, 1.6f, CH, line);
		// Feld-Leiste darunter (wie "1st 12" usw.): Seltenheit
		Gfx.rect(g, GX, GY + CH + 22f - 0.8f, w, 1.6f, line);
		Gfx.rect(g, GX - 0.8f, GY + CH, 1.6f, 22f, line);
		Gfx.rect(g, GX + w - 0.8f, GY + CH, 1.6f, 22f, line);
		Gfx.centeredText(g, "GEWINN-FELDER", GX + w / 2f, GY + CH + 11f, 0.9f, 0xFFEDE6C8, 0.95f);

		if (teasing && teaseField >= 0 && teaseField != winField) {
			float f = Gfx.progress(t, tTease, 0.15f) * (1f - Gfx.progress(t, tTeaseEnd - 0.1f, 0.1f));
			float pulse = 0.6f + 0.4f * (float) Math.sin((t - tTease) * 16f);
			Gfx.texCentered(g, T_GLOW, fieldX(teaseField), GY + CH / 2f, fieldW * 2.6f, CH * 1.3f, 0f,
					Gfx.argb(fields.get(teaseField).rarity.color, 0.45f * f * pulse));
		}
		if (won) {
			float hp = Gfx.progress(t, tWin, 0.3f);
			float pulse = 1f + 0.08f * (float) Math.sin(t * 7f);
			float x = GX + winField * fieldW;
			Gfx.texCentered(g, T_GLOW, fieldX(winField), GY + CH / 2f, fieldW * 3f * pulse, CH * 1.4f * pulse, 0f, Gfx.argb(rarity.color, 0.55f * hp));
			int edge = Gfx.argb(Gfx.lerpColor(GOLD, 0xFFFFFFFF, 0.3f), hp);
			Gfx.rect(g, x - 1.5f, GY - 1.5f, fieldW + 3f, 3f, edge);
			Gfx.rect(g, x - 1.5f, GY + CH - 1.5f, fieldW + 3f, 3f, edge);
			Gfx.rect(g, x - 1.5f, GY - 1.5f, 3f, CH + 3f, edge);
			Gfx.rect(g, x + fieldW - 1.5f, GY - 1.5f, 3f, CH + 3f, edge);
			drawSparkles(g, fieldX(winField), GY + 40f, 34f, 6, t - tWin);
		}
	}

	/** Chip-Reihe unter dem Wettfeld (wie auf dem Tisch des Croupiers). */
	private void drawRack(GuiGraphicsExtractor g) {
		float w = fields.size() * fieldW;
		int n = CHIP_COLORS.length;
		float step = Math.min(30f, (w + 20f) / n);
		float x0 = GX + w / 2f - step * (n - 1) / 2f;
		for (int i = 0; i < n; i++) drawStack(g, x0 + i * step, RACK_Y, CHIP_COLORS[i], 3, 1f);
	}

	private float rackX() {
		return GX + fields.size() * fieldW / 2f;
	}

	private void drawChipStacks(GuiGraphicsExtractor g) {
		boolean won = t >= tWin;
		for (Bet b : bets) {
			if (t < b.at) continue;
			float x = fieldX(b.field), y = stackY();
			float drop = 1f - Gfx.easeOutBack(Gfx.progress(t, b.at, 0.16f));
			float a = 1f;
			float jump = 0f;
			if (won) {
				if (b.field == winField) {
					float since = t - tWin;
					jump = since < 0.5f ? (float) Math.abs(Math.sin(since * 12f)) * 4f * (1f - since / 0.5f) : 0f;
					Gfx.texCentered(g, T_GLOW, x, y - 4f, 46, 46, 0f, Gfx.argb(GOLD, 0.6f * Gfx.progress(t, tWin, 0.25f)));
				} else {
					a = 1f - 0.45f * Gfx.progress(t, tWin, 0.3f);
				}
			}
			drawStack(g, x, y - drop * 10f - jump, b.color, b.chips, a);
		}
	}

	/** Chip-Stapel von oben; (x, y) = Mitte des untersten Chips, jeder weitere liegt leicht versetzt darüber. */
	private static void drawStack(GuiGraphicsExtractor g, float x, float y, int color, int chips, float a) {
		float size = 20f;
		for (int j = 0; j < chips; j++) {
			float cx = x - j * 0.5f, cy = y - j * 1.8f;
			int col = j == chips - 1 ? color : Gfx.lerpColor(color, 0xFF000000, 0.25f);
			Gfx.texCentered(g, T_CHIP, cx, cy, size, size, 0f, Gfx.argb(col, a));
			Gfx.texCentered(g, T_CHIP_MARKS, cx, cy, size, size, 0f, Gfx.argb(0xFFFFFFFF, a));
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
			icon(pockets[i]).draw(g, 0f, 0f, ICON, ICON, 0f, Gfx.argb(0xFFFFFFFF, 1f - 0.5f * blur));
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
		icon(pockets[winIdx]).draw(g, x, y - 4f * hp + bob, size, size, 0f, Gfx.argb(0xFFFFFFFF, 1f));
		drawSparkles(g, x, y, 28f, 6, t - tWin);
	}

	private void drawTeaseGlow(GuiGraphicsExtractor g, float wheel) {
		int tease = Math.floorMod(winIdx + teaseOffset, POCKETS);
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
		// von oben: eine springende Kugel wird größer, ihr Schatten wandert weg
		float h = b[2];
		Gfx.texCentered(g, T_DISC, x + 1f + h * 0.35f, y + 1.5f + h * 0.5f, BALL * 0.95f, BALL * 0.95f, 0f, Gfx.argb(0xFF000000, 0.3f));
		float size = BALL * (1f + h * 0.035f);
		Gfx.texCentered(g, T_BALL, x, y - h * 0.3f, size, size, 0f, Gfx.argb(0xFFFFFFFF, 1f));
	}

	// ---- Hand -------------------------------------------------------------------------------------------------------

	/** Wo die Hand einen Stapel absetzt (Greifpunkt über dem Stapel). */
	private float[] betSpot(Bet b) {
		return new float[]{fieldX(b.field) + 2f, stackY() - 10f};
	}

	/** Greifpunkt der Hand und ihr Winkel (Ärmel zeigt nach rechts unten). */
	private float[] handPos(float time) {
		float turretX = WCX + 2f, turretY = WCY - 2f;
		float launchX = px(0f, R_TRACK) - 4f, launchY = WCY - 2f;
		// Wegpunkte: Chips holen, dann die Felder der Reihe nach
		List<float[]> keys = new ArrayList<>();
		keys.add(new float[]{tHandIn, VW + 170f, RACK_Y + 60f});
		keys.add(new float[]{tRack, rackX() + 2f, RACK_Y - 10f});
		for (Bet b : bets) {
			float[] s = betSpot(b);
			keys.add(new float[]{b.at, s[0], s[1]});
		}
		keys.add(new float[]{tSpin, turretX, turretY});
		if (time < tSpin) {
			for (int i = 0; i + 1 < keys.size(); i++) {
				float[] k0 = keys.get(i), k1 = keys.get(i + 1);
				if (time < k1[0]) {
					boolean first = i == 0;
					float from = first ? k0[0] : k0[0] + 0.06f;
					float m = Gfx.easeInOutCubic(Gfx.progress(time, from, k1[0] - 0.04f - from));
					if (first) m = Gfx.easeOutCubic(Gfx.progress(time, from, k1[0] - 0.04f - from));
					boolean last = i + 2 == keys.size();
					float lift = first ? 0f : (float) Math.sin(m * Math.PI) * (last ? 18f : 6f);
					float angle = last ? Gfx.lerp(0.35f, 0.5f, m) : 0.35f;
					return new float[]{Gfx.lerp(k0[1], k1[1], m), Gfx.lerp(k0[2], k1[2], m) - lift + dip(time), angle};
				}
			}
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

	/** Kurzes Absenken der Hand beim Greifen und Absetzen. */
	private float dip(float time) {
		float d = 0f;
		List<Float> at = new ArrayList<>();
		at.add(tRack);
		for (Bet b : bets) at.add(b.at);
		for (float a : at) {
			float p = (time - (a - 0.06f)) / 0.14f;
			if (p > 0f && p < 1f) d += 6f * (float) Math.sin(p * Math.PI);
		}
		return d;
	}

	private void drawCroupierHand(GuiGraphicsExtractor g) {
		if (t < tHandIn || t > tLaunch + 0.45f) return;
		float[] h = handPos(t);
		// Stapel, den die Hand gerade trägt
		if (t >= tRack) {
			for (Bet b : bets) {
				if (t < b.at) {
					drawStack(g, h[0] - 2f, h[1] + 10f, b.color, b.chips, 1f);
					break;
				}
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
