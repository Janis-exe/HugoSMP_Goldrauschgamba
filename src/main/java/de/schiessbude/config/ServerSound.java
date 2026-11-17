package de.schiessbude.config;

import net.minecraft.client.resources.sounds.SoundInstance;

/**
 * Ein Sound des Servers, der beim Geld-Drop zurückgehalten wurde und erst bei der Enthüllung abgespielt wird.
 *
 * @param sound der ursprüngliche Sound (noch nie abgespielt)
 * @param delay Abstand zum ersten zurückgehaltenen Sound in Sekunden (damit die Reihenfolge erhalten bleibt)
 */
public record ServerSound(SoundInstance sound, float delay) {
}
