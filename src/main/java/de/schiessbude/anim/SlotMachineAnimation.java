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
 * Die Slotmaschinen-Animation (im Erz-Test: Kohle): 5 Walzen x 3 Reihen.
 * <p>
 * Ablauf: Eine Hand zieht den Hebel rechts an der Maschine runter, die fünf Walzen drehen sich und bleiben von links
 * nach rechts stehen. Jede der drei Reihen ist eine mögliche Gewinnlinie mit einem eigenen Symbol. Nach der ersten
 * Walze ist also noch alles offen; danach fliegt eine Reihe raus, und vor der letzten Walze sind noch zwei übrig:
 * dein Gewinn und ein Köder. Die letzte Walze kriecht aus, bleibt knapp davor stehen und rutscht dann nach.
 * Am Ende steht immer nur eine Form fünfmal in einer Reihe: dein Gewinn.
 */
public final class SlotMachineAnimation extends CasinoAnimation {
	// ---- Layout in virtuellen Einheiten (passt zu tools/generate_textures.py) ----
	static final float MX = 110f, MY = 44f, MW = 420f, MH = 300f;
	static final float WIN_X = 165f, WIN_Y = 122f, WIN_W = 310f, WIN_H = 150f;
	static final int REELS = 5, ROWS = 3;
	static final float REEL_W = WIN_W / REELS, ROW_H = WIN_H / ROWS;
	/** Mitte der mittleren Reihe und Walzen-Radius; der Winkel zwischen zwei Symbolen ergibt genau eine Reihe. */
	static final float MID_Y = WIN_Y + WIN_H / 2f, REEL_R = 90f;
	static final float STEP = (float) Math.asin(ROW_H / REEL_R);
	static final float SYMBOL = 34f;
	static final float PIVOT_X = 514f, PIVOT_Y = 224f, LEVER_L = 112f, KNOB = 26f;
	static final float CROWN_X = 320f, CROWN_Y = 109f, CROWN_RX = 130f, CROWN_RY = 48f;
	static final float TRAY_X = 320f, TRAY_Y = 318f;
	static final int STRIP = 24;
	static final float ACCEL = 0.3f, SPIN_SPEED = 13f;

	static final Identifier T_BG = Gfx.id("textures/gui/slot_background.png");
	static final Identifier T_MACHINE = Gfx.id("textures/gui/slot_machine.png");
	static final Identifier T_GLASS = Gfx.id("textures/gui/slot_glass.png");
	static final Identifier T_KNOB = Gfx.id("textures/gui/lever_knob.png");

	private final Reel[] reels = new Reel[REELS];
	/** Symbol, das in der jeweiligen Reihe eine Gewinnlinie bilden würde. */
	private final PrizeDefinition[] lineSymbol = new PrizeDefinition[ROWS];
	/** Endstand: cells[walze][reihe]. */
	private final PrizeDefinition[][] cells = new PrizeDefinition[REELS][ROWS];
	private final int winRow, decoyRow;
	private final float tPullStart, tPullDown, tLeverBack;
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

		// --- Welche Reihe gewinnt, welche ist der Köder, wann fliegt die dritte raus? ---
		winRow = rnd.nextInt(ROWS);
		decoyRow = (winRow + 1 + rnd.nextInt(ROWS - 1)) % ROWS;
		int thirdRow = ROWS - winRow - decoyRow;
		PrizeDefinition decoy = pickDecoy(win);
		PrizeDefinition third = anyExcept(win, decoy);
		lineSymbol[winRow] = win;
		lineSymbol[decoyRow] = decoy;
		lineSymbol[thirdRow] = third;
		float roll = rnd.nextFloat();
		int thirdOut = roll < 0.3f ? 1 : roll < 0.7f ? 2 : 3;
		for (int c = 0; c < REELS - 1; c++) {
			cells[c][winRow] = win;
			cells[c][decoyRow] = decoy;
			cells[c][thirdRow] = c < thirdOut ? third : anyExcept(third, null);
		}

		// --- Walzen: vier schnelle Stopps, dann die letzte mit langem Auslaufen ---
		for (int i = 0; i < REELS; i++) reels[i] = new Reel(rnd.nextInt(STRIP));
		float start = tPullDown + 0.02f;
		for (int i = 0; i < REELS - 1; i++) {
			float stop = start + 1.1f + 0.4f * i;
			reels[i].plan(start + 0.05f * i, stop - 0.38f, 0.38f, false, 0f);
			reels[i].fill(cells[i]);
		}
		float hold = rarity == Rarity.LEGENDARY ? 1.05f : rarity.atLeast(Rarity.EPIC) ? 0.85f : 0.65f;
		Reel last = reels[REELS - 1];
		last.plan(start + 0.2f, reels[REELS - 2].tStop - 0.1f, 1.8f, true, hold);
		last.fillFinal(win, decoy);
		for (int r = 0; r < ROWS; r++) cells[REELS - 1][r] = last.symbolAt(last.stopIndex, r);

