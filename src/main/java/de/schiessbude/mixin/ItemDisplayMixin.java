package de.schiessbude.mixin;

import de.schiessbude.trigger.Drops;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Meldet, wenn ein schwebendes Item-Display ein Item bekommt. Beim Geld-Drop erscheint zuerst ein goldenes Nugget,
 * direkt danach spielt der Server seine Sounds: So weiß die Mod rechtzeitig, welche Sounds sie zurückhalten soll.
 * Nur lesen, nichts verändern.
 */
@Mixin(Display.ItemDisplay.class)
public abstract class ItemDisplayMixin {
	@Shadow
	@Final
	private static EntityDataAccessor<ItemStack> DATA_ITEM_STACK_ID;

	@Shadow
	private ItemStack getItemStack() {
		return null;
	}

	@Inject(method = "onSyncedDataUpdated", at = @At("TAIL"))
	private void schiessbude$onItemChanged(EntityDataAccessor<?> key, CallbackInfo ci) {
		if (DATA_ITEM_STACK_ID.equals(key)) Drops.onItemDisplay((Display.ItemDisplay) (Object) this, getItemStack());
	}
}
