package de.schiessbude.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Einstellungen der Mod. Gespeichert in config/schiessbude.json.
 * Alles, was im Einstellungsmenü fehlt (Gewinne, Erkennungs-Muster), kann direkt in der Datei bearbeitet werden.
 */
public class ModConfig {
	private static final Logger LOG = LoggerFactory.getLogger("schiessbude");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static ModConfig instance;

	// ---- Allgemein --------------------------------------------------------
	/** Animation an/aus. */
	public boolean enabled = true;
	/** Test-Modus: jedes abgebaute Erz löst eine Animation mit zufälligem Gewinn aus. */
	public boolean testModeOres = true;
	/** Welche Animation bei einem echten Fund vom Server läuft. */
	public AnimationType serverAnimation = AnimationType.SHOOTING_RANGE;
	/**
	 * Erz-Test: Teil der Block-ID -> Animation. Der erste passende Eintrag gewinnt
	 * ("copper" passt auf copper_ore und deepslate_copper_ore).
	 */
	public Map<String, AnimationType> oreAnimations = defaultOreAnimations();
	/** Erz-Test: Animation für alle Erze, die oben nicht aufgeführt sind (z. B. Smaragd, Quarz). */
	public AnimationType otherOresAnimation = AnimationType.RANDOM;
	/** Funde unterhalb dieser Seltenheit werden ohne Animation übersprungen. */
	public Rarity minRarity = Rarity.COMMON;
	/** Abspielgeschwindigkeit (1.0 = normal). */
	public float speed = 1.0f;
	/** Lautstärke der Effekte (0..1). */
	public float volume = 1.0f;
	/** Wie lange der Gewinn am Ende gezeigt wird (Sekunden bei Geschwindigkeit 1.0). */
	public float revealSeconds = 3.0f;
	/** Chat, Titel, Actionbar und Bossleiste ausblenden, solange eine Animation läuft oder wartet. */
	public boolean hideChatAndTitles = true;

	// ---- Erkennung über Server-Pakete --------------------------------------
	/** Systemnachrichten im Chat (ClientboundSystemChatPacket). */
	public boolean triggerChat = true;
	/** Text über der Hotbar (Actionbar). */
	public boolean triggerActionbar = true;
	/** Großer Titel (ClientboundSetTitleTextPacket). */
	public boolean triggerTitle = true;
	/** Untertitel (ClientboundSetSubtitleTextPacket). */
	public boolean triggerSubtitle = true;
	/** Regex, den eine Nachricht ZUSÄTZLICH enthalten muss (leer = egal). Schützt vor Werbung wie "Kauf Star-Tools!". */
	public String requiredPattern = "(?i)(gefunden|found|erhalten|gewonnen|bekommen)";
	/** Regex: Nachrichten, die hierauf passen, lösen nie aus (leer = keiner), z. B. Funde anderer Spieler. */
	public String ignorePattern = "";
	/** Kommen dieselben Funde gleichzeitig als Chat UND Titel, zählt das nur einmal (Millisekunden). */
	public int duplicateWindowMs = 250;
	/** Debug: jeden empfangenen Chat-/Actionbar-/Titel-Text in config/schiessbude/pakete.log schreiben. */
	public boolean logPackets = false;
	/** Das eigene Paket "schiessbude:reward" vom Server annehmen (nur Gewinn-ID und kurzer Text). */
	public boolean allowServerPayload = true;

	// ---- Geld-Drops (schwebendes goldenes Schild "+66,000 $" beim Abbauen) ----
	/** Geld-Drops erkennen: Text-Display mit Betrag in deiner Nähe. Der Betrag bestimmt den Gewinn (testMoneyMax). */
	public boolean triggerMoneyDrops = true;
	/** Nur Schilder, die höchstens so weit von dir entfernt sind (Blöcke). Drops anderer Spieler weiter weg zählen nicht. */
	public float moneyDropMaxDistance = 8f;
	/** Nur auslösen, wenn du in den letzten 5 Sekunden selbst einen Block abgebaut hast. */
	public boolean moneyDropNeedsMining = true;
	/** Die Sounds des Servers beim Geld-Drop zurückhalten und erst bei der Enthüllung abspielen (statt der eigenen). */
	public boolean moneyDropSoundAtReveal = true;

	// ---- Gewinne -----------------------------------------------------------
	public List<PrizeDefinition> prizes = defaultPrizes();

	private transient Pattern requiredCompiled;
	private transient Pattern ignoreCompiled;
	private transient boolean patternsCompiled;

