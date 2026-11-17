package de.schiessbude.config;

import java.util.regex.Pattern;

/**
 * Eine Variante eines Gewinns mit eigenem Bild, z. B. "Star-Axt" beim Star-Tool oder "Kuh-Spawner" beim Spawner.
 * Steht in config/schiessbude.json unter prizes[].variants und kann dort angepasst werden.
 */
public class PrizeVariant {
	/** Anzeigename, z. B. "Star-Axt". */
	public String name = "";
	/** Bild: Textur ("schiessbude:textures/prize/star_axt.png") oder Item-ID. Leer = Bild des Gewinns. */
	public String icon = "";
	/** Regex: erkennt die Variante in der Server-Nachricht, z. B. "(?i)\\b(axt|axe)\\b". */
	public String pattern = "";

	private transient Pattern compiled;
	private transient boolean done;

	public PrizeVariant() {
	}

	public PrizeVariant(String name, String icon, String pattern) {
		this.name = name;
		this.icon = icon;
		this.pattern = pattern;
	}

	public boolean matches(String text) {
		if (text == null) return false;
		if (!done) {
			done = true;
			compiled = PrizeDefinition.safeCompile(pattern);
		}
		return compiled != null && compiled.matcher(text).find();
	}

	void invalidate() {
		done = false;
	}
}
