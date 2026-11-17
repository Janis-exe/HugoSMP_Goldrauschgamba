package de.schiessbude.mixin;

import de.schiessbude.trigger.MoneyDrops;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hält die eigenen Sounds des Servers beim Geld-Drop kurz zurück, damit sie erst bei der Enthüllung in der Animation
 * laufen (siehe MoneyDrops). Alle anderen Sounds laufen unverändert.
 */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void schiessbude$holdBack(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		if (MoneyDrops.interceptSound(sound)) cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
	}

	@Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
	private void schiessbude$holdBackDelayed(SoundInstance sound, int delay, CallbackInfo ci) {
		if (MoneyDrops.interceptSound(sound)) ci.cancel();
	}
}
