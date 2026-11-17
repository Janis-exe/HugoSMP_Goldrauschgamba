package de.schiessbude.config;

import java.util.List;

/**
 * Ein konkreter Fund, der animiert werden soll.
 *
 * @param prize        welcher Gewinn
 * @param detail       z. B. "+12.345 $" oder "Star-Axt" (darf null sein)
 * @param source       woher der Fund kam (Chat, Titel, Test, ...)
 * @param animation    welche Animation (null = Einstellung "Animation bei Server-Fund")
 * @param variant      Variante mit eigenem Bild, z. B. Star-Axt (darf null sein)
 * @param revealSounds Sounds des Servers, die zurückgehalten wurden und erst bei der Enthüllung laufen (oder null)
 */
public record Reward(PrizeDefinition prize, String detail, String source, AnimationType animation, PrizeVariant variant,
		List<ServerSound> revealSounds) {
	public Reward {
		// Ohne eigenes Detail zeigt die Enthüllung den Namen der Variante ("Star-Axt")
		if ((detail == null || detail.isBlank()) && variant != null) detail = variant.name;
	}

	/** Variante wird aus dem Detail-Text erkannt. */
	public Reward(PrizeDefinition prize, String detail, String source) {
		this(prize, detail, source, null);
	}

	public Reward(PrizeDefinition prize, String detail, String source, AnimationType animation) {
		this(prize, detail, source, animation, prize.findVariant(detail), null);
	}

	public String detailOrDescription() {
		if (detail != null && !detail.isBlank()) return detail;
		return prize.description == null ? "" : prize.description;
	}
}
