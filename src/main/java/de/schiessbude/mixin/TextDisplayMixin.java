package de.schiessbude.mixin;

import de.schiessbude.trigger.MoneyDrops;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Meldet, wenn ein schwebendes Text-Schild (Text-Display) seinen Text bekommt. So erkennt die Mod Geld-Drops
 * ("+66,000 $"), die der Server nur als Schild über dem abgebauten Block zeigt. Nur lesen, nichts verändern.
 */
@Mixin(Display.TextDisplay.class)
public abstract class TextDisplayMixin {
	@Shadow
	@Final
	private static EntityDataAccessor<Component> DATA_TEXT_ID;

	@Shadow
	private Component getText() {
		return null;
	}

	@Inject(method = "onSyncedDataUpdated", at = @At("TAIL"))
	private void schiessbude$onTextChanged(EntityDataAccessor<?> key, CallbackInfo ci) {
		if (DATA_TEXT_ID.equals(key)) MoneyDrops.onTextDisplay((Display.TextDisplay) (Object) this, getText());
	}
}
