package de.schiessbude.anim;

import de.schiessbude.config.ModConfig;
import de.schiessbude.config.PrizeDefinition;
import de.schiessbude.config.Rarity;
import de.schiessbude.config.Reward;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Die Schießbuden-Animation.
 * <p>
 * Ablauf: Schießbude erscheint, Zielscheiben mit verschiedenen Gewinnen klappen hoch, die Pistole in der Mitte
 * schießt eine Scheibe nach der anderen um (beim letzten Schuss zielt sie spannungsvoll erst auf deinen Gewinn),
 * bis nur noch dein Gewinn steht. Dann zoomt die Kamera heran und der Gewinn wird groß enthüllt.
 * <p>
 * Alles wird als reine Funktion der Zeit {@code t} berechnet: Pausieren und Überspringen sind dadurch trivial.
 */
public final class ShootingRangeAnimation {
	// ---- Layout in virtuellen Einheiten (passt zu tools/generate_textures.py) ----
	static final float VW = 640f, VH = 360f;
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
	static final float CARD_X = 320f, CARD_Y = 158f;

	// ---- Texturen ----
	static final Identifier T_BG = Gfx.id("textures/gui/background.png");
	static final Identifier T_BOARD = Gfx.id("textures/gui/target_board.png");
	static final Identifier T_RING = Gfx.id("textures/gui/rarity_ring.png");
	static final Identifier T_PISTOL = Gfx.id("textures/gui/pistol.png");
	static final Identifier T_MOUNT = Gfx.id("textures/gui/mount.png");
	static final Identifier T_GLOW = Gfx.id("textures/gui/glow.png");
	static final Identifier T_DISC = Gfx.id("textures/gui/disc.png");
	static final Identifier T_RAYS = Gfx.id("textures/gui/rays.png");
	static final Identifier T_FLASH = Gfx.id("textures/gui/muzzle_flash.png");
	static final Identifier T_SPARKLE = Gfx.id("textures/gui/sparkle.png");
	static final Identifier T_SIGN = Gfx.id("textures/gui/sign.png");
	static final Identifier T_BANNER = Gfx.id("textures/gui/banner.png");
	static final Identifier T_CARD = Gfx.id("textures/gui/card.png");

	static final int OUTLINE = 0xFF231230;
	static final int GOLD = 0xFFFFC93C;

	private final Reward reward;
	private final Rarity rarity;
	private final Random rnd;
	private final boolean chained;
	private final List<Target> targets = new ArrayList<>();
	private final Target winner;
	private final List<Shot> shots = new ArrayList<>();
	private final List<float[]> gunKeys = new ArrayList<>();
	private final List<float[]> suspense = new ArrayList<>();
	private final List<SoundCue> cues = new ArrayList<>();
	private final List<Confetti> confetti = new ArrayList<>();
	private final float[] sparkleSeed = new float[64];

	private final float tLastFall, tZoomStart, tReveal, tFadeOut, tEnd;
	private float t;
	private int nextCue;

