import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Sicherheits-Check für die Goldrausch-Schießbude-Mod: Schutz gegen "geratet werden" (RAT) über diese Mod.
 * <p>
 * Liest JEDE Klasse in der fertigen .jar Byte für Byte (Konstanten-Pool) und prüft, welche fremden Klassen und
 * Methoden sie benutzt. Erlaubt ist nur eine feste Liste (Positivliste). Alles, was ein Trojaner bräuchte
 * (Internet, Dateien außerhalb des eigenen Config-Ordners, Programme starten, Reflection, nachgeladener Code,
 * Account-/Session-Daten, Daten an den Server schicken, Zwischenablage ...), lässt den Check fehlschlagen.
 * Außerdem dürfen in der .jar nur die erwarteten Dateien liegen (keine versteckten .jar/.dll/.so/Skripte).
 * <p>
 * Aufruf (JDK 25):  java tools/SicherheitsCheck.java goldrausch-schiessbude-1.1.1.jar
 * Damit kannst du auch eine .jar prüfen, die dir jemand anderes geschickt hat.
 */
public class SicherheitsCheck {
	private static final String OWN = "de/schiessbude/";

	/** Erlaubte fremde Klassen (exakt) bzw. Pakete (endet auf '/'). */
	private static final List<String> ALLOWED = List.of(
			// Java-Grundlagen (bewusst OHNE java/lang/reflect, Class, ClassLoader, Runtime, Process, Thread ...)
			"java/lang/Object", "java/lang/String", "java/lang/StringBuilder", "java/lang/CharSequence",
			"java/lang/Enum", "java/lang/Record", "java/lang/Math", "java/lang/Number", "java/lang/Comparable",
			"java/lang/Boolean", "java/lang/Byte", "java/lang/Short", "java/lang/Integer", "java/lang/Long",
			"java/lang/Float", "java/lang/Double", "java/lang/Character", "java/lang/Iterable", "java/lang/Runnable",
			"java/lang/AutoCloseable", "java/lang/Throwable", "java/lang/Exception", "java/lang/RuntimeException",
			"java/lang/Error", "java/lang/MatchException", "java/lang/NoSuchFieldError", "java/lang/IllegalArgumentException",
			"java/lang/IllegalStateException", "java/lang/IndexOutOfBoundsException", "java/lang/NullPointerException",
			"java/lang/ArithmeticException", "java/lang/System",
			// nur die Bootstrap-Methoden, die der Compiler für Lambdas/Strings/Records/switch erzeugt (siehe MEMBERS)
			"java/lang/invoke/LambdaMetafactory", "java/lang/invoke/StringConcatFactory", "java/lang/invoke/MethodHandles",
			"java/lang/invoke/MethodHandles$Lookup", "java/lang/runtime/ObjectMethods", "java/lang/runtime/SwitchBootstraps",
			"java/util/function/", "java/util/regex/", "java/time/",
			"java/io/IOException", "java/io/Reader", "java/io/Writer", "java/io/UncheckedIOException",
			"java/nio/charset/StandardCharsets",
			"java/nio/file/", // nur in FILE_ACCESS (Konfiguration und Paket-Log), siehe unten
			// Bibliotheken, die Minecraft/Fabric mitbringen
			"com/google/gson/", "com/mojang/brigadier/", "com/mojang/blaze3d/platform/InputConstants",
			"com/mojang/blaze3d/platform/InputConstants$Type",
			"net/fabricmc/api/", "net/fabricmc/fabric/api/", "net/fabricmc/loader/api/FabricLoader",
			"net/minecraft/", "org/joml/", "org/slf4j/", "org/spongepowered/asm/mixin/",
			"com/terraformersmc/modmenu/api/"
	);

