# 9elf Player

IPTV-Player für **Android TV**, **Fire TV** und **Android-Handys** im Stil
von TiviMate. Nativ umgesetzt mit **Kotlin, Jetpack Compose for TV und
Media3/ExoPlayer**.

## Auf dem Handy

Dieselbe APK läuft auch auf einem Android-Handy (ab API 22) – dieselbe
`.apk`, kein separater Build. Drei Anpassungen machen das möglich:

- Die App ist nicht mehr als reine TV-App markiert
  (`android.software.leanback` `required="false"`), sonst würden manche
  Launcher/Stores sie auf Handys als inkompatibel ausblenden.
- Die Bildschirmausrichtung ist frei, nicht mehr fest auf Querformat
  gesperrt. Nur während der eigentlichen Video-Wiedergabe schaltet die App
  gezielt auf Querformat (`LockScreenOrientation`) – in Menüs und
  Einstellungen darf sich das Handy frei drehen.
- Die mehrspaltigen Bildschirme (Hauptbildschirm, TV-Guide, Filme/Serien)
  sind für TV-Breiten (≥ 900 dp) entworfen. Unterhalb dieser Schwelle
  verwenden sie schmalere, aber weiterhin feste Spaltenbreiten
  (`COMPACT_WIDTH_BREAKPOINT`), damit auf einem Handy im Querformat noch
  genug Platz für die Vorschau bleibt. Das Filme-/Serien-Raster nutzt
  zusätzlich `GridCells.Adaptive` statt einer festen Spaltenzahl, damit
  Poster nicht auf Briefmarkengröße schrumpfen.

Die D-Pad-Bedienung war von Anfang an so gebaut, dass jede Aktion sowohl
über Fokus (Fernbedienung) als auch über Klick (Touch) auslöst – deshalb
funktioniert die App per Fingertipp, ohne dass die Interaktionslogik
angefasst werden musste. Eine eigens für Touch entworfene Oberfläche
(z. B. eine Navigationsleiste unten, dichtere Listen) ist das nicht –
dafür wäre eine echte zweite Bildschirmvariante nötig.

## Warum nativ statt Flutter

Für einen IPTV-Player auf einem Fire TV Stick sind drei Dinge entscheidend,
und in allen dreien ist der native Weg klar im Vorteil:

| Anforderung | Warum nativ |
|---|---|
| Video-Wiedergabe (TS, HLS, Multi-Audio) | ExoPlayer ist die Referenzimplementierung. Flutters `video_player` reicht Frames zusätzlich durch eine Texture-Bridge – auf schwachen Sticks kostet das spürbar Leistung, und die Spurauswahl ist eingeschränkt. |
| D-Pad-Fokus | `Compose for TV` bringt Fokusverwaltung, `focusRestorer` und Fokus-Visualisierung von Haus aus mit. In Flutter muss man das komplette Fokusmodell selbst bauen. |
| Speicher | Fire TV Sticks haben oft nur 1 GB RAM. Eine zweite Runtime plus Texture-Kopien sind hier teuer. |

## Projektstruktur

```
app/src/main/java/de/neunelf/player/
│
├─ NeunelfPlayerApplication.kt  Hilt-Einstieg, Coil-Bildcache
├─ MainActivity.kt              Single Activity, Bild-in-Bild
│
├─ core/
│  └─ TimeFormat.kt             Zeitformatierung (UTC → lokale Zeit)
│
├─ data/
│  ├─ model/Models.kt           Domänenmodelle (Channel, EpgProgram, …)
│  │
│  ├─ remote/
│  │  ├─ json/
│  │  │  └─ LenientSerializers.kt   Tolerante Serializer für Panel-Eigenheiten
│  │  ├─ xtream/
│  │  │  ├─ XtreamApi.kt            player_api.php + URL-Bau
│  │  │  ├─ XtreamDto.kt            JSON-Abbildungen
│  │  │  └─ XtreamMapper.kt         DTO → Domänenmodell
│  │  ├─ m3u/M3uParser.kt           M3U / M3U8 / m3u_plus
│  │  └─ epg/XmltvParser.kt         XMLTV (streamend, gzip-fähig)
│  │
│  ├─ local/                    Room: Entities, DAOs, Datenbank
│  ├─ prefs/SettingsStore.kt    DataStore-Einstellungen
│  └─ repository/
│     ├─ IptvRepository.kt      Sender, Kategorien, Favoriten, Verlauf
│     ├─ PlaylistSyncer.kt      Import von Xtream bzw. M3U
│     └─ EpgRepository.kt       EPG-Import und Rasterabfragen
│
├─ player/
│  ├─ PlayerFactory.kt          ExoPlayer-Konfiguration für IPTV
│  ├─ PlayerManager.kt          Wiedergabezustand, Spurauswahl, Neuversuche
│  └─ PlaybackService.kt        MediaSession (Fernbedienung, PiP)
│
├─ di/AppModule.kt              Hilt-Bindings
│
└─ ui/
   ├─ theme/                    Farben, Typografie für 10-Foot-UI
   ├─ common/DpadModifiers.kt   Fernbedienungs- und Fokus-Helfer
   ├─ components/               Wiederverwendbare Bausteine (Senderzeile …)
   ├─ home/                     Hauptbildschirm (2-Spalten-Layout)
   ├─ guide/                    TV-Guide (EPG-Raster)
   ├─ player/                   Vollbild-Player mit Overlays
   ├─ vod/                      Filme und Serien
   ├─ login/                    Ersteinrichtung
   ├─ settings/                 Einstellungen
   └─ navigation/               Navigationsgraph
```

