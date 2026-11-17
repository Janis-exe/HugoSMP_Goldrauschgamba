package de.schiessbude.anim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

/** Kleine Zeichen-Helfer für die Animation. Alle Koordinaten sind float (Pose-Transformation). */
public final class Gfx {
	/** Nominale Texturgröße: UVs sind normalisiert, daher funktioniert jede echte Bildgröße. */
	private static final int N = 256;

	/** Globale Deckkraft (für Ein-/Ausblenden der ganzen Animation). */
	public static float alpha = 1f;

	private Gfx() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("schiessbude", path);
	}

	public static int argb(int rgbOrArgb, float a) {
		int baseA = (rgbOrArgb >>> 24);
		if (baseA == 0) baseA = 255;
		int aa = Math.round(baseA * clamp01(a) * clamp01(alpha));
		return (aa << 24) | (rgbOrArgb & 0xFFFFFF);
	}

	public static int lerpColor(int c1, int c2, float t) {
		t = clamp01(t);
		int a = (int) (((c1 >>> 24) & 255) + (((c2 >>> 24) & 255) - ((c1 >>> 24) & 255)) * t);
		int r = (int) (((c1 >> 16) & 255) + (((c2 >> 16) & 255) - ((c1 >> 16) & 255)) * t);
		int g = (int) (((c1 >> 8) & 255) + (((c2 >> 8) & 255) - ((c1 >> 8) & 255)) * t);
		int b = (int) ((c1 & 255) + ((c2 & 255) - (c1 & 255)) * t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	/** Zeichnet eine Textur in das Rechteck (x, y, w, h). */
	public static void tex(GuiGraphicsExtractor g, Identifier tex, float x, float y, float w, float h, int color) {
		if (w <= 0.01f || h <= 0.01f || (color >>> 24) == 0) return;
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(x, y);
		p.scale(w / N, h / N);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0f, 0f, N, N, N, N, N, N, color);
		p.popMatrix();
	}

	/** Zeichnet eine Textur zentriert auf (cx, cy), gedreht um den Mittelpunkt. */
	public static void texCentered(GuiGraphicsExtractor g, Identifier tex, float cx, float cy, float w, float h, float angle, int color) {
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(cx, cy);
		if (angle != 0f) p.rotate(angle);
		tex(g, tex, -w / 2f, -h / 2f, w, h, color);
		p.popMatrix();
	}

	/** Gefülltes Rechteck mit float-Koordinaten. */
	public static void rect(GuiGraphicsExtractor g, float x, float y, float w, float h, int color) {
		if (w <= 0f || h <= 0f || (color >>> 24) == 0) return;
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(x, y);
		p.scale(w / 64f, h / 64f);
		g.fill(0, 0, 64, 64, color);
		p.popMatrix();
	}

	/** Linie als gedrehtes Rechteck. */
	public static void line(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, float width, int color) {
		float dx = x1 - x0, dy = y1 - y0;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 0.01f) return;
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(x0, y0);
		p.rotate((float) Math.atan2(dy, dx));
		rect(g, 0, -width / 2f, len, width, color);
		p.popMatrix();
	}

	/** Item (Platzhalter-Fallback) zentriert in Größe size. */
	public static void item(GuiGraphicsExtractor g, ItemStack stack, float cx, float cy, float size) {
		if (alpha < 0.5f) return; // Items können nicht transparent gezeichnet werden
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(cx - size / 2f, cy - size / 2f);
		p.scale(size / 16f, size / 16f);
		g.fakeItem(stack, 0, 0);
		p.popMatrix();
	}

	/** Text mit dicker Kontur (Comic-Stil), zentriert auf (cx, cy). */
	public static void outlinedText(GuiGraphicsExtractor g, String text, float cx, float cy, float scale, int color, int outline, float a) {
		if (text == null || text.isEmpty() || a <= 0.02f) return;
		Font font = Minecraft.getInstance().font;
		int w = font.width(text);
		int c = argb(color, a);
		int o = argb(outline, a);
		if ((c >>> 24) < 5) return;
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(cx, cy);
		p.scale(scale, scale);
		int x = -w / 2;
		int y = -4;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				if (dx != 0 || dy != 0) g.text(font, text, x + dx, y + dy, o, false);
			}
		}
		g.text(font, text, x, y + 2, o, false);
		g.text(font, text, x, y, c, false);
		p.popMatrix();
	}

	/** Einfacher Text mit Schatten. */
	public static void text(GuiGraphicsExtractor g, String text, float x, float y, float scale, int color, float a) {
		int c = argb(color, a);
		if ((c >>> 24) < 5) return;
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(x, y);
		p.scale(scale, scale);
		g.text(Minecraft.getInstance().font, text, 0, 0, c, true);
		p.popMatrix();
	}

	public static int textWidth(String text) {
		return Minecraft.getInstance().font.width(text);
	}

	// ---- Easing --------------------------------------------------------------
	public static float clamp01(float v) {
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}

	public static float progress(float t, float start, float duration) {
		return clamp01((t - start) / duration);
	}

	public static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	public static float smooth(float t) {
		t = clamp01(t);
		return t * t * (3f - 2f * t);
	}

	public static float easeOutCubic(float t) {
		t = clamp01(t);
		float u = 1f - t;
		return 1f - u * u * u;
	}

	public static float easeInCubic(float t) {
		t = clamp01(t);
		return t * t * t;
	}

	public static float easeInOutCubic(float t) {
		t = clamp01(t);
		return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
	}

	public static float easeOutBack(float t) {
		t = clamp01(t);
		float c1 = 1.70158f, c3 = c1 + 1f;
		float u = t - 1f;
		return 1f + c3 * u * u * u + c1 * u * u;
	}

	public static float easeOutElastic(float t) {
		t = clamp01(t);
		if (t == 0f || t == 1f) return t;
		return (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1);
	}
}
