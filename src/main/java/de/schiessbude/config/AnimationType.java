package de.schiessbude.config;

import java.util.Locale;
import java.util.Random;

/** Welche Animation abgespielt wird. */
public enum AnimationType {
	SHOOTING_RANGE("Schießstand", "schiessstand"),
	SLOT_MACHINE("Slotmaschine", "slot"),
	ROULETTE("Roulette", "roulette"),
	/** Bei jedem Fund eine der drei Animationen. */
	RANDOM("Zufall", "zufall");

	public final String displayName;
	/** Kurzname für Befehle, z. B. {@code /schiessbude test slot}. */
	public final String command;

	AnimationType(String displayName, String command) {
		this.displayName = displayName;
		this.command = command;
	}

	/** Löst {@link #RANDOM} in eine echte Animation auf. */
	public AnimationType resolve(Random random) {
		if (this != RANDOM) return this;
		return values()[random.nextInt(values().length - 1)];
	}

	/** Akzeptiert den Kurznamen ("slot"), den Anzeigenamen oder den Enum-Namen. */
	public static AnimationType parse(String text) {
		if (text == null) return null;
		String s = text.trim().toLowerCase(Locale.ROOT);
		for (AnimationType type : values()) {
			if (type.command.equals(s) || type.name().toLowerCase(Locale.ROOT).equals(s)
					|| type.displayName.toLowerCase(Locale.ROOT).equals(s)) {
				return type;
			}
		}
		return null;
	}
}