## Bedienung über die Fernbedienung

**Hauptbildschirm**

| Taste | Wirkung |
|---|---|
| ◀ / ▶ | Zwischen Kategorien, Sendern und Vorschau wechseln |
| ▲ / ▼ | Innerhalb der Spalte bewegen |
| OK | Sender im Vollbild starten |
| OK lang | Favorit setzen/entfernen |

**Vollbild-Player**

| Taste | Wirkung |
|---|---|
| ▼ | Senderliste mit EPG einblenden |
| ▲ | Schnelloptionen (Tonspur, Untertitel, Format, Favorit, PiP) |
| OK | Info-Leiste zur laufenden Sendung |
| ◀ / ▶, Kanal +/− | Sender wechseln |
| Zurück | Overlay schließen, sonst zurück zur Übersicht |

## Datenquellen

**Xtream Codes** – Server-URL, Benutzername, Passwort. Die App normalisiert
die Eingabe, eine versehentlich mitkopierte `player_api.php?...`-URL wird
also akzeptiert. Genutzte Endpunkte:

```
player_api.php                          Anmeldung, Kontoinformationen
  ?action=get_live_categories           Kategorien
  ?action=get_live_streams              Sender
  ?action=get_vod_streams               Filme
  ?action=get_series                    Serien
  ?action=get_series_info&series_id=…   Staffeln und Episoden
  ?action=get_short_epg&stream_id=…     Kurz-EPG (Notnagel)
xmltv.php                               Vollständige Programmzeitschrift
```

Wiedergabe-URLs entstehen nach dem Schema
`{Basis}/live|movie|series/{Benutzer}/{Passwort}/{ID}.{Endung}`.

**M3U / M3U8** – direkter Link. Unterstützt den `m3u_plus`-Dialekt mit
`tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `tvg-chno`, `#EXTGRP`,
`#EXTVLCOPT` und `#EXTHTTP`. Eine im Header angegebene `url-tvg` wird
automatisch als EPG-Quelle übernommen.

**EPG** – XMLTV per URL, gzip-komprimiert oder unkomprimiert. Der Import
läuft streamend und schreibt in Blöcken zu 1.000 Sendungen, damit auch
Dateien jenseits von 100 MB auf einem Stick durchlaufen.

## Fertige APK laden

Jeder Push auf `main` oder einen `claude/*`-Branch baut die App auf GitHub
Actions und hängt zwei APKs an ein Release:

- `9elf-Player-vX.Y.Z.apk` – optimierter Build, dieser gehört auf den Stick
- `9elf-Player-vX.Y.Z-debug.apk` – nur für die Fehlersuche, deutlich größer
  und langsamer

Weil dieses Repository privat ist, sind seine Release-Dateien nicht ohne
Anmeldung abrufbar – die Downloader-App auf dem Fire TV kann sich aber nicht
anmelden. Die Builds werden deshalb zusätzlich in ein öffentliches
Repository gespiegelt, das ausschließlich die APKs enthält.

Dafür sind zwei Dinge nötig:

1. Ein öffentliches Repository, standardmäßig `9elf-player-releases`.
   Ein anderer Name lässt sich über die Repository-Variable
   `PUBLIC_RELEASE_REPO` einstellen.
2. Ein Zugriffstoken mit Schreibrecht auf dieses Repository, hinterlegt als
   Secret `RELEASE_TOKEN`.

Fehlt das Secret, überspringt der Workflow die Spiegelung und der Build
bleibt trotzdem grün.

### Aktualisierung aus der App heraus