	public ShootingRangeAnimation(Reward reward, long seed, boolean chained, float revealSeconds) {
		this.reward = reward;
		this.rarity = reward.prize().rarity;
		this.rnd = new Random(seed);
		this.chained = chained;
		for (int i = 0; i < sparkleSeed.length; i++) sparkleSeed[i] = rnd.nextFloat();

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
		float popStart = chained ? 0.15f : 0.38f;
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
			boolean fake = last || (rarity == Rarity.LEGENDARY && k == n - 2);
			if (fake) {
				// Spannung: zuerst auf den Gewinn zielen ...
				float onWinner = time + 0.42f;
				key(onWinner, angleOf(winner));
				float hold = last ? (rarity.atLeast(Rarity.EPIC) ? 1.05f : 0.75f) : 0.5f;
				suspense.add(new float[]{onWinner, onWinner + hold});
				for (float b = onWinner; b < onWinner + hold - 0.05f; b += 0.2f) {
					cue(b, SoundEvents.NOTE_BLOCK_BASEDRUM, 0.75f + (b - onWinner) * 0.4f, 0.55f);
				}
				time = onWinner + hold;
				key(time, angleOf(winner));
				// ... dann blitzschnell zum Köder schwenken
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
		this.tLastFall = time + BULLET_TIME + FALL_TIME - 0.1f;
		key(time + 0.1f, angle);
		key(time + 0.6f, angleOf(winner));
		cue(tLastFall, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 1.0f);
		cue(tLastFall + 0.15f, SoundEvents.AMETHYST_BLOCK_CHIME, 1.4f, 0.9f);

		this.tZoomStart = tLastFall + 0.35f;
		float tZoomEnd = tZoomStart + 0.85f;
		cue(tZoomStart, SoundEvents.NOTE_BLOCK_BELL, 0.8f, 0.6f);
		cue(tZoomStart + 0.3f, SoundEvents.NOTE_BLOCK_BELL, 1.0f, 0.7f);
		cue(tZoomStart + 0.6f, SoundEvents.NOTE_BLOCK_BELL, 1.3f, 0.8f);
		this.tReveal = tZoomEnd;
		float hold = revealSeconds + (rarity == Rarity.LEGENDARY ? 0.8f : rarity == Rarity.EPIC ? 0.4f : 0f);
		this.tFadeOut = tReveal + hold;
		this.tEnd = tFadeOut + 0.45f;

		// Enthüllungs-Sounds nach Seltenheit
		switch (rarity) {
			case COMMON -> cue(tReveal, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.9f, 0.9f);
			case UNCOMMON -> cue(tReveal, SoundEvents.PLAYER_LEVELUP, 1.3f, 0.7f);
			case RARE -> cue(tReveal, SoundEvents.PLAYER_LEVELUP, 1.0f, 0.85f);
			case EPIC -> cue(tReveal, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.1f, 0.8f);
			case LEGENDARY -> {
				cue(tReveal, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.9f);
				cue(tReveal + 0.3f, SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.0f, 0.8f);
				cue(tReveal + 0.7f, SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.2f, 0.7f);
			}
		}
		cue(tReveal, SoundEvents.FIREWORK_ROCKET_BLAST, 0.7f, 0.6f);
		cues.sort(Comparator.comparingDouble(c -> c.time));

		if (rarity.atLeast(Rarity.EPIC)) {
			int count = rarity == Rarity.LEGENDARY ? 110 : 70;
			for (int i = 0; i < count; i++) confetti.add(new Confetti(rnd, rarity));
		}
	}

	private List<PrizeDefinition> pickDecoyPrizes(int count) {
		List<PrizeDefinition> all = new ArrayList<>(ModConfig.get().prizes);
		List<PrizeDefinition> result = new ArrayList<>();
		// Jede Gewinnart mindestens einmal zeigen (sofern Platz ist) ...
		List<PrizeDefinition> shuffled = new ArrayList<>(all);
		Collections.shuffle(shuffled, rnd);
		for (PrizeDefinition p : shuffled) {
			if (result.size() < count) result.add(p);
		}
		// ... Rest nach Gewichtung auffüllen
		while (result.size() < count) result.add(ModConfig.get().randomPrize(rnd));
		Collections.shuffle(result, rnd);
		return result;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Zeitsteuerung
	// ---------------------------------------------------------------------------------------------------------------

	public float time() {
		return t;
	}

	public Reward reward() {
		return reward;
	}

	/** Spielt Sounds ab, deren Zeitpunkt überschritten wurde. */
	public void advance(float dt, float volume) {
		t += dt;
		while (nextCue < cues.size() && cues.get(nextCue).time <= t) {
			SoundCue c = cues.get(nextCue++);
			// Sehr alte Sounds (z. B. nach Überspringen) nicht nachholen
			if (t - c.time < 0.25f) Sounds.play(c.sound, c.pitch, c.volume * volume);
		}
	}

	/** Überspringen: erst direkt zur Enthüllung, beim zweiten Mal ans Ende. */
	public void skip() {
		if (t < tReveal - 0.15f) {
			t = tReveal - 0.15f;
		} else if (t < tFadeOut) {
			t = tFadeOut;
		}
		while (nextCue < cues.size() && cues.get(nextCue).time < t - 0.01f) nextCue++;
	}

	/** Fertig? Wenn schon der nächste Fund wartet, wird das Ausblenden ausgelassen. */
	public boolean isFinished(boolean nextWaiting) {
		return t >= (nextWaiting ? tFadeOut : tEnd);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Zeichnen
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * @param width,height GUI-Größe
	 * @param queued       Anzahl wartender Funde
	 * @param skipHint     Name der Überspringen-Taste (oder null)
	 */
	public void render(GuiGraphicsExtractor g, int width, int height, int queued, String skipHint) {
		float intro = chained ? 1f : Gfx.progress(t, 0f, 0.22f);
		float outro = 1f - Gfx.progress(t, tFadeOut, tEnd - tFadeOut);
		Gfx.alpha = Math.min(intro, outro);

		float s = Math.min(width / VW, height / VH);
		Matrix3x2fStack pose = g.pose();

		// Vollflächiger Hintergrund: verdeckt Chat, Titel, Bossleiste usw. komplett
		g.fillGradient(0, 0, width, height, Gfx.argb(0xFF2E145C, 1f), Gfx.argb(0xFF301A54, 1f));

		// ---- Szene (mit Kamera-Zoom und Wackeln) ----
		float zoomP = Gfx.easeInOutCubic(Gfx.progress(t, tZoomStart, tReveal - tZoomStart));
		float zoom = Gfx.lerp(1f, 1.75f, zoomP);
		float fx = Gfx.lerp(VW / 2f, winner.x(), zoomP);
		float fy = Gfx.lerp(VH / 2f, winner.boardCenterY(), zoomP);
		float slide = chained ? 0f : (1f - Gfx.easeOutCubic(Gfx.progress(t, 0f, 0.5f))) * 40f;
		float shake = shakeAmount();
		float sx = (float) Math.sin(t * 93.0) * shake;
		float sy = (float) Math.cos(t * 71.0) * shake;

		pose.pushMatrix();
		pose.translate(width / 2f, height / 2f);
		pose.scale(s * zoom, s * zoom);
		pose.translate(-fx + sx, -fy + sy + slide);
		drawScene(g);
		pose.popMatrix();

		// ---- Direkt folgender Fund: kurz aus dem Dunkeln einblenden ----
		if (chained && t < 0.35f) g.fill(0, 0, width, height, Gfx.argb(0xFF140A24, 1f - Gfx.progress(t, 0f, 0.35f)));

		// ---- Weißer Blitz beim Übergang zur Enthüllung ----
		float flash = t < tReveal ? Gfx.progress(t, tReveal - 0.12f, 0.12f) : 1f - Gfx.progress(t, tReveal, 0.45f);
		if (flash > 0f) g.fill(0, 0, width, height, Gfx.argb(0xFFFFFFFF, flash));

		// ---- Enthüllung ----
		if (t >= tReveal) {
			float dim = Gfx.progress(t, tReveal, 0.15f);
			int darkTint = Gfx.lerpColor(0xFF120A1E, rarity.color, 0.18f);
			g.fill(0, 0, width, height, Gfx.argb(darkTint, 0.86f * dim));
			pose.pushMatrix();
			pose.translate(width / 2f, height / 2f);
			pose.scale(s, s);
			pose.translate(-VW / 2f, -VH / 2f);
			drawReveal(g);
			pose.popMatrix();
			if (flash > 0f) g.fill(0, 0, width, height, Gfx.argb(0xFFFFFFFF, flash * 0.85f));
		}

		// ---- Kleine Hinweise in den Ecken ----
		drawHud(g, width, height, queued, skipHint);
		Gfx.alpha = 1f;
	}

	private float shakeAmount() {
		float amount = 0f;
		for (Shot shot : shots) {
			float dt = t - shot.fireTime;
			if (dt >= 0f && dt < 0.18f) amount = Math.max(amount, 2.2f * (1f - dt / 0.18f));
		}
		for (float[] w : suspense) {
			if (t >= w[0] && t <= w[1]) amount = Math.max(amount, 0.5f);
		}
		float zoomShake = Gfx.progress(t, tZoomStart + 0.3f, tReveal - tZoomStart - 0.3f);
		if (t < tReveal) amount = Math.max(amount, zoomShake * 1.6f);
		float revealShake = t >= tReveal ? 3.5f * (1f - Gfx.progress(t, tReveal, 0.35f)) : 0f;
		return Math.max(amount, revealShake);
	}

	private void drawScene(GuiGraphicsExtractor g) {
		Gfx.tex(g, T_BG, 0, 0, VW, VH, Gfx.argb(0xFFFFFFFF, 1f));

		// Lichterkette an der Markise (Lauflicht)
		int step = (int) Math.floor(t * (t > tLastFall ? 12f : 6f));
		for (int i = 0; i < 16; i++) {
			float bx = 20 + i * 40, by = 52;
			boolean on = (i + step) % 3 != 0;
			int col = t > tLastFall ? Gfx.lerpColor(0xFFFFE9A0, rarity.color, 0.6f) : 0xFFFFE9A0;
			if (on) Gfx.texCentered(g, T_GLOW, bx, by, 26, 26, 0f, Gfx.argb(col, 0.75f));
			Gfx.texCentered(g, T_DISC, bx, by, 7, 7, 0f, Gfx.argb(on ? 0xFFFFF6D0 : 0xFF8A6A50, 1f));
		}

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
		boolean highlight = tg.winner && t >= tLastFall;
		float bob = 0f;
		if (highlight) {
			float hp = Gfx.progress(t, tLastFall, 0.4f);
			bob = (float) Math.sin((t - tLastFall) * 5f) * 2.5f * hp;
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
		float glowA = switch (tg.prize.rarity) {
			case COMMON -> 0.12f;
			case UNCOMMON -> 0.2f;
			case RARE -> 0.28f;
			case EPIC -> 0.36f;
			case LEGENDARY -> 0.48f;
		};
		Gfx.texCentered(g, T_GLOW, 0, boardCy, BOARD * 1.7f, BOARD * 1.7f, 0f, Gfx.argb(tg.prize.rarity.color, glowA));
		Gfx.texCentered(g, T_RING, 0, boardCy, RING, RING, 0f, Gfx.argb(tg.prize.rarity.color, 1f));
		Gfx.texCentered(g, T_BOARD, 0, boardCy, BOARD, BOARD, 0f, Gfx.argb(0xFFFFFFFF, 1f));
		PrizeIcon.of(tg.prize).draw(g, 0, boardCy, ICON, 0f, 1f);
		if (whiteFlash > 0f) {
			Gfx.texCentered(g, T_DISC, 0, boardCy, BOARD, BOARD, 0f, Gfx.argb(0xFFFFFFFF, whiteFlash));
		}
		p.popMatrix();

		// Funkeln um den Gewinner
		if (highlight) {
			for (int i = 0; i < 6; i++) {
				float a = sparkleSeed[i] * 6.28f + (t - tLastFall) * 1.6f;
				float r = 34f * sc + sparkleSeed[i + 6] * 10f;
				float tw = (float) Math.pow(Math.max(0, Math.sin(t * 4f + i * 1.7f)), 2);
				Gfx.texCentered(g, T_SPARKLE, tg.x() + (float) Math.cos(a) * r, tg.boardCenterY() + (float) Math.sin(a) * r,
						9f * tw + 2f, 9f * tw + 2f, 0f, Gfx.argb(0xFFFFFFFF, tw));
			}
		}
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

	private void drawSign(GuiGraphicsExtractor g) {
		float swing = (float) Math.sin(t * 2.2f) * 0.025f;
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(VW / 2f, 2f);
		p.rotate(swing);
		Gfx.tex(g, T_SIGN, -84, 0, 168, 40, Gfx.argb(0xFFFFFFFF, 1f));
		Gfx.outlinedText(g, "GOLDRAUSCH", 0, 22.5f, 1.55f, GOLD, OUTLINE, 1f);
		p.popMatrix();
	}

	private void drawReveal(GuiGraphicsExtractor g) {
		float rt = t - tReveal;
		int rc = rarity.color;
		int light = Gfx.lerpColor(rc, 0xFFFFFFFF, 0.55f);

		// Strahlen und Leuchten
		float raysIn = Gfx.easeOutCubic(Gfx.progress(rt, 0f, 0.5f));
		Gfx.texCentered(g, T_RAYS, CARD_X, CARD_Y, 620 * raysIn, 620 * raysIn, rt * 0.35f, Gfx.argb(rc, 0.5f));
		Gfx.texCentered(g, T_RAYS, CARD_X, CARD_Y, 430 * raysIn, 430 * raysIn, -rt * 0.5f + 0.2f, Gfx.argb(light, 0.3f));
		float pulse = 1f + (float) Math.sin(rt * 4f) * 0.05f;
		Gfx.texCentered(g, T_GLOW, CARD_X, CARD_Y, 330 * pulse * raysIn, 330 * pulse * raysIn, 0f, Gfx.argb(rc, 0.85f));

		// Konfetti (Episch / Legendär)
		for (Confetti c : confetti) c.draw(g, rt);

		// Karte mit Gewinn-Bild
		float cardP = Gfx.easeOutBack(Gfx.progress(rt, 0.02f, 0.45f));
		float bob = (float) Math.sin(rt * 2.4f) * 3f;
		Gfx.texCentered(g, T_RING, CARD_X, CARD_Y + bob, 168 * cardP, 168 * cardP, 0f, Gfx.argb(rc, 1f));
		Gfx.texCentered(g, T_CARD, CARD_X, CARD_Y + bob, 146 * cardP, 146 * cardP, 0f, Gfx.argb(Gfx.lerpColor(rc, 0xFFFFFFFF, 0.72f), 1f));
		float iconP = Gfx.easeOutBack(Gfx.progress(rt, 0.12f, 0.45f));
		float wobble = (float) Math.sin(rt * 1.7f) * 0.07f;
		PrizeIcon.of(reward.prize()).draw(g, CARD_X, CARD_Y + bob, 104 * iconP, wobble, 1f);

		// Funkeln
		int sparkles = 8 + rarity.ordinal() * 4;
		for (int i = 0; i < sparkles && i < 32; i++) {
			float a = sparkleSeed[i] * 6.28f + rt * 0.25f;
			float r = 84f + sparkleSeed[i + 32] * 60f;
			float tw = (float) Math.pow(Math.max(0, Math.sin(rt * 3f + i * 1.3f)), 2) * cardP;
			int col = i % 2 == 0 ? 0xFFFFFFFF : light;
			Gfx.texCentered(g, T_SPARKLE, CARD_X + (float) Math.cos(a) * r * 1.25f, CARD_Y + (float) Math.sin(a) * r * 0.8f,
					14f * tw + 2f, 14f * tw + 2f, 0f, Gfx.argb(col, tw));
		}

		// Seltenheit über der Karte
		float labelP = Gfx.easeOutBack(Gfx.progress(rt, 0.25f, 0.35f));
		Gfx.outlinedText(g, rarity.displayName.toUpperCase(Locale.GERMAN), CARD_X, 50, 2.5f * labelP, rc, OUTLINE, labelP > 0 ? 1f : 0f);

		// Banner mit Namen
		float bannerP = Gfx.easeOutBack(Gfx.progress(rt, 0.32f, 0.4f));
		if (bannerP > 0f) {
			Matrix3x2fStack p = g.pose();
			p.pushMatrix();
			p.translate(CARD_X, 266);
			p.scale(bannerP, bannerP);
			Gfx.tex(g, T_BANNER, -155, -27, 310, 54, Gfx.argb(rc, 1f));
			String name = reward.prize().name.toUpperCase(Locale.GERMAN);
			float nameScale = Math.min(2.4f, 236f / Math.max(1, Gfx.textWidth(name)));
			Gfx.outlinedText(g, name, 0, -6, nameScale, 0xFFFFFFFF, OUTLINE, 1f);
			p.popMatrix();
		}

		// Detail (Betrag, Spawner-Typ ...)
		String detail = reward.detailOrDescription();
		if (!detail.isBlank()) {
			float dp = Gfx.progress(rt, 0.5f, 0.3f);
			boolean money = detail.contains("$");
			float dScale = Math.min(money ? 2.0f : 1.6f, 560f / Math.max(1, Gfx.textWidth(detail)));
			Gfx.outlinedText(g, detail, CARD_X, 314 + (1f - Gfx.easeOutCubic(dp)) * 10f, dScale,
					money ? GOLD : 0xFFFFFFFF, OUTLINE, dp);
		}
	}

	private void drawHud(GuiGraphicsExtractor g, int width, int height, int queued, String skipHint) {
		float a = 0.9f;
		if (skipHint != null) {
			String s = "[" + skipHint + "] Überspringen";
			int w = Gfx.textWidth(s);
			g.fill(4, height - 16, 12 + w, height - 4, Gfx.argb(0xAA000000, a));
			Gfx.text(g, s, 8, height - 14, 1f, 0xFFDDDDDD, a);
		}
		if (queued > 0) {
			String s = "+" + queued + (queued == 1 ? " weiterer Fund" : " weitere Funde");
			int w = Gfx.textWidth(s);
			g.fill(width - w - 12, height - 16, width - 4, height - 4, Gfx.argb(0xAA000000, a));
			Gfx.text(g, s, width - w - 8, height - 14, 1f, GOLD, a);
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

	private void cue(float time, SoundEvent sound, float pitch, float volume) {
		cues.add(new SoundCue(time, sound, pitch, volume));
	}

	private void cue(float time, Holder<SoundEvent> sound, float pitch, float volume) {
		cues.add(new SoundCue(time, sound.value(), pitch, volume));
	}

	private record SoundCue(float time, SoundEvent sound, float pitch, float volume) {
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

	static final class Confetti {
		final float x0, y0, vy, swayAmp, swayFreq, spin, w, h;
		final int color;
		final float delay;

		Confetti(Random rnd, Rarity rarity) {
			x0 = rnd.nextFloat() * VW;
			y0 = -10f - rnd.nextFloat() * 160f;
			vy = 55f + rnd.nextFloat() * 85f;
			swayAmp = 8f + rnd.nextFloat() * 22f;
			swayFreq = 1.5f + rnd.nextFloat() * 2.5f;
			spin = (rnd.nextFloat() * 2f - 1f) * 6f;
			w = 3f + rnd.nextFloat() * 3f;
			h = 5f + rnd.nextFloat() * 5f;
			delay = rnd.nextFloat() * 0.3f;
			int[] palette = rarity == Rarity.LEGENDARY
					? new int[]{0xFFFFC93C, 0xFFFFE680, 0xFFFF9F1C, 0xFFFFFFFF, 0xFFFFB347}
					: new int[]{0xFFB060FF, 0xFFFF5FA2, 0xFF3FA7FF, 0xFFFFC93C, 0xFF4CDB5A, 0xFFFFFFFF};
			color = palette[rnd.nextInt(palette.length)];
		}

		void draw(GuiGraphicsExtractor g, float rt) {
			float lt = rt - delay;
			if (lt < 0f) return;
			float y = y0 + vy * lt;
			if (y > VH + 20f) return;
			float x = x0 + (float) Math.sin(lt * swayFreq) * swayAmp;
			float flip = (float) Math.abs(Math.cos(lt * spin));
			Matrix3x2fStack p = g.pose();
			p.pushMatrix();
			p.translate(x, y);
			p.rotate(lt * spin * 0.5f);
			p.scale(Math.max(0.15f, flip), 1f);
			Gfx.rect(g, -w / 2f, -h / 2f, w, h, Gfx.argb(color, 1f));
			p.popMatrix();
		}
	}
}
