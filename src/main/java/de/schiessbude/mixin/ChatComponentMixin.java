package de.schiessbude.mixin;

import de.schiessbude.anim.AnimationManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Versteckt den Chat (auch im geöffneten Chatfenster), solange eine Animation läuft - kein Spoiler. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	@Inject(
			method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void schiessbude$hideChat(GuiGraphicsExtractor graphics, Font font, int ticks, int mouseX, int mouseY,
			ChatComponent.DisplayMode displayMode, boolean changeCursorOnInsertions, CallbackInfo ci) {
		if (AnimationManager.shouldHideVanillaText()) ci.cancel();
	}
}
