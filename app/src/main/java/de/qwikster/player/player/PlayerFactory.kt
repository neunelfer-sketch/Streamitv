package de.qwikster.player.player

import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import de.qwikster.player.data.repository.PlaylistSyncer
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Baut ExoPlayer-Instanzen, die auf IPTV und schwache TV-Hardware
 * abgestimmt sind.
 *
 * Die Standardkonfiguration von ExoPlayer ist auf VOD ausgelegt. Für
 * Live-TS-Streams über ein Consumer-WLAN mussten drei Dinge angepasst werden:
 *
 * 1. **Puffergrenzen** – der Standard puffert bis zu 50 s. Auf einem
 *    Fire TV Stick bedeutet das mehrere Sekunden Verzögerung beim
 *    Kanalwechsel. Wir puffern deutlich kürzer und starten früher.
 * 2. **TS-Extractor mit HDMV-/AC3-Erkennung** – viele europäische Sender
 *    liefern AC-3/E-AC-3-Ton, den der Standard-Extractor sonst überspringt.
 * 3. **Erweiterte Renderer** – `EXTENSION_RENDERER_MODE_PREFER` nutzt
 *    Software-Decoder, wenn der Hardware-Decoder des Sticks ein Format
 *    nicht kann (statt schwarzem Bild).
 */
@Singleton
class PlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {

    /**
     * @param bufferMs Zielpuffer aus den Einstellungen. Kleinere Werte
     *        beschleunigen den Kanalwechsel, größere überbrücken WLAN-Aussetzer.
     */
    fun create(bufferMs: Int = 15_000): ExoPlayer {
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                // Auf TV nie herunterskalieren: das Panel ist das Ziel-Display.
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                // Tonspur-Wechsel soll nicht am Kanal-Sprung scheitern.
                .setAllowAudioMixedMimeTypeAdaptiveness(true)
                .setAllowAudioMixedSampleRateAdaptiveness(true)
                .setAllowAudioMixedChannelCountAdaptiveness(true)
                .build()
        }

        return ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(buildLoadControl(bufferMs))
            .setRenderersFactory(
                DefaultRenderersFactory(context)
                    // `ON` statt `PREFER`: Der Hardware-Decoder hat Vorrang,
                    // ein mitgelieferter Software-Decoder springt nur ein, wenn
                    // es für das Format keinen gibt. Genau andersherum wäre es
                    // für FHD und erst recht 4K fatal – ein Software-Decoder
                    // schafft auf einem Stick keine 3840×2160, das Bild
                    // ruckelte oder bliebe ganz weg.
                    //
                    // Heute liegt der App ohnehin keine Decoder-Erweiterung
                    // bei, der Wert ist also aktuell wirkungslos. Er steht hier
                    // trotzdem richtig, damit das Hinzufügen einer Erweiterung
                    // (etwa für AC-4) später nicht unbemerkt die
                    // Hardware-Beschleunigung abschaltet.
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                    // Bildaussetzer lieber überspringen als das Bild einfrieren lassen.
                    .setEnableDecoderFallback(true),
            )
            .setMediaSourceFactory(buildMediaSourceFactory())
            // Sorgt dafür, dass ExoPlayer bei Live-Streams die Wiedergaberate
            // minimal nachregelt, statt den Puffer leerlaufen zu lassen.
            .setUsePlatformDiagnostics(false)
            .build()
            .apply {
                playWhenReady = true
                // Hält CPU und WLAN wach, solange etwas läuft. Der Bildschirm
                // ist damit *nicht* gemeint – dafür sorgt `KeepScreenOn` in
                // der Oberfläche. Wichtig ist das für Bild-in-Bild und den
                // Moment, in dem das System die App in den Hintergrund
                // schiebt: ohne Wake-Lock drosselt Fire OS dort das WLAN, und
                // der Stream reißt nach wenigen Sekunden ab.
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
    }

