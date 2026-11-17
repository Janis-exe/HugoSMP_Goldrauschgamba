package de.schiessbude;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.schiessbude.anim.AnimationManager;
import de.schiessbude.config.AnimationType;
import de.schiessbude.config.ModConfig;
import de.schiessbude.config.PrizeDefinition;
import de.schiessbude.config.PrizeVariant;
import de.schiessbude.config.Reward;
import de.schiessbude.gui.PreviewScreen;
import de.schiessbude.gui.SettingsScreen;
import de.schiessbude.net.RewardPayload;
import de.schiessbude.trigger.MoneyDrops;
import de.schiessbude.trigger.PacketTriggers;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

public class SchiessbudeClient implements ClientModInitializer {
	public static final String MOD_ID = "schiessbude";
	private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
	private static final Random RANDOM = new Random();

	private static KeyMapping settingsKey;
	private static KeyMapping skipKey;
	private static boolean openSettingsNextTick;
	private static String previewOnTitle = System.getProperty("schiessbude.preview");
	private static final AnimationType PREVIEW_ANIM = AnimationType.parse(System.getProperty("schiessbude.previewAnim", "zufall"));

	@Override
	public void onInitializeClient() {
		ModConfig.load();

		// ---- Tasten ----
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.schiessbude.settings", InputConstants.Type.KEYBOARD, InputConstants.KEY_K, category));
		skipKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.schiessbude.skip", InputConstants.Type.KEYBOARD, InputConstants.KEY_J, category));
		AnimationManager.skipKeyName = () -> skipKey.isUnbound() ? null : skipKey.getTranslatedKeyMessage().getString();

		// ---- HUD: Animation ganz oben zeichnen ----
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "shooting_range"), (graphics, deltaTracker) -> AnimationManager.render(graphics));
		// Titel, Actionbar und Bossleiste (obere Mitte) während der Animation verstecken - kein Spoiler
		for (Identifier element : new Identifier[]{VanillaHudElements.TITLE_AND_SUBTITLE, VanillaHudElements.OVERLAY_MESSAGE, VanillaHudElements.BOSS_BAR, VanillaHudElements.CHAT}) {
			HudElementRegistry.replaceElement(element, original -> (graphics, deltaTracker) -> {
				if (!AnimationManager.shouldHideVanillaText()) original.extractRenderState(graphics, deltaTracker);
			});
		}

		// ---- Server-Pakete: Chat (System), Actionbar, Titel, Untertitel ----
		ClientReceiveMessageEvents.GAME.register((message, overlay) ->
				PacketTriggers.onText(message, overlay ? PacketTriggers.Source.ACTIONBAR : PacketTriggers.Source.CHAT));
		// Titel und Untertitel: siehe HudTitleMixin (läuft so auch mit Fabric API 0.161)

		// ---- Optional: eigenes Paket "schiessbude:reward" ----
		PayloadTypeRegistry.clientboundPlay().register(RewardPayload.TYPE, RewardPayload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(RewardPayload.TYPE, (payload, context) -> PacketTriggers.onPayload(payload.data()));

		// ---- Test-Modus: jedes abgebaute Erz (Kupfer = Schießstand, Kohle = Slotmaschine, Gold/Eisen/... = Roulette) ----
		ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
			MoneyDrops.onOwnBlockBroken(); // Geld-Drops zählen nur direkt nach eigenem Abbau
			ModConfig cfg = ModConfig.get();
			if (!cfg.testModeOres || !isOre(state)) return;
			String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
			enqueueTestReward("Test: " + path, cfg.animationForOre(path));
		});

		// ---- Tick: Tasten, verzögertes Öffnen ----
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			AnimationManager.clear();
			MoneyDrops.reset();
		});

		// ---- Befehle: /schiessbude, /schiessbude test [animation] [gewinn], /schiessbude leeren ----
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
			LiteralArgumentBuilder<FabricClientCommandSource> test = ClientCommands.literal("test")
					.executes(ctx -> testCommand(ctx.getSource(), AnimationType.RANDOM, null))
					.then(prizeArgument(AnimationType.RANDOM));
			for (AnimationType type : AnimationType.values()) {
				test.then(ClientCommands.literal(type.command)
						.executes(ctx -> testCommand(ctx.getSource(), type, null))
						.then(prizeArgument(type)));
			}
			dispatcher.register(ClientCommands.literal("schiessbude")
					.executes(ctx -> {
						openSettingsNextTick = true;
						return 1;
					})
					.then(test)
					.then(ClientCommands.literal("leeren")
							.executes(ctx -> {
								AnimationManager.clear();
								ctx.getSource().sendFeedback(Component.literal("Warteschlange geleert."));
								return 1;
							})));
		});

		LOG.info("Goldrausch Schießbude geladen ({} Gewinne konfiguriert)", ModConfig.get().prizes.size());
	}

	private void onTick(Minecraft mc) {
		MoneyDrops.tick();
		if (openSettingsNextTick && mc.gui.screen() == null) {
			openSettingsNextTick = false;
			mc.gui.setScreen(new SettingsScreen(null));
		}
		while (settingsKey.consumeClick()) {
			if (mc.gui.screen() == null) mc.gui.setScreen(new SettingsScreen(null));
		}
		while (skipKey.consumeClick()) {
			AnimationManager.skip();
		}
		// Entwickler-Vorschau: -Dschiessbude.preview=<gewinn_id|zufall>
		if (previewOnTitle != null && mc.gui.screen() instanceof TitleScreen title) {
			String prize = previewOnTitle.equals("zufall") ? null : previewOnTitle;
			previewOnTitle = null;
			mc.gui.setScreen(new PreviewScreen(title, prize, PREVIEW_ANIM));
		}
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource, String> prizeArgument(AnimationType type) {
		return ClientCommands.argument("gewinn", StringArgumentType.word())
				.suggests((ctx, builder) -> {
					ModConfig.get().prizes.forEach(p -> builder.suggest(p.id));
					return builder.buildFuture();
				})
				.executes(ctx -> testCommand(ctx.getSource(), type, StringArgumentType.getString(ctx, "gewinn")));
	}

	private static int testCommand(FabricClientCommandSource source, AnimationType type, String prizeId) {
		if (prizeId != null && ModConfig.get().findPrize(prizeId) == null) {
			source.sendError(Component.literal("Unbekannter Gewinn: " + prizeId));
			return 0;
		}
		AnimationManager.enqueue(createTestReward("Befehl", prizeId, type));
		return 1;
	}

	private static boolean isOre(BlockState state) {
		if (state.is(BlockTags.ORES)) return true;
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return path.endsWith("_ore") || path.equals("ancient_debris");
	}

	/** Test-Fund mit zufälligem (oder vorgegebenem) Gewinn und der gewünschten Animation. */
	public static Reward createTestReward(String source, String prizeId, AnimationType type) {
		ModConfig cfg = ModConfig.get();
		PrizeDefinition prize = prizeId != null ? cfg.findPrize(prizeId) : null;
		if (prize == null) prize = cfg.randomPrize(RANDOM);
		AnimationType animation = type == null ? AnimationType.RANDOM : type;
		PrizeVariant variant = prize.randomVariant(RANDOM);
		String detail = variant != null ? variant.name : prize.randomTestDetail(RANDOM);
		return new Reward(prize, detail, source, animation, variant, null);
	}

	public static void enqueueTestReward(String source, AnimationType type) {
		AnimationManager.enqueue(createTestReward(source, null, type));
	}
}
