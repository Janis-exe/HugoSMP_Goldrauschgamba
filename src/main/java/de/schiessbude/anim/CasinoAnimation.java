package de.schiessbude.anim;

import de.schiessbude.config.AnimationType;
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
 * Gemeinsame Grundlage aller Gewinn-Animationen (Schießstand, Slotmaschine, Roulette).
 * <p>
 * Die Unterklassen planen im Konstruktor ihren Ablauf bis zu dem Moment, in dem der Gewinn feststeht
 * ({@link #finishTimeline}), und zeichnen ihre Szene ({@link #drawScene}). Kamera-Zoom, weißer Blitz,
 * die große Enthüllung, Konfetti, Sounds, Überspringen und die Hinweise in den Ecken sind für alle gleich.
 * <p>
 * Alles wird als reine Funktion der Zeit {@code t} berechnet: Pausieren und Überspringen sind dadurch trivial.
 */
public abstract class CasinoAnimation {
	/** Virtuelle Bildgröße: alle Szenen sind für 640 x 360 entworfen und werden auf den Bildschirm skaliert. */
	public static final float VW = 640f, VH = 360f;
	static final float CARD_X = 320f, CARD_Y = 158f;

	static final Identifier T_RING = Gfx.id("textures/gui/rarity_ring.png");
	static final Identifier T_GLOW = Gfx.id("textures/gui/glow.png");
	static final Identifier T_DISC = Gfx.id("textures/gui/disc.png");
	static final Identifier T_RAYS = Gfx.id("textures/gui/rays.png");
	static final Identifier T_SPARKLE = Gfx.id("textures/gui/sparkle.png");
	static final Identifier T_SIGN = Gfx.id("textures/gui/sign.png");
	static final Identifier T_BANNER = Gfx.id("textures/gui/banner.png");
	static final Identifier T_CARD = Gfx.id("textures/gui/card.png");
	static final Identifier T_HAND = Gfx.id("textures/gui/hand.png");
	static final Identifier T_HAND_LEFT = Gfx.id("textures/gui/hand_left.png");
	static final Identifier T_COIN = Gfx.id("textures/gui/coin.png");

	static final int OUTLINE = 0xFF231230;
	static final int GOLD = 0xFFFFC93C;
	/** Greifpunkt im Handschuh-Bild (hand.png, 150 x 72). */
	static final float HAND_W = 150f, HAND_H = 72f, HAND_GX = 10f, HAND_GY = 37f;

	protected final Reward reward;
	protected final Rarity rarity;
	protected final Random rnd;
	protected final boolean chained;
	/** Beginn der Handlung: Folgt ein Fund direkt auf den vorigen, geht es ohne Einblenden schneller los. */
	protected final float t0;
	protected final float[] sparkleSeed = new float[64];
	private final List<SoundCue> cues = new ArrayList<>();
	private final List<Confetti> confetti = new ArrayList<>();

	/** Ab hier steht der Gewinn fest (alle Köder sind weg, die Walzen stehen, die Kugel liegt). */
	protected float tWin;
	protected float tZoomStart, tReveal, tFadeOut, tEnd;
	protected float t;
	private int nextCue;

	protected CasinoAnimation(Reward reward, long seed, boolean chained) {
		this.reward = reward;
		this.rarity = reward.prize().rarity;
		this.rnd = new Random(seed);
		this.chained = chained;
		this.t0 = chained ? 0.15f : 0.38f;
		for (int i = 0; i < sparkleSeed.length; i++) sparkleSeed[i] = rnd.nextFloat();
	}

	/** Erstellt die passende Animation. {@code type} darf nicht {@link AnimationType#RANDOM} sein. */
	public static CasinoAnimation create(AnimationType type, Reward reward, long seed, boolean chained, float revealSeconds) {
		return switch (type) {
			case SLOT_MACHINE -> new SlotMachineAnimation(reward, seed, chained, revealSeconds);
			case ROULETTE -> new RouletteAnimation(reward, seed, chained, revealSeconds);
			default -> new ShootingRangeAnimation(reward, seed, chained, revealSeconds);
		};
	}

	/**
	 * Schließt den Ablaufplan ab: Zoom, Enthüllung (mit Sounds nach Seltenheit), Ausblenden.
	 * Muss am Ende jedes Unterklassen-Konstruktors aufgerufen werden.
	 */
	protected final void finishTimeline(float tWin, float revealSeconds) {
		this.tWin = tWin;
		cue(tWin, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 1.0f);
		cue(tWin + 0.15f, SoundEvents.AMETHYST_BLOCK_CHIME, 1.4f, 0.9f);

		this.tZoomStart = tWin + 0.35f;
		this.tReveal = tZoomStart + 0.85f;
		cue(tZoomStart, SoundEvents.NOTE_BLOCK_BELL, 0.8f, 0.6f);
		cue(tZoomStart + 0.3f, SoundEvents.NOTE_BLOCK_BELL, 1.0f, 0.7f);
		cue(tZoomStart + 0.6f, SoundEvents.NOTE_BLOCK_BELL, 1.3f, 0.8f);
		float hold = revealSeconds + (rarity == Rarity.LEGENDARY ? 0.8f : rarity == Rarity.EPIC ? 0.4f : 0f);
		this.tFadeOut = tReveal + hold;
		this.tEnd = tFadeOut + 0.45f;

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

	// ---- Was die Unterklassen liefern -------------------------------------------------------------------------------

	/** Zeichnet die Szene in virtuellen Einheiten (0..640 x 0..360). */
	protected abstract void drawScene(GuiGraphicsExtractor g);

	/** Punkt, auf den die Kamera am Ende zoomt (der Gewinn in der Szene). */
	protected abstract float focusX();

	protected abstract float focusY();

	protected float focusZoom() {
		return 1.75f;
	}

	/** Zusätzliches Wackeln der Kamera (Schüsse, Walzen-Stopps ...). */
	protected float sceneShake() {
		return 0f;
	}

	// ---- Zeitsteuerung ----------------------------------------------------------------------------------------------

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

	// ---- Zeichnen ---------------------------------------------------------------------------------------------------

	/**
	 * @param width,height GUI-Größe
	 * @param queued       Anzahl wartender Funde
	 * @param skipHint     Name der Überspringen-Taste (oder null)
	 */
	public final void render(GuiGraphicsExtractor g, int width, int height, int queued, String skipHint) {
		float intro = chained ? 1f : Gfx.progress(t, 0f, 0.22f);
		float outro = 1f - Gfx.progress(t, tFadeOut, tEnd - tFadeOut);
		Gfx.alpha = Math.min(intro, outro);

		float s = Math.min(width / VW, height / VH);
		Matrix3x2fStack pose = g.pose();

		// Vollflächiger Hintergrund: verdeckt Chat, Titel, Bossleiste usw. komplett
		g.fillGradient(0, 0, width, height, Gfx.argb(0xFF2E145C, 1f), Gfx.argb(0xFF301A54, 1f));

		// ---- Szene (mit Kamera-Zoom und Wackeln) ----
		float zoomP = Gfx.easeInOutCubic(Gfx.progress(t, tZoomStart, tReveal - tZoomStart));
		float zoom = Gfx.lerp(1f, focusZoom(), zoomP);
		// Kamera bleibt immer innerhalb der Szene (kein leerer Rand beim Heranzoomen)
		float fx = Gfx.lerp(VW / 2f, focusX(), zoomP);
		float fy = Gfx.lerp(VH / 2f, focusY(), zoomP);
		fx = Math.clamp(fx, VW / (2f * zoom), VW - VW / (2f * zoom));
		fy = Math.clamp(fy, VH / (2f * zoom), VH - VH / (2f * zoom));
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
		float amount = sceneShake();
		float zoomShake = Gfx.progress(t, tZoomStart + 0.3f, tReveal - tZoomStart - 0.3f);
		if (t < tReveal) amount = Math.max(amount, zoomShake * 1.6f);
		float revealShake = t >= tReveal ? 3.5f * (1f - Gfx.progress(t, tReveal, 0.35f)) : 0f;
		return Math.max(amount, revealShake);
	}

	/** Lauflicht an der Markise (Fassungen bei x = 20 + 40 i, y = 52 in allen Hintergründen). */
	protected void drawAwningBulbs(GuiGraphicsExtractor g) {
		boolean won = t > tWin;
		int step = (int) Math.floor(t * (won ? 12f : 6f));
		int col = won ? Gfx.lerpColor(0xFFFFE9A0, rarity.color, 0.6f) : 0xFFFFE9A0;
		for (int i = 0; i < 16; i++) {
			drawBulb(g, 20 + i * 40, 52, (i + step) % 3 != 0, col, 1f);
		}
	}

	protected static void drawBulb(GuiGraphicsExtractor g, float x, float y, boolean on, int color, float size) {
		if (on) Gfx.texCentered(g, T_GLOW, x, y, 26 * size, 26 * size, 0f, Gfx.argb(color, 0.75f));
		Gfx.texCentered(g, T_DISC, x, y, 7 * size, 7 * size, 0f, Gfx.argb(on ? 0xFFFFF6D0 : 0xFF8A6A50, 1f));
	}

	/** Hängendes "GOLDRAUSCH"-Schild oben in der Mitte. */
	protected void drawSign(GuiGraphicsExtractor g) {
		float swing = (float) Math.sin(t * 2.2f) * 0.025f;
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(VW / 2f, 2f);
		p.rotate(swing);
		Gfx.tex(g, T_SIGN, -84, 0, 168, 40, Gfx.argb(0xFFFFFFFF, 1f));
		Gfx.outlinedText(g, "GOLDRAUSCH", 0, 22.5f, 1.55f, GOLD, OUTLINE, 1f);
		p.popMatrix();
	}

	/**
	 * Comic-Handschuh. (x, y) ist der Greifpunkt, {@code angle} dreht um ihn.
	 * {@code mirrored = false}: Ärmel zeigt nach rechts, {@code true}: nach links.
	 */
	protected static void drawHand(GuiGraphicsExtractor g, float x, float y, float angle, float scale, boolean mirrored) {
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(x, y);
		p.rotate(angle);
		p.scale(scale, scale);
		// eigenes gespiegeltes Bild: negative Skalierung würde beim Zeichnen weggeschnitten
		if (mirrored) {
			Gfx.tex(g, T_HAND_LEFT, -(HAND_W - HAND_GX), -HAND_GY, HAND_W, HAND_H, Gfx.argb(0xFFFFFFFF, 1f));
		} else {
			Gfx.tex(g, T_HAND, -HAND_GX, -HAND_GY, HAND_W, HAND_H, Gfx.argb(0xFFFFFFFF, 1f));
		}
		p.popMatrix();
	}

	/** Funkeln rund um einen Punkt (Gewinn hervorheben). */
	protected void drawSparkles(GuiGraphicsExtractor g, float cx, float cy, float radius, int count, float since) {
		for (int i = 0; i < count; i++) {
			float a = sparkleSeed[i] * 6.28f + since * 1.6f;
			float r = radius + sparkleSeed[i + 6] * 10f;
			float tw = (float) Math.pow(Math.max(0, Math.sin(t * 4f + i * 1.7f)), 2);
			Gfx.texCentered(g, T_SPARKLE, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r,
					9f * tw + 2f, 9f * tw + 2f, 0f, Gfx.argb(0xFFFFFFFF, tw));
		}
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

	// ---- Hilfen -----------------------------------------------------------------------------------------------------

	/** Wie stark der Seltenheits-Schein hinter einem Gewinn leuchtet. */
	protected static float glowAlpha(Rarity r) {
		return switch (r) {
			case COMMON -> 0.12f;
			case UNCOMMON -> 0.2f;
			case RARE -> 0.28f;
			case EPIC -> 0.36f;
			case LEGENDARY -> 0.48f;
		};
	}

	/** Köder-Gewinne: jede Gewinnart mindestens einmal (sofern Platz ist), Rest nach Gewichtung. */
	protected List<PrizeDefinition> pickDecoyPrizes(int count) {
		List<PrizeDefinition> result = new ArrayList<>();
		List<PrizeDefinition> shuffled = new ArrayList<>(ModConfig.get().prizes);
		Collections.shuffle(shuffled, rnd);
		for (PrizeDefinition p : shuffled) {
			if (result.size() < count) result.add(p);
		}
		while (result.size() < count) result.add(ModConfig.get().randomPrize(rnd));
		Collections.shuffle(result, rnd);
		return result;
	}

	/** Zufälliger Gewinn, der nicht {@code not} ist (falls es einen anderen gibt). */
	protected PrizeDefinition randomOther(PrizeDefinition not) {
		List<PrizeDefinition> all = ModConfig.get().prizes;
		if (all.size() <= 1) return all.getFirst();
		for (int i = 0; i < 20; i++) {
			PrizeDefinition p = ModConfig.get().randomPrize(rnd);
			if (p != not) return p;
		}
		for (PrizeDefinition p : all) {
			if (p != not) return p;
		}
		return not;
	}

	/** Herzschlag-Trommel während einer Spannungspause. */
	protected void heartbeat(float from, float to) {
		for (float b = from; b < to - 0.05f; b += 0.2f) {
			cue(b, SoundEvents.NOTE_BLOCK_BASEDRUM, 0.75f + (b - from) * 0.4f, 0.55f);
		}
	}

	protected void cue(float time, SoundEvent sound, float pitch, float volume) {
		cues.add(new SoundCue(time, sound, pitch, volume));
	}

	protected void cue(float time, Holder<SoundEvent> sound, float pitch, float volume) {
		cues.add(new SoundCue(time, sound.value(), pitch, volume));
	}

	private record SoundCue(float time, SoundEvent sound, float pitch, float volume) {
	}

	/** Herausspringende Goldmünzen (Slotmaschine). */
	static final class Coin {
		final float x0, y0, vx, vy, spin, delay, size;

		Coin(Random rnd, float x, float y, float delay) {
			this.x0 = x + (rnd.nextFloat() - 0.5f) * 40f;
			this.y0 = y;
			this.vx = (rnd.nextFloat() - 0.5f) * 260f;
			this.vy = -170f - rnd.nextFloat() * 190f;
			this.spin = 6f + rnd.nextFloat() * 10f;
			this.delay = delay;
			this.size = 11f + rnd.nextFloat() * 5f;
		}

		void draw(GuiGraphicsExtractor g, float since) {
			float lt = since - delay;
			if (lt < 0f || lt > 1.6f) return;
			float x = x0 + vx * lt;
			float y = y0 + vy * lt + 0.5f * 640f * lt * lt;
			if (y > VH + 20f) return;
			float flip = Math.abs((float) Math.cos(lt * spin));
			float a = 1f - Gfx.progress(lt, 1.2f, 0.4f);
			Gfx.texCentered(g, T_COIN, x, y, size * Math.max(0.15f, flip), size, 0f, Gfx.argb(0xFFFFFFFF, a));
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