	/** java/util/ ist erlaubt, außer diesen Teilen. */
	private static final List<String> JAVA_UTIL_DENIED = List.of(
			"java/util/zip/", "java/util/jar/", "java/util/concurrent/", "java/util/spi/", "java/util/prefs/",
			"java/util/logging/", "java/util/Base64", "java/util/ServiceLoader", "java/util/Timer", "java/util/Scanner",
			"java/util/Properties"
	);

	/** Gefährliche Teile von Minecraft/Fabric (Account, Netzwerk, Server, Links öffnen ...). */
	private static final List<String> DENIED = List.of(
			"net/minecraft/client/User", "net/minecraft/client/multiplayer/", "net/minecraft/client/main/",
			"net/minecraft/client/server/", "net/minecraft/server/", "net/minecraft/network/Connection",
			"net/minecraft/network/protocol/game/", "net/minecraft/network/protocol/login/",
			"net/minecraft/network/protocol/common/Serverbound", "net/minecraft/util/HttpUtil", "net/minecraft/Util",
			"net/minecraft/util/Util", "net/minecraft/client/player/", "net/minecraft/client/KeyboardHandler",
			"net/minecraft/client/gui/screens/ConfirmLinkScreen", "net/minecraft/client/telemetry/",
			"net/minecraft/client/resources/server/", "net/minecraft/client/quickplay/",
			"net/fabricmc/fabric/api/networking/v1/PacketSender", "net/fabricmc/fabric/api/client/networking/v1/ClientLoginNetworking",
			"net/fabricmc/fabric/api/client/networking/v1/ClientConfigurationNetworking"
	);

	/** Bei diesen Klassen ist nur eine feste Liste von Methoden/Feldern erlaubt. */
	private static final Map<String, Set<String>> MEMBERS = Map.ofEntries(
			Map.entry("java/lang/System", Set.of("nanoTime", "currentTimeMillis", "getProperty", "lineSeparator", "arraycopy")),
			Map.entry("java/lang/invoke/LambdaMetafactory", Set.of("metafactory", "altMetafactory")),
			Map.entry("java/lang/invoke/StringConcatFactory", Set.of("makeConcatWithConstants", "makeConcat")),
			Map.entry("java/lang/invoke/MethodHandles", Set.of()),
			Map.entry("java/lang/invoke/MethodHandles$Lookup", Set.of()),
			Map.entry("java/lang/runtime/ObjectMethods", Set.of("bootstrap")),
			Map.entry("java/lang/runtime/SwitchBootstraps", Set.of("typeSwitch", "enumSwitch")),
			// Minecraft: nur Bildschirm, Schrift, Welt-Abfrage, Pause und Sound - KEIN getUser, getConnection, player ...
			Map.entry("net/minecraft/client/Minecraft", Set.of("getInstance", "gui", "font", "level", "isPaused", "getSoundManager")),
			// Fabric-Netzwerk: nur EMPFANGEN, niemals senden
			Map.entry("net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking", Set.of("registerGlobalReceiver")),
			Map.entry("net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry", Set.of("clientboundPlay", "register")),
			Map.entry("net/fabricmc/loader/api/FabricLoader", Set.of("getInstance", "getConfigDir"))
	);

	/** Nur diese Klassen dürfen überhaupt Dateien anfassen (Konfiguration bzw. Paket-Log im config-Ordner). */
	private static final Set<String> FILE_ACCESS = Set.of(OWN + "config/ModConfig", OWN + "trigger/PacketTriggers");

	private static final Set<String> FILES_EXACT = Set.of("fabric.mod.json", "schiessbude.mixins.json", "META-INF/MANIFEST.MF");

	private final List<String> problems = new ArrayList<>();
	private final Set<String> usedExternal = new TreeSet<>();
	private int classCount;

