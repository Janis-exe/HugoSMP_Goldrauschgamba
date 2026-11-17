package de.schiessbude.anim;

import de.schiessbude.config.PrizeDefinition;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

/**
 * Bild eines Gewinns: entweder eine Textur (Platzhalter-PNG, per Ressourcenpaket ersetzbar) oder ein Minecraft-Item.
 */
public final class PrizeIcon {
	private static final Map<String, PrizeIcon> CACHE = new HashMap<>();

	private final Identifier texture;
	private final ItemStack stack;

	private PrizeIcon(Identifier texture, ItemStack stack) {
		this.texture = texture;
		this.stack = stack;
	}

	public static PrizeIcon of(PrizeDefinition prize) {
		String key = prize.icon == null ? "" : prize.icon.trim();
		return CACHE.computeIfAbsent(key, PrizeIcon::parse);
	}

	public static void clearCache() {
		CACHE.clear();
	}

	private static PrizeIcon parse(String icon) {
		if (icon.endsWith(".png")) {
			Identifier id = Identifier.tryParse(icon);
			if (id != null) return new PrizeIcon(id, null);
		}
		Identifier id = Identifier.tryParse(icon);
		Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		if (item == null || item == Items.AIR) item = Items.BARRIER;
		return new PrizeIcon(null, new ItemStack(item));
	}

	public void draw(GuiGraphicsExtractor g, float cx, float cy, float size, float angle, float alpha) {
		draw(g, cx, cy, size, size, angle, Gfx.argb(0xFFFFFFFF, alpha));
	}

	/**
	 * Zeichnet das Bild in w x h (z. B. auf einer Walze gestaucht). {@code color} färbt Texturen ein
	 * (zum Abdunkeln); Items können nicht eingefärbt werden und erscheinen nur bei genug Deckkraft.
	 */
	public void draw(GuiGraphicsExtractor g, float cx, float cy, float w, float h, float angle, int color) {
		if (texture != null) {
			Gfx.texCentered(g, texture, cx, cy, w, h, angle, color);
		} else if ((color >>> 24) > 127 && w > 0.5f && h > 0.5f) {
			var p = g.pose();
			p.pushMatrix();
			p.translate(cx, cy);
			if (angle != 0f) p.rotate(angle);
			p.scale(1f, h / w);
			Gfx.item(g, stack, 0, 0, w * 0.9f);
			p.popMatrix();
		}
	}
}
