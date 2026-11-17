package de.schiessbude.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Ein möglicher Gewinn. Alle Felder stehen in config/schiessbude.json und können dort angepasst werden.
 */
public class PrizeDefinition {
	/** Eindeutige ID (auch für das Server-Paket "schiessbude:reward"). */
	public String id = "platzhalter";
	/** Anzeigename in der Animation. */
	public String name = "Platzhalter";
	public Rarity rarity = Rarity.COMMON;
	/**
	 * Bild des Gewinns: entweder eine Textur ("schiessbude:textures/prize/goldnugget.png")
	 * oder eine Item-ID ("minecraft:gold_nugget").
	 */
	public String icon = "minecraft:barrier";
	/** Beschreibung, wenn aus der Nachricht kein Detail (z. B. Betrag) gelesen werden konnte. */
	public String description = "";
	/** Regex: erkennt diesen Gewinn in Chat/Actionbar/Title-Paketen (Groß-/Kleinschreibung egal mit (?i)). */
	public String pattern = "";
	/** Optionaler Regex: Gruppe 1 (oder der ganze Treffer) wird als Detail angezeigt, z. B. der Betrag. */
	public String detailPattern = "";
	/** Gewichtung für den Test-Modus und für die Ziel-Verteilung. */
	public int testWeight = 1;
	/** Test-Modus: zufälliger Geldbetrag bis zu diesem Wert (0 = keiner). */
	public long testMoneyMax = 0;
	/** Test-Modus: zufällige Details (z. B. Spawner-Typen). */
	public List<String> testDetails = new ArrayList<>();

	private transient Pattern compiledPattern;
	private transient Pattern compiledDetail;
	private transient boolean compiled;

	public PrizeDefinition() {
	}

	public PrizeDefinition(String id, String name, Rarity rarity, String icon, String description, String pattern,
			String detailPattern, int testWeight, long testMoneyMax, List<String> testDetails) {
		this.id = id;
		this.name = name;
		this.rarity = rarity;
		this.icon = icon;
		this.description = description;
		this.pattern = pattern;
		this.detailPattern = detailPattern;
		this.testWeight = testWeight;
		this.testMoneyMax = testMoneyMax;
		this.testDetails = new ArrayList<>(testDetails);
	}

	private void compile() {
		if (compiled) return;
		compiled = true;
		compiledPattern = safeCompile(pattern);
		compiledDetail = safeCompile(detailPattern);
	}

	static Pattern safeCompile(String regex) {
		if (regex == null || regex.isBlank()) return null;
		try {
			return Pattern.compile(regex);
		} catch (PatternSyntaxException e) {
			org.slf4j.LoggerFactory.getLogger("schiessbude").warn("Ungültiger Regex in der Konfiguration: {} ({})", regex, e.getDescription());
			return null;
		}
	}

	public boolean matches(String plainText) {
		compile();
		return compiledPattern != null && compiledPattern.matcher(plainText).find();
	}

	/** Liest das Detail (z. B. "12.345 $") aus der Nachricht, oder null. */
	public String extractDetail(String plainText) {
		compile();
		if (compiledDetail == null) return null;
		Matcher m = compiledDetail.matcher(plainText);
		if (!m.find()) return null;
		String s = m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group();
		s = s.trim();
		return s.isEmpty() ? null : s;
	}

	/** Erzeugt ein zufälliges Detail für den Test-Modus. */
	public String randomTestDetail(Random random) {
		if (testMoneyMax > 0) {
			long min = Math.max(1, testMoneyMax / 50);
			long value = min + (long) (Math.pow(random.nextDouble(), 2.2) * (testMoneyMax - min));
			return "+" + String.format(Locale.GERMANY, "%,d", value) + " $";
		}
		if (testDetails != null && !testDetails.isEmpty()) {
			return testDetails.get(random.nextInt(testDetails.size()));
		}
		return null;
	}

	public void invalidate() {
		compiled = false;
	}
}
