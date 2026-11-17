package de.schiessbude.gui;

import de.schiessbude.SchiessbudeClient;
import de.schiessbude.anim.AnimationManager;
import de.schiessbude.anim.PrizeIcon;
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

	@Override
	protected void init() {
		ModConfig cfg = ModConfig.get();
		int colW = 155, gap = 10, rowH = 24;
		int left = this.width / 2 - colW - gap / 2;
		int right = this.width / 2 + gap / 2;
		int y = Math.max(36, this.height / 2 - 112);

		// Zeile 1
		addRenderableWidget(CycleButton.onOffBuilder(cfg.enabled)
				.withTooltip(v -> Tooltip.create(Component.literal("Schaltet die Schießbuden-Animation komplett an oder aus.")))
				.create(left, y, colW, 20, Component.literal("Animation"), (b, v) -> {
					cfg.enabled = v;
					if (!v) AnimationManager.clear();
					ModConfig.save();
				}));
		addRenderableWidget(CycleButton.onOffBuilder(cfg.testModeOres)
				.withTooltip(v -> Tooltip.create(Component.literal("Zum Testen: Jedes abgebaute Erz startet eine Animation mit zufälligem Gewinn.")))
				.create(right, y, colW, 20, Component.literal("Test: Erz abbauen"), (b, v) -> {
					cfg.testModeOres = v;
					ModConfig.save();
				}));
		y += rowH;

		// Zeile 2
		addRenderableWidget(CycleButton.<Rarity>builder(r -> Component.literal(r.displayName).withColor(r.color & 0xFFFFFF), cfg.minRarity)
				.withValues(Rarity.values())
				.withTooltip(v -> Tooltip.create(Component.literal("Funde unterhalb dieser Seltenheit werden ohne Animation übersprungen.")))
				.create(left, y, colW, 20, Component.literal("Ab Seltenheit"), (b, v) -> {
					cfg.minRarity = v;
					ModConfig.save();
				}));
		addRenderableWidget(new Slider(right, y, colW, "Tempo", 0.5, 3.0, cfg.speed,
				v -> String.format(Locale.GERMAN, "%.2fx", v), v -> {
			cfg.speed = (float) v;
			ModConfig.save();
		}));
		y += rowH;

		// Zeile 3
		addRenderableWidget(new Slider(left, y, colW, "Lautstärke", 0.0, 1.0, cfg.volume,
				v -> Math.round(v * 100) + " %", v -> {
			cfg.volume = (float) v;
			ModConfig.save();
		}));
		addRenderableWidget(new Slider(right, y, colW, "Gewinn-Anzeige", 1.0, 8.0, cfg.revealSeconds,
				v -> String.format(Locale.GERMAN, "%.1f s", v), v -> {
			cfg.revealSeconds = (float) v;
			ModConfig.save();
		}));
		y += rowH;

		// Zeile 4
		addRenderableWidget(CycleButton.onOffBuilder(cfg.hideChatAndTitles)
				.withTooltip(v -> Tooltip.create(Component.literal("Versteckt Chat, Titel, Actionbar und Bossleiste während der Animation, damit der Gewinn nicht verraten wird.")))
				.create(left, y, colW, 20, Component.literal("Chat verdecken"), (b, v) -> {
					cfg.hideChatAndTitles = v;
					ModConfig.save();
				}));
		addRenderableWidget(CycleButton.onOffBuilder(cfg.logPackets)
				.withTooltip(v -> Tooltip.create(Component.literal("Schreibt jeden empfangenen Chat-/Actionbar-/Titel-Text nach config/schiessbude/pakete.log. Damit findest du heraus, welche Nachricht der Server bei einem Fund schickt.")))
				.create(right, y, colW, 20, Component.literal("Pakete loggen"), (b, v) -> {
					cfg.logPackets = v;
					ModConfig.save();
				}));
		y += rowH;

		// Zeile 5: Erkennung
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
		y += rowH + 8;

		// Zeile 6: Aktionen
		addRenderableWidget(Button.builder(Component.literal("▶ Animation testen"), b -> testAnimation())
				.bounds(left, y, colW, 20)
				.tooltip(Tooltip.create(Component.literal("Spielt sofort eine Animation mit zufälligem Gewinn ab.")))
				.build());
		clearButton = addRenderableWidget(Button.builder(Component.literal(""), b -> {
					AnimationManager.clear();
					updateClearButton();
				})
				.bounds(right, y, colW, 20)
				.build());
		updateClearButton();
		y += rowH;

		addRenderableWidget(Button.builder(Component.literal("Konfig neu laden"), b -> {
					ModConfig.load();
					PrizeIcon.clearCache();
					rebuildWidgets();
				})
				.bounds(left, y, colW, 20)
				.tooltip(Tooltip.create(Component.literal("Liest config/schiessbude.json neu ein (Gewinne, Erkennungs-Muster).")))
				.build());
		addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> onClose())
				.bounds(right, y, colW, 20)
				.build());
	}

	private void updateClearButton() {
		int n = AnimationManager.queuedCount() + (AnimationManager.isActive() ? 1 : 0);
		clearButton.setMessage(Component.literal("Warteschlange leeren (" + n + ")"));
		clearButton.active = n > 0;
	}

	private void testAnimation() {
		if (this.minecraft.level != null) {
			onClose();
			SchiessbudeClient.enqueueTestReward("Test-Knopf");
		} else {
			this.minecraft.gui.setScreen(new PreviewScreen(this));
		}
	}

	@Override
	public void tick() {
		if (clearButton != null) updateClearButton();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, Math.max(12, this.height / 2 - 134), 0xFFFFC93C);
		graphics.centeredText(this.font, Component.literal("Gewinne & Erkennungs-Muster: config/schiessbude.json"),
				this.width / 2, this.height - 22, 0xFFA0A0A0);
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