		scheduleReelSounds();
		scheduleTicks();

		float win0 = last.settled + 0.05f;
		// Münzregen
		int coinCount = 10 + rarity.ordinal() * 7;
		for (int i = 0; i < coinCount; i++) coins.add(new Coin(rnd, TRAY_X, TRAY_Y - 4f, i * 0.025f));
		cue(win0, SoundEvents.ARMOR_EQUIP_GOLD, 1.2f, 0.6f);
		for (int i = 0; i < 6 + rarity.ordinal() * 2; i++) {
			cue(win0 + 0.05f + i * 0.07f, SoundEvents.NOTE_BLOCK_CHIME, 1.5f + rnd.nextFloat() * 0.5f, 0.28f);
		}
		finishTimeline(win0, revealSeconds);
	}

	/** Köder für das Finale: meistens der wertvollste andere Gewinn, sonst ein zufälliger. */
	private PrizeDefinition pickDecoy(PrizeDefinition win) {
		PrizeDefinition best = null;
		for (PrizeDefinition p : ModConfig.get().prizes) {
			if (p != win && (best == null || p.rarity.ordinal() > best.rarity.ordinal())) best = p;
		}
		if (best == null) return win;
		return rnd.nextFloat() < 0.6f ? best : randomOther(win);
	}

	/** Zufälliges Symbol (halb gleichverteilt, halb nach Gewichtung), möglichst nicht {@code a} oder {@code b}. */
	private PrizeDefinition anyExcept(PrizeDefinition a, PrizeDefinition b) {
		List<PrizeDefinition> all = ModConfig.get().prizes;
		for (int i = 0; i < 24; i++) {
			PrizeDefinition p = rnd.nextBoolean() ? all.get(rnd.nextInt(all.size())) : ModConfig.get().randomPrize(rnd);
			if (p != a && p != b) return p;
		}
		for (PrizeDefinition p : all) {
			if (p != a && p != b) return p;
		}
		return all.get(rnd.nextInt(all.size()));
	}

	/** Ab wann eine Walze als "steht" zählt (bei der letzten: nach dem Nachrutschen). */
	private float landed(int c) {
		Reel r = reels[c];
		return r.nudgeAt >= 0f ? r.nudgeAt + 0.12f : r.tStop;
	}

	private void scheduleReelSounds() {
		for (int c = 0; c < REELS; c++) {
			Reel r = reels[c];
			cue(r.tStop, SoundEvents.NOTE_BLOCK_BASEDRUM, 1.2f, 0.7f);
			cue(r.tStop, SoundEvents.STONE_BUTTON_CLICK_ON, 0.9f, 0.6f);
			if (r.nudgeAt >= 0f) {
				// Knapp daneben ... Herzklopfen ... und doch!
				cue(r.tStop + 0.03f, SoundEvents.NOTE_BLOCK_BASS, 0.7f, 0.6f);
				heartbeat(r.tStop + 0.1f, r.nudgeAt);
				cue(r.nudgeAt, SoundEvents.STONE_BUTTON_CLICK_ON, 1.3f, 0.7f);
			}
			int alive = 0, died = 0;
			for (int row = 0; row < ROWS; row++) {
				int[] s = lineState(row, landed(c) + 0.001f);
				if (s[1] < 0 && s[0] == c + 1) alive++;
				if (s[1] == c) died++;
			}
			float at = landed(c) + 0.03f;
			if (alive > 0) cue(at, SoundEvents.NOTE_BLOCK_PLING, 0.9f + 0.18f * c, 0.5f);
			if (died > 0) cue(at + 0.05f, SoundEvents.NOTE_BLOCK_BASS, 0.6f, 0.45f);
		}
		// Spannung, während die letzte Walze ausläuft: aufsteigende Töne
		Reel last = reels[REELS - 1];
		float from = reels[REELS - 2].tStop + 0.15f;
		int k = 0;
		for (float time = from; time < last.tStop - 0.05f; time += 0.16f, k++) {
			cue(time, SoundEvents.NOTE_BLOCK_BIT, 0.7f + k * 0.07f, 0.22f);
		}
	}

	/** Leises Rattern der Walzen: ein Klick pro Symbol der Walze, die am längsten läuft. */
	private void scheduleTicks() {
		int[] lastIdx = new int[REELS];
		for (int i = 0; i < REELS; i++) lastIdx[i] = Math.round(reels[i].pos(0f));
		float lastTick = -1f;
		float end = reels[REELS - 1].settled;
		for (float time = reels[0].tStart; time <= end; time += 1f / 240f) {
			for (int i = REELS - 1; i >= 0; i--) {
				Reel r = reels[i];
				if (time < r.tStart || time > r.tStop) continue;
				int now = Math.round(r.pos(time));
				if (now != lastIdx[i]) {
					lastIdx[i] = now;
					if (time - lastTick > 0.05f) {
						cue(time, SoundEvents.NOTE_BLOCK_HAT, 1.5f + rnd.nextFloat() * 0.2f, 0.16f);
						lastTick = time;
					}
				}
				break;
			}
		}
	}

	/**
	 * Zustand einer Gewinnlinie zur Zeit {@code time}: {Anzahl Walzen von links mit dem Linien-Symbol,
	 * Walze, an der die Linie gerissen ist (oder -1)}.
	 */
	private int[] lineState(int row, float time) {
		int matched = 0;
		for (int c = 0; c < REELS; c++) {
			if (time < landed(c)) break;
			if (cells[c][row] != lineSymbol[row]) return new int[]{matched, c};
			matched++;
		}
		return new int[]{matched, -1};
	}

	private static float rowY(int row) {
		return MID_Y + (row - 1) * ROW_H;
	}

	private static float reelX(int c) {
		return WIN_X + REEL_W * (c + 0.5f);
	}

	// ---------------------------------------------------------------------------------------------------------------

	@Override
	protected float focusX() {
		return WIN_X + WIN_W / 2f;
	}

	@Override
	protected float focusY() {
		return rowY(winRow);
	}

	@Override
	protected float focusZoom() {
		return 1.7f;
	}

	@Override
	protected float sceneShake() {
		float amount = 0f;
		for (Reel r : reels) {
			amount = Math.max(amount, kick(t - r.tStop, 0.14f, 1.2f));
			if (r.nudgeAt >= 0f) amount = Math.max(amount, kick(t - r.nudgeAt, 0.16f, 1.8f));
		}
		amount = Math.max(amount, kick(t - tPullDown, 0.15f, 1.2f));
		if (inSuspense()) amount = Math.max(amount, 0.5f);
		return amount;
	}

	private static float kick(float dt, float duration, float strength) {
		return dt >= 0f && dt < duration ? strength * (1f - dt / duration) : 0f;
	}

	/** Die letzte Walze steht knapp daneben (Herzklopfen). */
	private boolean inSuspense() {
		Reel last = reels[REELS - 1];
		return t >= last.tStop && t <= last.nudgeAt;
	}

	/** Die letzte Walze läuft langsam aus, zwei Linien sind noch im Rennen. */
	private boolean inAnticipation() {
		return t >= reels[REELS - 2].tStop && t < reels[REELS - 1].tStop;
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
		g.enableScissor((int) WIN_X, (int) WIN_Y, (int) (WIN_X + WIN_W), (int) (WIN_Y + WIN_H));
		drawLines(g, false);
		for (int i = 0; i < REELS; i++) drawReel(g, i);
		drawLines(g, true);
		drawWinDim(g);
		g.disableScissor();
		Gfx.tex(g, T_GLASS, WIN_X, WIN_Y, WIN_W, WIN_H, Gfx.argb(0xFFFFFFFF, 1f));
		drawAnticipationFrame(g);
		drawRowArrows(g);
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
		boolean anticipation = inAnticipation();
		int step = (int) Math.floor(t * (won ? 14f : tense ? 10f : anticipation ? 9f : 5f));
		for (int i = 0; i < 11; i++) {
			double a = Math.toRadians(196 + i * 148.0 / 10);
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
		} else if (tense || anticipation) {
			textCol = Gfx.lerpColor(GOLD, 0xFFFF5060, (float) (Math.sin(t * (tense ? 20f : 12f)) * 0.5 + 0.5));
		}
		Gfx.outlinedText(g, "JACKPOT", CROWN_X, 96f, scale, textCol, OUTLINE, 1f);
	}

	/**
	 * Die noch möglichen Gewinnlinien: farbiges Band hinter den passenden Symbolen ({@code front = false})
	 * bzw. Rahmen um sie ({@code front = true}). Eine gerissene Linie blendet kurz aus.
	 */
	private void drawLines(GuiGraphicsExtractor g, boolean front) {
		boolean won = t >= tWin;
		for (int row = 0; row < ROWS; row++) {
			if (won && row != winRow) continue;
			int[] s = lineState(row, t);
			int matched = s[0];
			if (matched == 0) continue;
			float a;
			if (s[1] >= 0) {
				a = 1f - Gfx.progress(t, landed(s[1]), 0.3f);
				if (a <= 0f) continue;
			} else {
				a = 0.75f + 0.25f * (float) Math.sin(t * 7f + row * 2f);
			}
			int col = lineSymbol[row].rarity.color;
			float y = rowY(row);
			float x0 = WIN_X + 3f, x1 = WIN_X + matched * REEL_W - 3f;
			if (won) a = Math.max(a, Gfx.progress(t, tWin, 0.25f));
			if (!front) {
				Gfx.rect(g, x0, y - 22f, x1 - x0, 44f, Gfx.argb(col, (won ? 0.4f : 0.22f) * a));
				for (int c = 0; c < matched; c++) {
					float pulse = won ? 1f + 0.12f * (float) Math.sin((t - tWin) * 8f + c) : 1f;
					Gfx.texCentered(g, T_GLOW, reelX(c), y, 70 * pulse, 56 * pulse, 0f, Gfx.argb(col, (won ? 0.85f : 0.5f) * a));
				}
			} else {
				int edge = Gfx.argb(Gfx.lerpColor(col, 0xFFFFFFFF, 0.45f), 0.9f * a);
				float w = won ? 2.2f : 1.6f;
				Gfx.rect(g, x0, y - 23f, x1 - x0, w, edge);
				Gfx.rect(g, x0, y + 23f - w, x1 - x0, w, edge);
				Gfx.rect(g, x0, y - 23f, w, 46f, edge);
				Gfx.rect(g, x1 - w, y - 23f, w, 46f, edge);
			}
		}
	}

	/** Nach dem Gewinn: alles außer der Gewinnlinie wird abgedunkelt. */
	private void drawWinDim(GuiGraphicsExtractor g) {
		if (t < tWin) return;
		float hp = Gfx.progress(t, tWin, 0.3f);
		for (int row = 0; row < ROWS; row++) {
			if (row == winRow) continue;
			Gfx.rect(g, WIN_X, rowY(row) - ROW_H / 2f, WIN_W, ROW_H, Gfx.argb(OUTLINE, 0.5f * hp));
		}
		for (int c = 0; c < REELS; c++) drawSparkles(g, reelX(c), rowY(winRow), 22f, 3, t - tWin + c);
	}

	/** Leuchtender Rahmen um die letzte Walze, solange sie die Entscheidung bringt. */
	private void drawAnticipationFrame(GuiGraphicsExtractor g) {
		Reel last = reels[REELS - 1];
		float from = reels[REELS - 2].tStop;
		if (t < from || t > last.nudgeAt + 0.3f) return;
		float a = Gfx.progress(t, from, 0.2f) * (1f - Gfx.progress(t, last.nudgeAt, 0.3f));
		float pulse = 0.65f + 0.35f * (float) Math.sin(t * 16f);
		int col = inSuspense() ? 0xFFFF5060 : GOLD;
		float x = WIN_X + REEL_W * (REELS - 1), y = WIN_Y;
		Gfx.texCentered(g, T_GLOW, x + REEL_W / 2f, MID_Y, REEL_W * 2.2f, WIN_H * 1.5f, 0f, Gfx.argb(col, 0.35f * a * pulse));
		float w = 2.6f;
		int c = Gfx.argb(Gfx.lerpColor(col, 0xFFFFFFFF, 0.35f), a * pulse);
		Gfx.rect(g, x, y, REEL_W, w, c);
		Gfx.rect(g, x, y + WIN_H - w, REEL_W, w, c);
		Gfx.rect(g, x, y, w, WIN_H, c);
		Gfx.rect(g, x + REEL_W - w, y, w, WIN_H, c);
	}

	/** Die Pfeile neben den Reihen leuchten, solange die Linie noch gewinnen kann. */
	private void drawRowArrows(GuiGraphicsExtractor g) {
		boolean won = t >= tWin;
		for (int row = 0; row < ROWS; row++) {
			int[] s = lineState(row, t);
			boolean alive = s[1] < 0 && s[0] > 0 && (!won || row == winRow);
			if (!alive) continue;
			int col = lineSymbol[row].rarity.color;
			float pulse = 0.6f + 0.4f * (float) Math.sin(t * 9f + row);
			float y = rowY(row);
			Gfx.texCentered(g, T_GLOW, MX + 40f, y, 30, 30, 0f, Gfx.argb(col, 0.9f * pulse));
			Gfx.texCentered(g, T_GLOW, MX + 380f, y, 30, 30, 0f, Gfx.argb(col, 0.9f * pulse));
		}
	}

	private void drawReel(GuiGraphicsExtractor g, int i) {
		Reel r = reels[i];
		float pos = r.pos(t);
		float blur = Gfx.clamp01((Math.abs(r.speed(t)) - 2.5f) / 8f);
		boolean won = t >= tWin;
		float x = reelX(i);
		int center = Math.round(pos);
		for (int k = center - 3; k <= center + 3; k++) {
			float phi = (pos - k) * STEP;
			if (Math.abs(phi) > 1.35f) continue;
			float c = (float) Math.cos(phi);
			float y = MID_Y + REEL_R * (float) Math.sin(phi);
			PrizeDefinition prize = r.strip[Math.floorMod(k, STRIP)];
			int shade = Gfx.lerpColor(0xFF5A4A66, 0xFFFFFFFF, c);
			boolean winSymbol = won && k == r.stopIndex + 1 - winRow;
			float pulse = 1f;
			if (winSymbol) {
				float hp = Gfx.progress(t, tWin, 0.3f);
				pulse = 1f + 0.12f * (float) Math.sin((t - tWin) * 8f + i) * hp;
			} else {
				Gfx.texCentered(g, T_GLOW, x, y, 46, 46 * c, 0f, Gfx.argb(prize.rarity.color, glowAlpha(prize.rarity) * c * (1f - blur)));
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

	/**
	 * Eine Walze: Symbolstreifen und Bewegungsplan. Position in Symbolen; steht die Walze bei der ganzen Zahl p,
	 * ist Symbol p in der mittleren Reihe, p + 1 oben und p - 1 unten.
	 */
	private final class Reel {
		final PrizeDefinition[] strip = new PrizeDefinition[STRIP];
		final int posStart;
		float tStart, tDecel, decelDur, v, tStop, restPos;
		/** Near-Miss: bleibt erst eine Reihe zu früh stehen und rutscht zu diesem Zeitpunkt nach. */
		float nudgeAt = -1f;
		float settled;
		/** Endposition (Symbol in der mittleren Reihe). */
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

		/** Symbol in Reihe {@code row}, wenn die Walze bei {@code pos} steht. */
		PrizeDefinition symbolAt(int pos, int row) {
			return strip[Math.floorMod(pos + 1 - row, STRIP)];
		}

		private void set(int index, PrizeDefinition p) {
			strip[Math.floorMod(index, STRIP)] = p;
		}

		private void fillRandom(PrizeDefinition a, PrizeDefinition b) {
			for (int i = 0; i < STRIP; i++) strip[i] = anyExcept(a, b);
		}

		/** Normale Walze: Endstand wie geplant, sonst zufällig. */
		void fill(PrizeDefinition[] column) {
			fillRandom(null, null);
			for (int row = 0; row < ROWS; row++) set(stopIndex + 1 - row, column[row]);
		}

		/**
		 * Letzte Walze: Am Ende steht der Gewinn in seiner Reihe, der Köder nicht. Beim Halt knapp davor ist noch keine
		 * der beiden Linien voll. Der Köder rutscht beim Auslaufen möglichst knapp an seiner Reihe vorbei.
		 */
		void fillFinal(PrizeDefinition win, PrizeDefinition decoy) {
			int p = stopIndex;
			for (int attempt = 0; attempt < 20; attempt++) {
				fillRandom(win, decoy);
				set(p + 1 - winRow, win);
				for (int m = p - 1 - decoyRow; m >= p - 3 - decoyRow; m--) {
					if (Math.floorMod(m - (p + 1 - winRow), STRIP) == 0) continue;
					PrizeDefinition old = strip[Math.floorMod(m, STRIP)];
					set(m, decoy);
					if (finalValid(win, decoy)) break;
					set(m, old);
				}
				if (finalValid(win, decoy)) return;
			}
		}

		private boolean finalValid(PrizeDefinition win, PrizeDefinition decoy) {
			int p = stopIndex;
			return symbolAt(p, winRow) == win && symbolAt(p, decoyRow) != decoy
					&& symbolAt(p - 1, winRow) != win && symbolAt(p - 1, decoyRow) != decoy;
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