	public static Map<String, AnimationType> defaultOreAnimations() {
		Map<String, AnimationType> map = new LinkedHashMap<>();
		map.put("copper", AnimationType.SHOOTING_RANGE);
		map.put("coal", AnimationType.SLOT_MACHINE);
		map.put("gold", AnimationType.ROULETTE);
		map.put("iron", AnimationType.ROULETTE);
		map.put("redstone", AnimationType.ROULETTE);
		map.put("diamond", AnimationType.ROULETTE);
		map.put("lapis", AnimationType.ROULETTE);
		return map;
	}

	public static List<PrizeDefinition> defaultPrizes() {
		String money = "([0-9][0-9.,]*\\s?\\$)";
		List<PrizeDefinition> list = new ArrayList<>();
		list.add(new PrizeDefinition("goldnugget", "Goldnugget", Rarity.COMMON,
				"schiessbude:textures/prize/goldnugget.png", "Bis zu 75.000 $ direkt aufs Konto",
				"(?i)gold\\s?-?nugget", money, 600, 75_000, List.of()));
		list.add(new PrizeDefinition("goldbarren", "Goldbarren", Rarity.UNCOMMON,
				"schiessbude:textures/prize/goldbarren.png", "Bis zu 2.500.000 $ direkt aufs Konto",
				"(?i)gold\\s?-?barren", money, 250, 2_500_000, List.of()));
		list.add(new PrizeDefinition("star_tool", "Star-Tool", Rarity.RARE,
				"schiessbude:textures/prize/star_tool.png", "Ein zufälliges Star-Tool, nur für dich",
				"(?i)star\\s?-?tool", "(?i)(star\\s?-?(?:hammer|axt|eimer|spitzhacke|schaufel|schwert|hacke|pickaxe|axe|shovel|bucket|sword|hoe))",
				100, 0, List.of("Star-Spitzhacke", "Star-Axt", "Star-Schaufel")));
		list.add(new PrizeDefinition("spawner", "Spawner", Rarity.EPIC,
				"schiessbude:textures/prize/spawner.png", "Kuh, Eisengolem, Spinne oder Zombie-Piglin",
				"(?i)spawner", "(?i)(kuh|eisengolem|spinne|zombie\\s?-?piglin)",
				40, 0, List.of("Kuh-Spawner", "Eisengolem-Spawner", "Spinnen-Spawner", "Zombie-Piglin-Spawner")));
		list.add(new PrizeDefinition("goldticket", "Goldticket", Rarity.LEGENDARY,
				"schiessbude:textures/prize/goldticket.png", "Der Hauptgewinn!",
				"(?i)gold\\s?-?ticket", "",
				10, 0, List.of()));
		for (PrizeDefinition p : list) p.variants = defaultVariants(p.id);
		return list;
	}

	/** Voreingestellte Varianten mit eigenen Bildern (Star-Tools und Spawner). */
	public static List<PrizeVariant> defaultVariants(String prizeId) {
		String tex = "schiessbude:textures/prize/";
		List<PrizeVariant> v = new ArrayList<>();
		switch (prizeId) {
			case "star_tool" -> {
				v.add(new PrizeVariant("Star-Hammer", tex + "star_hammer.png", "(?i)hammer"));
				v.add(new PrizeVariant("Star-Axt", tex + "star_axt.png", "(?i)\\b(axt|axe)\\b"));
				v.add(new PrizeVariant("Star-Eimer", tex + "star_eimer.png", "(?i)eimer|bucket"));
				v.add(new PrizeVariant("Star-Spitzhacke", tex + "star_spitzhacke.png", "(?i)spitz\\s?-?hacke|pick\\s?-?axe|picke"));
				v.add(new PrizeVariant("Star-Schaufel", tex + "star_schaufel.png", "(?i)schaufel|shovel|spaten"));
			}
			case "spawner" -> {
				v.add(new PrizeVariant("Kuh-Spawner", tex + "spawner_kuh.png", "(?i)kuh|\\bcow"));
				v.add(new PrizeVariant("Eisengolem-Spawner", tex + "spawner_eisengolem.png", "(?i)golem"));
				v.add(new PrizeVariant("Spinnen-Spawner", tex + "spawner_spinne.png", "(?i)spinne|spider"));
				v.add(new PrizeVariant("Zombie-Piglin-Spawner", tex + "spawner_zombie_piglin.png", "(?i)piglin"));
			}
			default -> {
			}
		}
		return v;
	}

	public static ModConfig get() {
		if (instance == null) load();
		return instance;
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("schiessbude.json");
	}