	public static void main(String[] args) throws IOException {
		if (args.length != 1) {
			System.out.println("Aufruf: java tools/SicherheitsCheck.java <mod.jar>");
			System.exit(2);
		}
		SicherheitsCheck check = new SicherheitsCheck();
		check.checkJar(Path.of(args[0]));
		if (check.problems.isEmpty()) {
			System.out.println("Sicherheits-Check OK: " + check.classCount + " Klassen geprüft, keine verbotenen Funktionen.");
			System.out.println("Benutzte fremde Pakete: " + summarize(check.usedExternal));
		} else {
			System.out.println("SICHERHEITS-CHECK FEHLGESCHLAGEN (" + check.problems.size() + " Probleme):");
			check.problems.forEach(p -> System.out.println("  - " + p));
			System.exit(1);
		}
	}

	private void checkJar(Path jar) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				if (e.isDirectory()) continue;
				String name = e.getName();
				if (name.endsWith(".class")) {
					if (!name.startsWith(OWN)) {
						problems.add("Fremde Klasse in der Mod: " + name);
						continue;
					}
					try (InputStream in = zip.getInputStream(e)) {
						checkClass(name, in.readAllBytes());
					}
				} else if (name.equals("fabric.mod.json")) {
					checkModJson(read(zip, e));
				} else if (name.equals("schiessbude.mixins.json")) {
					checkMixinJson(read(zip, e));
				} else if (!FILES_EXACT.contains(name) && !isAllowedAsset(name)) {
					problems.add("Unerwartete Datei in der Mod: " + name);
				}
			}
		}
		if (classCount == 0) problems.add("Keine Klassen gefunden - ist das die richtige Datei?");
	}

	private static boolean isAllowedAsset(String name) {
		if (!name.startsWith("assets/schiessbude/")) return false;
		return name.endsWith(".png") || name.endsWith(".png.mcmeta") || (name.startsWith("assets/schiessbude/lang/") && name.endsWith(".json"));
	}

	private static String read(ZipFile zip, ZipEntry e) throws IOException {
		try (InputStream in = zip.getInputStream(e)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private void checkModJson(String json) {
		for (String key : List.of("\"jars\"", "accessWidener", "classTweaker", "languageAdapters", "\"main\"", "\"server\"", "preLaunch")) {
			if (json.contains(key)) problems.add("fabric.mod.json enthält " + key + " (nicht erlaubt)");
		}
		for (String entry : List.of("de.schiessbude.SchiessbudeClient", "de.schiessbude.compat.ModMenuIntegration")) {
			if (!json.contains("\"" + entry + "\"")) problems.add("fabric.mod.json: erwarteter Einstiegspunkt fehlt: " + entry);
		}
		if (json.replace("de.schiessbude.SchiessbudeClient", "").replace("de.schiessbude.compat.ModMenuIntegration", "")
				.matches("(?s).*\"[a-z][a-z0-9_]*\\.[a-z0-9_.]*\\.[A-Z][A-Za-z0-9_$]*\".*")) {
			problems.add("fabric.mod.json verweist auf unbekannte Klassen");
		}
	}

	private void checkMixinJson(String json) {
		if (!json.contains("\"package\": \"de.schiessbude.mixin\"")) problems.add("Mixins liegen nicht im eigenen Paket");
		String list = json.replaceAll("(?s).*\"client\"\\s*:\\s*\\[(.*?)].*", "$1").replaceAll("\\s", "");
		if (!list.equals("\"ChatComponentMixin\"")) problems.add("Unerwartete Mixins: " + list);
		if (json.contains("\"server\"") || json.contains("\"mixins\"") || json.contains("plugin")) {
			problems.add("Unerwartete Mixin-Einträge (server/mixins/plugin)");
		}
	}

	// ---- Klassendatei lesen -----------------------------------------------------------------------------------------

	private void checkClass(String file, byte[] bytes) throws IOException {
		classCount++;
		DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
		if (in.readInt() != 0xCAFEBABE) {
			problems.add(file + ": keine gültige Klassendatei");
			return;
		}
		in.readUnsignedShort();
		in.readUnsignedShort();
		int count = in.readUnsignedShort();
		int[] tag = new int[count];
		String[] utf = new String[count];
		int[] a = new int[count];
		int[] b = new int[count];
		for (int i = 1; i < count; i++) {
			tag[i] = in.readUnsignedByte();
			switch (tag[i]) {
				case 1 -> utf[i] = in.readUTF();
				case 3, 4 -> in.readInt();
				case 5, 6 -> {
					in.readLong();
					i++;
				}
				case 7, 8, 16, 19, 20 -> a[i] = in.readUnsignedShort();
				case 9, 10, 11, 12, 17, 18 -> {
					a[i] = in.readUnsignedShort();
					b[i] = in.readUnsignedShort();
				}
				case 15 -> {
					a[i] = in.readUnsignedByte();
					b[i] = in.readUnsignedShort();
				}
				default -> {
					problems.add(file + ": unbekannter Eintrag im Konstanten-Pool (" + tag[i] + ")");
					return;
				}
			}
		}
		in.readUnsignedShort(); // access flags
		String self = utf[a[in.readUnsignedShort()]];
		if (!(self + ".class").equals(file)) problems.add(file + ": Klassenname passt nicht zur Datei (" + self + ")");

		Set<String> reported = new HashSet<>();
		for (int i = 1; i < count; i++) {
			if (tag[i] == 7) {
				String cls = className(utf[a[i]]);
				if (cls != null) checkClassRef(self, cls, reported);
			} else if (tag[i] == 9 || tag[i] == 10 || tag[i] == 11) {
				String owner = className(utf[a[a[i]]]);
				String member = utf[a[b[i]]];
				if (owner == null || owner.startsWith(OWN)) continue;
				checkClassRef(self, owner, reported);
				Set<String> allowed = MEMBERS.get(owner);
				if (allowed != null && !allowed.contains(member)) {
					problems.add(self + " benutzt " + owner.replace('/', '.') + "." + member + " (nicht erlaubt)");
				}
			}
		}
	}

	private void checkClassRef(String self, String cls, Set<String> reported) {
		if (cls.startsWith(OWN)) return;
		usedExternal.add(cls);
		String why = null;
		if (!isAllowed(cls)) {
			why = "nicht auf der Positivliste";
		} else if (cls.startsWith("java/nio/file/") && !FILE_ACCESS.contains(self)) {
			why = "Dateizugriff ist nur in der Konfiguration und im Paket-Log erlaubt";
		}
		if (why != null && reported.add(cls)) problems.add(self + " benutzt " + cls.replace('/', '.') + " (" + why + ")");
	}

	private static boolean isAllowed(String cls) {
		for (String d : DENIED) if (cls.startsWith(d)) return false;
		if (cls.startsWith("java/util/") && !cls.startsWith("java/util/function/") && !cls.startsWith("java/util/regex/")) {
			for (String d : JAVA_UTIL_DENIED) if (cls.startsWith(d)) return false;
			return cls.indexOf('/', "java/util/".length()) < 0; // nur direkt in java.util
		}
		for (String p : ALLOWED) {
			if (p.endsWith("/") ? cls.startsWith(p) : cls.equals(p)) return true;
		}
		return false;
	}

	/** "java/lang/String", "[Ljava/lang/String;" oder "[[I" -> Klassenname bzw. null bei primitiven Arrays. */
	private static String className(String raw) {
		if (raw == null) return null;
		String s = raw;
		while (s.startsWith("[")) s = s.substring(1);
		if (s.startsWith("L") && s.endsWith(";")) return s.substring(1, s.length() - 1);
		if (s.length() == 1) return null;
		return s;
	}

	private static String summarize(Set<String> classes) {
		Set<String> packages = new TreeSet<>();
		for (String c : classes) {
			String[] parts = c.split("/");
			packages.add(String.join(".", java.util.Arrays.copyOf(parts, Math.min(parts.length - 1, 3))));
		}
		return String.join(", ", packages);
	}
}
