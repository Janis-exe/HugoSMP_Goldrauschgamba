package de.schiessbude.config;

/**
 * Ein konkreter Fund, der animiert werden soll.
 *
 * @param prize     welcher Gewinn
 * @param detail    z. B. "+12.345 $" oder "Eisengolem" (darf null sein)
 * @param source    woher der Fund kam (Chat, Titel, Test, ...)
 * @param animation welche Animation (null = Einstellung "Animation bei Server-Fund")
 */
public record Reward(PrizeDefinition prize, String detail, String source, AnimationType animation) {
	public Reward(PrizeDefinition prize, String detail, String source) {
		this(prize, detail, source, null);
	}

	public String detailOrDescription() {
		if (detail != null && !detail.isBlank()) return detail;
		return prize.description == null ? "" : prize.description;
	}
}
