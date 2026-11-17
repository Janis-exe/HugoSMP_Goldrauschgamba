# Goldrausch Schießbude – Fabric-Mod für Minecraft 26.3

**⬇ Download:** Reiter [Actions](../../actions/workflows/build.yml) → neuester grüner Durchlauf → unten bei
**Artifacts** auf `goldrausch-schiessbude` klicken. Du bekommst eine ZIP, darin liegt die `.jar` für den `mods`-Ordner.
Jede Änderung am Code baut automatisch eine neue Version.

![Vorschau](docs/vorschau.png)

Bei jedem Fund spielt der Client eine Gewinn-Animation im Brawl-Stars-Stil ab. Es gibt drei Buden im gleichen
Goldrausch-Look, alle etwa gleich lang (rund 6 Sekunden Spannung, dann die Enthüllung):

* **Schießstand:** Zielscheiben mit verschiedenen Gewinnen klappen auf bunten Podesten hoch. Eine goldene Pistole in
  der Mitte schießt eine Scheibe nach der anderen um. Vor dem letzten Schuss zielt sie spannungsvoll erst auf deinen
  Gewinn, bis nur noch er steht.
* **Slotmaschine:** Eine Hand zieht den Hebel rechts an der Maschine runter, die drei Walzen drehen sich und bleiben
  nacheinander mit deinem Gewinn auf der Gewinnlinie stehen. Die letzte Walze bleibt erst eine Reihe zu früh stehen
  und rutscht dann doch noch auf den Gewinn. Danach regnet es Münzen.
* **Roulette:** Ein Croupier-Schieber schiebt die Chips aufs Einsatzfeld, eine Hand dreht das Rad an und wirft die
  Kugel ein. Die Fächer zeigen die Gewinne in ihrer Seltenheitsfarbe. Die Kugel hüpft erst in ein wertvolleres Fach
  und springt dann in das Fach mit deinem Gewinn.

Am Ende zoomt die Kamera heran und der Gewinn wird groß enthüllt
(Seltenheit, Name und Betrag, bei Episch/Legendär mit Konfetti).

* **Chat und obere Bildschirmmitte sind während der Animation komplett verdeckt.** Chat, Titel, Actionbar
  und Bossleiste werden zusätzlich ausgeblendet, auch wenn man mit T den Chat öffnet.
* **Warteschlange:** Kommen mehrere Funde direkt nacheinander, laufen alle der Reihe nach ab.
  Unten rechts steht, wie viele noch warten.
* Die Animation pausiert, solange ein Menü (z. B. Inventar) offen ist. So verpasst man keinen Fund.

## Installation

