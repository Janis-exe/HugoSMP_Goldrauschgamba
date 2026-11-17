package de.schiessbude.gui;

import de.schiessbude.SchiessbudeClient;
import de.schiessbude.anim.AnimationManager;
import de.schiessbude.anim.PrizeIcon;
import de.schiessbude.config.AnimationType;
import de.schiessbude.config.ModConfig;
import de.schiessbude.config.Rarity;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/** Einstellungsmenü (Taste K, /schiessbude oder über Mod Menu). */
public class SettingsScreen extends Screen {
	private final Screen parent;
	private Button clearButton;

	public SettingsScreen(Screen parent) {
		super(Component.literal("Goldrausch Schießbude – Einstellungen"));
		this.parent = parent;
	}

	private int titleY, footerY;

	@Override
	protected void init() {
		ModConfig cfg = ModConfig.get();
		int colW = 155, gap = 10;
		int rowH = this.height >= 280 ? 24 : 22;
		int total = 7 * rowH + 6 + 20;
		int left = this.width / 2 - colW - gap / 2;
		int right = this.width / 2 + gap / 2;
		int y = Math.max(28, (this.height - total) / 2 + 4);
		titleY = y - 16;
		footerY = y + total + 8 <= this.height - 12 ? this.height - 16 : -1;

		// Zeile 1
		addRenderableWidget(CycleButton.onOffBuilder(cfg.enabled)
				.withTooltip(v -> Tooltip.create(Component.literal("Schaltet die Animationen komplett an oder aus.")))
				.create(left, y, colW, 20, Component.literal("Animation"), (b, v) -> {
					cfg.enabled = v;
					if (!v) AnimationManager.clear();
					ModConfig.save();
				}));
		addRenderableWidget(CycleButton.onOffBuilder(cfg.testModeOres)
				.withTooltip(v -> Tooltip.create(Component.literal("Zum Testen: Jedes abgebaute Erz startet eine Animation mit zufälligem Gewinn.\n"
						+ "Kupfer = Schießstand, Kohle = Slotmaschine, Gold/Eisen/Redstone/Diamant/Lapis = Roulette, andere Erze = Zufall.")))
				.create(right, y, colW, 20, Component.literal("Test: Erz abbauen"), (b, v) -> {
					cfg.testModeOres = v;
					ModConfig.save();
				}));
		y += rowH;

		// Zeile 2
		addRenderableWidget(CycleButton.<AnimationType>builder(a -> Component.literal(a.displayName), cfg.serverAnimation)
				.withValues(AnimationType.values())
				.withTooltip(v -> Tooltip.create(Component.literal("Welche Animation bei einem echten Fund vom Server läuft.")))
				.create(left, y, colW, 20, Component.literal("Server-Fund"), (b, v) -> {
					cfg.serverAnimation = v;
					ModConfig.save();
				}));
		addRenderableWidget(CycleButton.<Rarity>builder(r -> Component.literal(r.displayName).withColor(r.color & 0xFFFFFF), cfg.minRarity)
				.withValues(Rarity.values())
				.withTooltip(v -> Tooltip.create(Component.literal("Funde unterhalb dieser Seltenheit werden ohne Animation übersprungen.")))
				.create(right, y, colW, 20, Component.literal("Ab Seltenheit"), (b, v) -> {
					cfg.minRarity = v;
					ModConfig.save();
				}));
		y += rowH;

		// Zeile 3
		addRenderableWidget(new Slider(left, y, colW, "Tempo", 0.5, 3.0, cfg.speed,
				v -> String.format(Locale.GERMAN, "%.2fx", v), v -> {
			cfg.speed = (float) v;
			ModConfig.save();
		}));
		addRenderableWidget(new Slider(right, y, colW, "Lautstärke", 0.0, 1.0, cfg.volume,
				v -> Math.round(v * 100) + " %", v -> {
			cfg.volume = (float) v;
			ModConfig.save();
		}));
		y += rowH;

		// Zeile 4
		addRenderableWidget(new Slider(left, y, colW, "Gewinn-Anzeige", 1.0, 8.0, cfg.revealSeconds,
				v -> String.format(Locale.GERMAN, "%.1f s", v), v -> {
			cfg.revealSeconds = (float) v;
			ModConfig.save();
		}));
		addRenderableWidget(CycleButton.onOffBuilder(cfg.hideChatAndTitles)
				.withTooltip(v -> Tooltip.create(Component.literal("Versteckt Chat, Titel, Actionbar und Bossleiste während der Animation, damit der Gewinn nicht verraten wird.")))
				.create(right, y, colW, 20, Component.literal("Chat verdecken"), (b, v) -> {
					cfg.hideChatAndTitles = v;
					ModConfig.save();
				}));
		y += rowH;

		// Zeile 5
		addRenderableWidget(CycleButton.onOffBuilder(cfg.logPackets)
				.withTooltip(v -> Tooltip.create(Component.literal("Schreibt jeden empfangenen Chat-/Actionbar-/Titel-Text nach config/schiessbude/pakete.log. Damit findest du heraus, welche Nachricht der Server bei einem Fund schickt.")))
				.create(left, y, colW, 20, Component.literal("Pakete loggen"), (b, v) -> {
					cfg.logPackets = v;
					ModConfig.save();
				}));
		clearButton = addRenderableWidget(Button.builder(Component.literal(""), b -> {
					AnimationManager.clear();
					updateClearButton();
				})
				.bounds(right, y, colW, 20)
				.build());
		updateClearButton();
		y += rowH;

		// Zeile 6: Erkennung
		addRenderableWidget(CycleButton.onOffBuilder(cfg.triggerChat)
				.create(left, y, colW / 2 - 2, 20, Component.literal("Chat"), (b, v) -> {
					cfg.triggerChat = v;
					ModConfig.save();
				}));
		addRenderableWidget(CycleButton.onOffBuilder(cfg.triggerActionbar)
				.create(left + colW / 2 + 2, y, colW / 2 - 2, 20, Component.literal("Actionbar"), (b, v) -> {
					cfg.triggerActionbar = v;
					ModConfig.save();
				}));
		addRenderableWidget(CycleButton.onOffBuilder(cfg.triggerTitle)
				.create(right, y, colW / 2 - 2, 20, Component.literal("Titel"), (b, v) -> {
					cfg.triggerTitle = v;
					ModConfig.save();
				}));
		addRenderableWidget(CycleButton.onOffBuilder(cfg.triggerSubtitle)
				.create(right + colW / 2 + 2, y, colW / 2 - 2, 20, Component.literal("Untertitel"), (b, v) -> {
					cfg.triggerSubtitle = v;
					ModConfig.save();
				}));
		y += rowH + 6;

		// Zeile 7: Testen
		int full = 2 * colW + gap;
		int bw = (full - 2 * 4) / 3;
		AnimationType[] tests = {AnimationType.SHOOTING_RANGE, AnimationType.SLOT_MACHINE, AnimationType.ROULETTE};
		for (int i = 0; i < tests.length; i++) {
			AnimationType type = tests[i];
			addRenderableWidget(Button.builder(Component.literal("▶ " + type.displayName), b -> testAnimation(type))
					.bounds(left + i * (bw + 4), y, i == 2 ? full - 2 * (bw + 4) : bw, 20)
					.tooltip(Tooltip.create(Component.literal("Spielt sofort " + article(type) + " mit zufälligem Gewinn ab.")))
					.build());
		}
		y += rowH;

		// Zeile 8
		addRenderableWidget(Button.builder(Component.literal("Konfig neu laden"), b -> {
					ModConfig.load();
					PrizeIcon.clearCache();
					rebuildWidgets();
				})
				.bounds(left, y, colW, 20)
				.tooltip(Tooltip.create(Component.literal("Liest config/schiessbude.json neu ein (Gewinne, Erkennungs-Muster, Erz-Zuordnung).")))
				.build());
		addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> onClose())
				.bounds(right, y, colW, 20)
				.build());
	}

	private static String article(AnimationType type) {
		return switch (type) {
			case SHOOTING_RANGE -> "den Schießstand";
			case SLOT_MACHINE -> "die Slotmaschine";
			case ROULETTE -> "das Roulette";
			default -> "eine Animation";
		};
	}

	private void updateClearButton() {
		int n = AnimationManager.queuedCount() + (AnimationManager.isActive() ? 1 : 0);
		clearButton.setMessage(Component.literal("Warteschlange leeren (" + n + ")"));
		clearButton.active = n > 0;
	}

	private void testAnimation(AnimationType type) {
		if (this.minecraft.level != null) {
			onClose();
			SchiessbudeClient.enqueueTestReward("Test-Knopf", type);
		} else {
			this.minecraft.gui.setScreen(new PreviewScreen(this, type));
		}
	}

	@Override
	public void tick() {
		if (clearButton != null) updateClearButton();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, Math.max(4, titleY), 0xFFFFC93C);
		if (footerY > 0) {
			graphics.centeredText(this.font, Component.literal("Gewinne, Erkennung & Erz-Zuordnung: config/schiessbude.json"),
					this.width / 2, footerY, 0xFFA0A0A0);
		}
	}

	@Override
	public void onClose() {
		ModConfig.save();
		this.minecraft.gui.setScreen(parent);
	}

	/** Einfacher Schieberegler mit Wertebereich. */
	private static final class Slider extends AbstractSliderButton {
		private final String label;
		private final double min, max;
		private final DoubleFunction<String> format;
		private final DoubleConsumer onChange;

		Slider(int x, int y, int w, String label, double min, double max, double value,
				DoubleFunction<String> format, DoubleConsumer onChange) {
			super(x, y, w, 20, Component.empty(), (value - min) / (max - min));
			this.label = label;
			this.min = min;
			this.max = max;
			this.format = format;
			this.onChange = onChange;
			updateMessage();
		}

		private double real() {
			return min + (max - min) * this.value;
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(label + ": " + format.apply(real())));
		}

		@Override
		protected void applyValue() {
			onChange.accept(real());
		}
	}
}
