package de.schiessbude.anim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;

final class Sounds {
	private Sounds() {
	}

	static void play(SoundEvent sound, float pitch, float volume) {
		if (volume <= 0.001f) return;
		Minecraft mc = Minecraft.getInstance();
		mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, Math.max(0.5f, Math.min(2.0f, pitch)), Math.min(1f, volume)));
	}
}
