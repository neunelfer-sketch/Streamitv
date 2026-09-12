# 9elf Player

IPTV-Player für **Android TV** und **Fire TV** im Stil von TiviMate.
Nativ umgesetzt mit **Kotlin, Jetpack Compose for TV und Media3/ExoPlayer**.

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
   ├─ vod/                      Filme/Serien, inkl. Film- und Serien-Detailansicht
   ├─ login/                    Ersteinrichtung
   ├─ settings/                 Einstellungen, inkl. Kanalverwaltung (sortieren/ausblenden)
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
- Catch-up/Timeshift-Wiedergabe: vergangene Sendungen im TV-Guide antippen
  (nur bei Sendern mit Archiv, Info-Leiste zeigt einen „Catch-up“-Hinweis)
- Detailansicht für Filme (Plot, Jahr, Bewertung, Laufzeit) und
  Staffel-/Episoden-Browser für Serien, inkl. Nachladen der Details
  (`get_vod_info`/`get_series_info`) beim ersten Öffnen
- Kanalverwaltung in den Einstellungen: Sender pro Kategorie ausblenden oder
  mit ▲/▼ neu sortieren (übersteht einen Playlist-Refresh)

## Noch offen

- Multiview (mehrere Streams gleichzeitig)
- Aufnahmefunktion
- Fortsetzen-Position für Filme/Episoden (Datenmodell ist vorbereitet)

---

_Developed by 9elf_