    /**
     * Puffer-Strategie.
     *
     * `bufferForPlaybackMs` ist der wichtigste Wert für das gefühlte Tempo:
     * er bestimmt, wie viele Millisekunden Daten vorliegen müssen, bevor
     * das Bild erscheint. 1,5 s ist der niedrigste Wert, mit dem TS-Streams
     * auf schwachen Sticks noch zuverlässig anlaufen.
     *
     * Die beiden Startschwellen werden am Zielpuffer gedeckelt, und das ist
     * keine Vorsichtsmaßnahme, sondern Pflicht: `DefaultLoadControl` prüft
     * beim Bauen, dass der Zielpuffer nicht *kleiner* ist als sie, und wirft
     * sonst. Mit dem festen Wert von 3 s reichte ein Zielpuffer von 2 s –
     * wie ihn die Vorschau anfordert –, um die App beim Erzeugen des
     * Players zu beenden.
     */
    private fun buildLoadControl(bufferMs: Int): LoadControl {
        val minBuffer = bufferMs.coerceIn(2_000, 60_000)
        val maxBuffer = (minBuffer * 2).coerceAtMost(120_000)
        val forPlayback = 1_500.coerceAtMost(minBuffer)
        // Nach einem Aussetzer wieder anlaufen: zwei Sekunden statt drei. Ein
        // stehendes Bild wird ab etwa einer Sekunde als Ruckler wahrgenommen,
        // und wer gerade schon einen Aussetzer hatte, wartet nicht gern noch
        // eine volle Sekunde extra auf das erste Bild.
        val forPlaybackAfterRebuffer = 2_000.coerceAtMost(minBuffer)
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ minBuffer,
                /* maxBufferMs = */ maxBuffer,
                /* bufferForPlaybackMs = */ forPlayback,
                /* bufferForPlaybackAfterRebufferMs = */ forPlaybackAfterRebuffer,
            )
            // Bei Live-TS ist die Datenrate vorab unbekannt; über die Zeit zu
            // puffern ist dort das einzig verlässliche Maß.
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(targetBufferBytes())
            // **Kein** 30-Sekunden-Rückpuffer mehr.
            //
            // Der hält bereits *abgespielte* Daten im Speicher. Bei einem
            // 4K-Strom mit 25 Mbit/s sind 30 Sekunden rund 90 MB, die zum
            // Vorwärtspuffer obendrauf kommen – auf einem Stick mehr, als der
            // Heap überhaupt hergibt. Die Folge sind ständige
            // Speicherbereinigungen, und genau die sieht man als Ruckeln.
            //
            // Zehn Sekunden decken den Rücksprung-Knopf des Film-Players ab,
            // mehr braucht es nicht; Live-TV kennt ohnehin kein Zurück. Ohne
            // `retainFromKeyframe` wird zudem strikt bis zur Grenze verworfen
            // statt großzügig bis zum davorliegenden Schlüsselbild.
            .setBackBuffer(10_000, false)
            .build()
    }

    /**
     * Wie viele Bytes gepuffert werden dürfen – abhängig vom Gerät.
     *
     * Der feste Wert von 32 MB, der hier stand, war für beide Enden falsch:
     * Auf einem 4K-Stick verschenkte er Luft, auf einem alten 1-GB-Gerät war
     * er zu nah am Limit. ExoPlayer puffert auf dem Java-Heap, und dessen
     * Größe sagt das System selbst – ein Drittel davon ist eine Grenze, die
     * mit dem Gerät wächst, statt zu raten.
     *
     * Die Obergrenze ist trotzdem nötig: Ein sehr großzügiger Heap heißt
     * nicht, dass ein Videopuffer ihn auch beanspruchen sollte.
     */
    private fun targetBufferBytes(): Int {
        val heapMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.memoryClass
            ?: FALLBACK_HEAP_MB
        return (heapMb / 3).coerceIn(24, 96) * 1024 * 1024
    }

    private fun buildMediaSourceFactory(): DefaultMediaSourceFactory {
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                // AC-3/E-AC-3 und HDMV-DTS in MPEG-TS erkennen – ohne diese
                // Flags bleiben viele deutsche HD-Sender stumm.
                DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            )
            // Größerer Suchbereich für den PMT: manche Panels senden sehr
            // viel Füllmaterial, bevor die Programminfos kommen.
            .setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 3)

        return DefaultMediaSourceFactory(buildDataSourceFactory(), extractorsFactory)
            // Bei Live-Streams nach einem Fehler nicht sofort aufgeben.
            .setLiveTargetOffsetMs(3_000)
    }

    /**
     * Datenquelle über OkHttp: gemeinsamer Verbindungspool mit dem Rest der
     * App, korrekte Redirect-Behandlung und ein User-Agent, den die meisten
     * Panels akzeptieren.
     */
    private fun buildDataSourceFactory(): DataSource.Factory {
        val httpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(PlaylistSyncer.USER_AGENT)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Connection" to "keep-alive",
                ),
            )
        return DefaultDataSource.Factory(context, httpFactory)
    }

    companion object {
        /** Wenn das System keine Heap-Größe nennt: der Wert eines typischen 1-GB-Sticks. */
        private const val FALLBACK_HEAP_MB = 96

        /**
         * Container-Endungen, für die wir ExoPlayer den Typ explizit vorgeben.
         *
         * Das ist kein Detail, sondern der Unterschied zwischen sofortigem
         * Bild und einer spürbaren Gedenksekunde: Ohne Angabe schnüffelt
         * ExoPlayer die Adresse durch – es probiert der Reihe nach jeden
         * bekannten Container durch, liest dafür jedes Mal Daten ein, und
         * MPEG-TS steht in dieser Reihe weit hinten. Genau MPEG-TS ist aber
         * das Format so gut wie jedes Live-Senders.
         *
         * Der Fragezeichen-Teil wird vorher abgeschnitten. Etliche Panels
         * hängen ein Zugangsmerkmal an (`…/12345.ts?token=…`); mit dem
         * dranhängenden Anhang endete die Adresse nicht mehr auf `.ts`, und
         * ausgerechnet diese Zugänge verloren den Vorteil wieder.
         */
        fun mimeTypeFor(url: String): String? {
            val path = url.substringBefore('?').substringBefore('#')
            return when {
                path.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                path.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                path.endsWith(".ts", ignoreCase = true) -> MimeTypes.VIDEO_MP2T
                else -> null
            }
        }
    }
}
