package de.schiessbude.config;

/** Seltenheitsstufen wie im Goldrausch-Menü des Servers. */
public enum Rarity {
	COMMON("Gewöhnlich", 0xFFE3E3E3),
	UNCOMMON("Ungewöhnlich", 0xFF4CDB5A),
	RARE("Selten", 0xFF3FA7FF),
	EPIC("Episch", 0xFFB060FF),
	LEGENDARY("Legendär", 0xFFFFA62B);

	public final String displayName;
	/** ARGB-Farbe der Seltenheit. */
	public final int color;

	Rarity(String displayName, int color) {
		this.displayName = displayName;
		this.color = color;
	}

	public boolean atLeast(Rarity other) {
		return this.ordinal() >= other.ordinal();
	}
}