Die App schaut beim Start still bei `releases/latest` des öffentlichen
Repositories nach einer neueren Ausgabe und zeigt einen Hinweis in der
Kopfzeile. Unter *Einstellungen → App → Aktualisierung* läuft der ganze
Ablauf über eine Taste: prüfen, herunterladen, installieren.

Zwei Voraussetzungen, die leicht übersehen werden:

- **Gleicher Signaturschlüssel.** Alle Builds werden mit dem Schlüssel aus
  `keystore/` signiert. Mit einem anderen Schlüssel lehnt Android die
  Installation über die bestehende Fassung ab.
- **Versionsnummer aus dem CI-Lauf.** `versionName`/`versionCode` kommen
  über `-PappVersionName` bzw. `-PappVersionCode` herein und müssen zum
  Release-Tag passen (`v1.0.<Lauf>`). Ein lokaler Build ohne diese Angaben
  meldet sich als `1.0.0` und hielte damit jede Veröffentlichung für neuer.

Beim ersten Mal fragt Android, ob die App Installationen vornehmen darf;
die App führt dafür direkt in die passende Systemeinstellung.

## QR-Codes zum Verlängern

Unter *Einstellungen → App → Zugang verlängern* zeigt die App zwei
QR-Codes, über die der Zuschauer den Telegram-Chat erreicht. Auf einem
Fernseher ist das der einzige bequeme Weg – anklicken lässt sich dort
nichts, und eine Adresse mit der Fernbedienung abzutippen ist mühsam.

Die Codes liegen doppelt vor:

| Datei | Zweck |
|---|---|
| `app/src/main/res/drawable/qr_telegram_*.xml` | Vektorgrafik für die App, auf jeder Bildschirmgröße scharf |
| `docs/qr/qr_telegram_*.png` | zum Ausdrucken oder für Werbung außerhalb der App |

Beide entstehen aus `scripts/generate_qr.py`. Ändert sich eine Adresse
oder kommt ein Kanal dazu, wird `TARGETS` im Skript angepasst und es neu
ausgeführt – die Dateien nicht von Hand bearbeiten:

```bash
pip install segno
python3 scripts/generate_qr.py app/src/main/res/drawable docs/qr
```

Die weiße Fläche samt Ruhezone steckt in der Grafik. Das ist Absicht: Ein
QR-Code direkt auf dunklem Untergrund wird von vielen Handykameras nicht
erkannt.

## Bauen

```bash
# Einmalig: Gradle-Wrapper erzeugen (der Wrapper-Jar liegt nicht im Repo)
gradle wrapper --gradle-version 8.9

./gradlew assembleDebug
./gradlew test            # Unit-Tests der Parser (kein Gerät nötig)

# Auf einen Fire TV Stick installieren
adb connect <IP-des-Sticks>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Voraussetzungen: JDK 17, Android SDK 35, `minSdk 22` (deckt Fire OS 5 ab).

## Umgesetzt

- Zwei-Spalten-Hauptbildschirm mit Kategorien, Senderliste und Vorschau
- EPG-Raster mit horizontaler Zeitachse, Tageswechsel und „Jetzt“-Markierung
- Vollbild-Player mit Overlays für Senderliste, Schnelloptionen und Info
- Xtream Codes: Live TV, Filme, Serien, Kurz-EPG
- M3U/M3U8-Import inkl. Kategorisierung
- XMLTV-Import (streamend, gzip)
- Mehrere Tonspuren und Untertitel, Sprachwahl bleibt über Kanalwechsel erhalten
- Fünf Seitenverhältnis-Modi
- Favoriten und „Zuletzt gesehen“ (überleben einen Playlist-Refresh)
- Bild-in-Bild
- Automatische Neuversuche bei abgebrochenen Streams
- Filme starten direkt aus dem Raster, Serien über eine Staffel-/Episodenübersicht
  (Episoden werden bei Xtream bei Bedarf über `get_series_info` nachgeladen)
- Sortierung von Filmen und Serien über das Menü oben rechts (neu hinzugefügt,
  Name A–Z, Name Z–A) – je Bereich getrennt gemerkt
- Fortsetzposition für Filme/Episoden (wird während der Wiedergabe laufend gesichert)

## Noch offen

- Catch-up/Timeshift-Wiedergabe (die URL-Erzeugung steht bereits in `XtreamApi`)
- Multiview (mehrere Streams gleichzeitig)
- Sortierung und Ausblenden von **Sendern** durch den Nutzer
  (für Filme und Serien ist sie umgesetzt)
- Aufnahmefunktion
- Serien aus reinen M3U-Playlists (der Parser erkennt aktuell nur Live und Filme)

---

_Developed by 9elf_
