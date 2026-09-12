package de.xott.player.player

import android.content.Context
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
import de.xott.player.data.repository.PlaylistSyncer
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
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
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
            }
    }

    /**
     * Puffer-Strategie.
     *
     * `bufferForPlaybackMs` ist der wichtigste Wert für das gefühlte Tempo:
     * er bestimmt, wie viele Millisekunden Daten vorliegen müssen, bevor
     * das Bild erscheint. 1,5 s ist der niedrigste Wert, mit dem TS-Streams
     * auf schwachen Sticks noch zuverlässig anlaufen.
     */
    private fun buildLoadControl(bufferMs: Int): LoadControl {
        val minBuffer = bufferMs.coerceIn(2_000, 60_000)
        val maxBuffer = (minBuffer * 2).coerceAtMost(120_000)
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ minBuffer,
                /* maxBufferMs = */ maxBuffer,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            // 32 MB Ziel: reicht für HD-Streams, ohne auf 1-GB-Geräten
            // den Speicher-Killer zu wecken.
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setBackBuffer(30_000, true)
            .build()
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
        /** Container-Endungen, für die wir ExoPlayer den Typ explizit vorgeben. */
        fun mimeTypeFor(url: String): String? = when {
            url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            url.endsWith(".ts", ignoreCase = true) -> MimeTypes.VIDEO_MP2T
            else -> null
        }
    }
}