	public static Path dataDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("schiessbude");
	}

	public static void load() {
		Path path = file();
		ModConfig cfg = null;
		if (Files.exists(path)) {
			try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				cfg = GSON.fromJson(r, ModConfig.class);
			} catch (Exception e) {
				LOG.error("Konnte {} nicht lesen - verwende Standardwerte", path, e);
			}
		}
		if (cfg == null) cfg = new ModConfig();
		cfg.sanitize();
		instance = cfg;
		save();
	}

	public static void save() {
		if (instance == null) return;
		instance.patternsCompiled = false;
		try {
			Files.createDirectories(file().getParent());
			try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
				GSON.toJson(instance, w);
			}
		} catch (IOException e) {
			LOG.error("Konnte Konfiguration nicht speichern", e);
		}
	}

	private void sanitize() {
		if (minRarity == null) minRarity = Rarity.COMMON;
		if (serverAnimation == null) serverAnimation = AnimationType.SHOOTING_RANGE;
		if (otherOresAnimation == null) otherOresAnimation = AnimationType.RANDOM;
		if (oreAnimations == null) oreAnimations = defaultOreAnimations();
		oreAnimations.entrySet().removeIf(e -> e.getKey() == null || e.getKey().isBlank() || e.getValue() == null);
		speed = clamp(speed <= 0 ? 1f : speed, 0.25f, 4f);
		volume = clamp(volume, 0f, 1f);
		revealSeconds = clamp(revealSeconds <= 0 ? 3f : revealSeconds, 0.5f, 15f);
		if (duplicateWindowMs < 0) duplicateWindowMs = 0;
		moneyDropMaxDistance = clamp(moneyDropMaxDistance <= 0 ? 8f : moneyDropMaxDistance, 1f, 64f);
		if (prizes == null || prizes.isEmpty()) prizes = defaultPrizes();
		prizes.removeIf(p -> p == null || p.id == null);
		for (PrizeDefinition p : prizes) {
			if (p.rarity == null) p.rarity = Rarity.COMMON;
			if (p.name == null) p.name = p.id;
			if (p.testDetails == null) p.testDetails = new ArrayList<>();
			if (p.testWeight < 0) p.testWeight = 0;
			// Ältere Konfigurationen: Varianten (Bilder der Star-Tools und Spawner) ergänzen
			if (p.variants == null || p.variants.isEmpty()) p.variants = defaultVariants(p.id);
			p.variants.removeIf(v -> v == null || v.name == null || v.name.isBlank());
		}
		if (prizes.isEmpty()) prizes = defaultPrizes();
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}

	// ---- Hilfsfunktionen ----------------------------------------------------

	/**
	 * Gewinn für einen Geld-Drop: der Geld-Gewinn (testMoneyMax > 0) mit dem kleinsten Höchstbetrag, der noch passt,
	 * z. B. bis 75.000 $ Goldnugget, darüber Goldbarren. Bei mehr als allen Höchstbeträgen der größte.
	 */
	public PrizeDefinition prizeForMoney(long amount) {
		PrizeDefinition best = null, largest = null;
		for (PrizeDefinition p : prizes) {
			if (p.testMoneyMax <= 0) continue;
			if (largest == null || p.testMoneyMax > largest.testMoneyMax) largest = p;
			if (amount <= p.testMoneyMax && (best == null || p.testMoneyMax < best.testMoneyMax)) best = p;
		}
		if (best != null) return best;
		if (largest != null) return largest;
		PrizeDefinition nugget = findPrize("goldnugget");
		return nugget != null ? nugget : prizes.getFirst();
	}

	public boolean passesFilters(String plainText) {
		if (!patternsCompiled) {
			requiredCompiled = PrizeDefinition.safeCompile(requiredPattern);
			ignoreCompiled = PrizeDefinition.safeCompile(ignorePattern);
			patternsCompiled = true;
			for (PrizeDefinition p : prizes) p.invalidate();
		}
		if (requiredCompiled != null && !requiredCompiled.matcher(plainText).find()) return false;
		return ignoreCompiled == null || !ignoreCompiled.matcher(plainText).find();
	}

	/** Erz-Test: Animation für einen Block (z. B. "deepslate_coal_ore" -> Slotmaschine). */
	public AnimationType animationForOre(String blockPath) {
		String path = blockPath.toLowerCase(Locale.ROOT);
		for (Map.Entry<String, AnimationType> e : oreAnimations.entrySet()) {
			if (path.contains(e.getKey().toLowerCase(Locale.ROOT))) return e.getValue();
		}
		return otherOresAnimation;
	}

	public PrizeDefinition findPrize(String id) {
		for (PrizeDefinition p : prizes) {
			if (p.id.equalsIgnoreCase(id)) return p;
		}
		return null;
	}

	/** Zufälliger Gewinn nach Gewichtung (für Test-Modus). */
	public PrizeDefinition randomPrize(Random random) {
		int total = 0;
		for (PrizeDefinition p : prizes) total += Math.max(0, p.testWeight);
		if (total <= 0) return prizes.get(random.nextInt(prizes.size()));
		int r = random.nextInt(total);
		for (PrizeDefinition p : prizes) {
			r -= Math.max(0, p.testWeight);
			if (r < 0) return p;
		}
		return prizes.getLast();
	}
}
