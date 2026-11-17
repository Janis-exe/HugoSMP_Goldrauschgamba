package de.schiessbude.mixin;

import de.schiessbude.trigger.PacketTriggers;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Liest Titel und Untertitel, die der Server anzeigt (für die Fund-Erkennung). Nur lesen, nichts verändern.
 * Direkt am HUD statt über Fabric-API-Ereignisse, damit die Mod auch mit älterer Fabric API (ab 0.161) läuft.
 */
@Mixin(Hud.class)
public abstract class HudTitleMixin {
	@Inject(method = "setTitle", at = @At("HEAD"))
	private void schiessbude$onTitle(Component title, CallbackInfo ci) {
		PacketTriggers.onText(title, PacketTriggers.Source.TITLE);
	}

	@Inject(method = "setSubtitle", at = @At("HEAD"))
	private void schiessbude$onSubtitle(Component subtitle, CallbackInfo ci) {
		PacketTriggers.onText(subtitle, PacketTriggers.Source.SUBTITLE);
	}
}