1. [Fabric Loader](https://fabricmc.net/use/) **0.20.1+** für Minecraft **26.3** installieren.
2. **Fabric API** für 26.3 in den `mods`-Ordner legen.
3. `goldrausch-schiessbude-1.1.0.jar` in den `mods`-Ordner legen (eine ältere Version vorher löschen).
4. Optional: **Mod Menu** (21.0.1+). Dann gibt es dort den Knopf „Konfigurieren“.

## Bedienung

| Was | Wie |
|---|---|
| Einstellungsmenü | Taste **K**, Befehl `/schiessbude` oder Mod Menu |
| Animation überspringen | Taste **J** (1× = direkt zum Gewinn, 2× = beenden) |
| Testen (zufällige Animation) | `/schiessbude test` oder `/schiessbude test goldticket` |
| Bestimmte Animation testen | `/schiessbude test schiessstand`, `/schiessbude test slot`, `/schiessbude test roulette` (optional mit Gewinn, z. B. `/schiessbude test slot spawner`) |
| Warteschlange leeren | `/schiessbude leeren` |

Beide Tasten lassen sich unter *Optionen → Steuerung → Goldrausch Schießbude* ändern.

### Einstellungsmenü
* **Animation** an/aus
* **Test: Erz abbauen**: Jedes abgebaute Erz startet eine Animation mit zufälligem Gewinn. Welche Animation,
  hängt vom Erz ab (siehe unten). **Ist zum Testen eingeschaltet. Für das echte Spielen ausschalten!**
* **Server-Fund**: welche Animation bei einem echten Fund vom Server läuft (Schießstand, Slotmaschine, Roulette
  oder Zufall)
* **Ab Seltenheit**: z. B. „Selten“, dann laufen Goldnuggets und Goldbarren ohne Animation durch
* **Tempo** (0,5x–3x), **Lautstärke**, **Gewinn-Anzeige** (wie lange der Gewinn zu sehen ist)
* **Chat verdecken**: Chat, Titel, Actionbar und Bossleiste während der Animation ausblenden
* **Pakete loggen**: siehe unten
* **Chat / Actionbar / Titel / Untertitel**: welche Server-Pakete einen Fund auslösen dürfen
* **▶ Schießstand / ▶ Slotmaschine / ▶ Roulette**: Animation sofort testen

### Erz-Test: welches Erz welche Animation startet

| Erz | Animation |
|---|---|
| Kupfer | Schießstand |
| Kohle | Slotmaschine |
| Gold, Eisen, Redstone, Diamant, Lapis | Roulette |
| alle anderen (Smaragd, Quarz, Antiker Schrott …) | Zufall |

Deepslate- und Nether-Varianten zählen mit (z. B. `deepslate_coal_ore`, `nether_gold_ore`). Die Zuordnung steht in
`config/schiessbude.json` unter `oreAnimations` und `otherOresAnimation`. Der Schlüssel ist ein Teil der Block-ID,
der Wert ist `SHOOTING_RANGE`, `SLOT_MACHINE`, `ROULETTE` oder `RANDOM`.

## Wie wird ein Fund erkannt?

Die Mod liest die Text-Pakete, die der Client vom Server empfängt:

* `ClientboundSystemChatPacket` (Chat-Systemnachricht und Actionbar)
* `ClientboundSetTitleTextPacket` (Titel)
* `ClientboundSetSubtitleTextPacket` (Untertitel)

Enthält ein Text den Namen eines Gewinns (z. B. „Goldbarren“) **und** ein Wort wie „gefunden“, startet
die Animation. Ein Betrag wie `12.345 $` im Text wird automatisch mit angezeigt.

### Die genaue Server-Nachricht herausfinden
1. Im Menü **Pakete loggen** einschalten.
2. Auf dem Server etwas finden.
3. Die Datei `.minecraft/config/schiessbude/pakete.log` öffnen. Dort steht jeder empfangene Text, z. B.
   `[20:04:41.123] CHAT: Goldrausch » Du hast einen Goldbarren gefunden! (+1.250.000 $)`
4. In `.minecraft/config/schiessbude.json` die Muster anpassen und im Menü auf **Konfig neu laden** klicken:
   * `requiredPattern`: Muss in jeder Fund-Nachricht vorkommen (z. B. `Du hast`). Damit lösen Werbung
     wie „Kauf dir Star-Tools!“ und die Funde **anderer Spieler** nicht aus.
   * `ignorePattern`: Nachrichten, die hierauf passen, lösen nie aus.
   * `prizes[].pattern` / `prizes[].detailPattern`: Erkennung des Gewinns bzw. des Betrags/Typs.

Alle Muster sind reguläre Ausdrücke (Java-Regex), `(?i)` = Groß-/Kleinschreibung egal.

### Optional: eigenes Server-Paket
Wer ein eigenes Server-Plugin hat, kann auch direkt das Paket `schiessbude:reward` schicken.
Der Inhalt ist UTF-8-Text: `gewinn_id` oder `gewinn_id|Detail`.

```java
// Bukkit/Paper: einmal im onEnable()
getServer().getMessenger().registerOutgoingPluginChannel(this, "schiessbude:reward");
// beim Fund
player.sendPluginMessage(this, "schiessbude:reward", "goldbarren|+1.250.000 $".getBytes(StandardCharsets.UTF_8));
```

## Gewinne und Platzhalter

Die 5 Gewinne aus dem Goldrausch-Menü sind voreingestellt:

| ID | Name | Seltenheit |
|---|---|---|
| `goldnugget` | Goldnugget | Gewöhnlich (grau) |
| `goldbarren` | Goldbarren | Ungewöhnlich (grün) |
| `star_tool` | Star-Tool | Selten (blau) |
| `spawner` | Spawner | Episch (lila) |
| `goldticket` | Goldticket | Legendär (orange) |

In `config/schiessbude.json` kannst du Gewinne ändern, hinzufügen oder entfernen.

**Bilder (Platzhalter):** Die Bilder liegen in `src/main/resources/assets/schiessbude/textures/prize/`.
Es gibt drei Wege, sie zu ersetzen:
* Eigene PNGs (quadratisch, z. B. 256×256) mit gleichem Namen dort ablegen und neu bauen.
* Ein Ressourcenpaket mit `assets/schiessbude/textures/prize/<name>.png` verwenden.
* In der Konfig bei `icon` eine Item-ID eintragen, z. B. `"minecraft:gold_ingot"`.

## Selbst bauen

Benötigt **JDK 25**.

```bash
./gradlew build
```

Die fertige Mod liegt danach unter `build/libs/goldrausch-schiessbude-1.1.0.jar`.

* Minecraft mit der Mod starten: `./gradlew runClient`
* Animation direkt im Hauptmenü ansehen: `./gradlew runClient -Ppreview=zufall` (oder `-Ppreview=goldticket`),
  bestimmte Animation mit `-Panim=schiessstand`, `-Panim=slot` oder `-Panim=roulette`
* Alle Grafiken neu erzeugen (Python + Pillow): `python3 tools/generate_textures.py`

## Projektaufbau

```
src/main/java/de/schiessbude/
├─ SchiessbudeClient.java          Start: Tasten, HUD, Paket-Events, Befehle, Erz-Test
├─ anim/CasinoAnimation.java       Gemeinsame Basis: Zoom, Enthüllung, Sounds, Überspringen
├─ anim/ShootingRangeAnimation.java  Schießstand (Kupfer)
├─ anim/SlotMachineAnimation.java  Slotmaschine (Kohle)
├─ anim/RouletteAnimation.java     Roulette (Gold, Eisen, Redstone, Diamant, Lapis)
├─ anim/AnimationManager.java      Warteschlange, Pausieren, Überspringen
├─ anim/Gfx.java, PrizeIcon.java   Zeichen-Helfer, Gewinn-Bilder
├─ trigger/PacketTriggers.java     Erkennung in Chat/Actionbar/Titel-Paketen + Paket-Log
├─ net/RewardPayload.java          Optionales eigenes Paket schiessbude:reward
├─ config/                         Konfiguration, Gewinne, Seltenheiten
├─ gui/SettingsScreen.java         Einstellungsmenü
├─ gui/PreviewScreen.java          Vorschau ohne Welt
├─ mixin/ChatComponentMixin.java   Versteckt den Chat während der Animation
└─ compat/ModMenuIntegration.java  Mod-Menu-Knopf
```

## Lizenz

GPL-3.0, siehe [LICENSE](LICENSE).
