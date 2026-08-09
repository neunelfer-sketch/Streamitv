package de.neunelf.player.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import de.neunelf.player.data.model.AspectRatioMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Eine auswählbare Ton- oder Untertitelspur. */
data class TrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String?,
    val isSelected: Boolean,
    /** Nur für Untertitel: der Eintrag "Aus". */
    val isOffOption: Boolean = false,
)

/** Beobachtbarer Zustand der Wiedergabe. */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isLive: Boolean = true,
    val videoResolution: String? = null,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val error: String? = null,
    /** Zählt automatische Wiederholversuche – für die Anzeige "Verbinde neu (2/3)". */
    val retryCount: Int = 0,
)

/**
 * Kapselt eine ExoPlayer-Instanz und übersetzt deren Callbacks in einen
 * beobachtbaren Zustand.
 *
 * Zwei Dinge, die IPTV-spezifisch sind und hier gelöst werden:
 *
 * - **Automatischer Neuversuch.** IPTV-Server werfen Verbindungen
 *   regelmäßig grundlos ab. Ein Player, der dabei einfach stehenbleibt,
 *   fühlt sich kaputt an; wir versuchen es bis zu [MAX_RETRIES] mal neu.
 * - **Spurauswahl über Sprache statt Index.** Nach einem Kanalwechsel sind
 *   die Indizes andere; gemerkt wird deshalb die Sprache.
 */
@Singleton
class PlayerManager @Inject constructor(
    private val playerFactory: PlayerFactory,
) {

    private var exoPlayer: ExoPlayer? = null
    private var currentUrl: String? = null
    private var retries = 0

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Vom Nutzer zuletzt gewählte Sprachen – überleben den Kanalwechsel. */
    private var preferredAudioLanguage: String? = null
    private var preferredSubtitleLanguage: String? = null
    private var subtitlesEnabled = false

    private val listener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateState()
            if (playbackState == Player.STATE_READY) {
                // Erfolgreich gestartet -> Fehlerzähler zurücksetzen.
                retries = 0
                _state.value = _state.value.copy(error = null, retryCount = 0)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = updateState()

        override fun onTracksChanged(tracks: Tracks) {
            applyPreferredTracks(tracks)
            updateState()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Wiedergabefehler: ${error.errorCodeName}", error)
            handleError(error)
        }
    }

    // -----------------------------------------------------------------------
    // Lebenszyklus
    // -----------------------------------------------------------------------

    /** Erzeugt den Player oder gibt den vorhandenen zurück. */
    fun getOrCreate(bufferMs: Int = 15_000): ExoPlayer =
        exoPlayer ?: playerFactory.create(bufferMs).also { player ->
            player.addListener(listener)
            exoPlayer = player
        }

    fun release() {
        exoPlayer?.removeListener(listener)
        exoPlayer?.release()
        exoPlayer = null
        currentUrl = null
        _state.value = PlaybackState()
    }

    // -----------------------------------------------------------------------
    // Wiedergabe
    // -----------------------------------------------------------------------

    /**
     * Startet einen Stream.
     *
     * @param startPositionMs Fortsetzposition für VOD; bei Live ignoriert.
     * @param isLive steuert, ob der Player ans Live-Ende springt.
     */
    fun play(
        url: String,
        title: String? = null,
        isLive: Boolean = true,
        startPositionMs: Long = 0L,
        bufferMs: Int = 15_000,
    ) {
        val player = getOrCreate(bufferMs)
        currentUrl = url
        retries = 0

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .apply {
                // Typ nur setzen, wenn er aus der URL eindeutig ableitbar ist –
                // sonst soll ExoPlayer selbst sniffen.
                PlayerFactory.mimeTypeFor(url)?.let { setMimeType(it) }
                title?.let {
                    setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder().setTitle(it).build(),
                    )
                }
            }
            .build()

        player.setMediaItem(mediaItem, if (isLive) C.TIME_UNSET else startPositionMs)
        player.prepare()
        player.play()

        _state.value = _state.value.copy(isLive = isLive, error = null, retryCount = 0)
    }

    fun stop() {
        exoPlayer?.stop()
        currentUrl = null
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
        updateState()
    }

    /** Springt relativ zur aktuellen Position (VOD). */
    fun seekBy(deltaMs: Long) {
        val player = exoPlayer ?: return
        if (!player.isCurrentMediaItemSeekable) return
        val target = (player.currentPosition + deltaMs)
            .coerceIn(0L, player.duration.coerceAtLeast(0L))
        player.seekTo(target)
        updateState()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        updateState()
    }

    /** Aktuelle Position – für das Speichern des Fortsetzpunkts. */
    fun currentPosition(): Long = exoPlayer?.currentPosition ?: 0L

    fun currentDuration(): Long =
        exoPlayer?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L

    // -----------------------------------------------------------------------
    // Spurauswahl
    // -----------------------------------------------------------------------

    /**
     * Wählt eine Tonspur aus. Die Sprache wird gemerkt und nach einem
     * Kanalwechsel automatisch wieder gesetzt.
     */
    fun selectAudioTrack(option: TrackOption) {
        val player = exoPlayer ?: return
        val group = player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .getOrNull(option.groupIndex) ?: return

        preferredAudioLanguage = option.language
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
        updateState()
    }

    /** Wählt einen Untertitel aus oder schaltet ihn ab ([TrackOption.isOffOption]). */
    fun selectSubtitleTrack(option: TrackOption) {
        val player = exoPlayer ?: return

        if (option.isOffOption) {
            subtitlesEnabled = false
            preferredSubtitleLanguage = null
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            updateState()
            return
        }

        val group = player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .getOrNull(option.groupIndex) ?: return

        subtitlesEnabled = true
        preferredSubtitleLanguage = option.language
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
        updateState()
    }

    /** Setzt die Vorgaben aus den Einstellungen (vor dem ersten `play`). */
    fun setPreferredLanguages(audio: String?, subtitle: String?, subtitlesOn: Boolean) {
        preferredAudioLanguage = audio?.takeIf { it.isNotBlank() }
        preferredSubtitleLanguage = subtitle?.takeIf { it.isNotBlank() }
        subtitlesEnabled = subtitlesOn
    }

    /**
     * Stellt nach einem Kanalwechsel die gemerkte Sprache wieder her.
     * Fehlt sie im neuen Stream, bleibt die Auswahl des Players unangetastet.
     */
    private fun applyPreferredTracks(tracks: Tracks) {
        val player = exoPlayer ?: return

        // Ton: nur setzen, wenn der neue Stream die Sprache überhaupt anbietet.
        // Sonst würde der TrackSelector auf seine Standardauswahl zurückfallen
        // und der Nutzer müsste nach jedem Zap erneut umstellen.
        preferredAudioLanguage?.let { language ->
            if (tracks.hasLanguage(C.TRACK_TYPE_AUDIO, language)) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguage(language)
                    .build()
            }
        }

        // Untertitel nur aktivieren, wenn der Nutzer sie eingeschaltet hat.
        if (subtitlesEnabled) {
            preferredSubtitleLanguage?.let { language ->
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage(language)
                    .build()
            }
        }
    }

    /** Prüft, ob der Stream eine Spur in der gewünschten Sprache enthält. */
    private fun Tracks.hasLanguage(trackType: Int, language: String): Boolean {
        val target = language.normalizeLanguage()
        return groups.filter { it.type == trackType }.any { group ->
            (0 until group.length).any { index ->
                group.getTrackFormat(index).language.normalizeLanguage() == target
            }
        }
    }

    // -----------------------------------------------------------------------
    // Fehlerbehandlung
    // -----------------------------------------------------------------------

    /**
     * IPTV-Server brechen Verbindungen häufig ab. Wir versuchen es bis zu
     * dreimal mit wachsender Wartezeit erneut, bevor der Nutzer eine
     * Fehlermeldung sieht.
     */
    private fun handleError(error: PlaybackException) {
        val player = exoPlayer ?: return
        val url = currentUrl

        val isRecoverable = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            -> true

            else -> false
        }

        if (isRecoverable && retries < MAX_RETRIES && url != null) {
            retries++
            _state.value = _state.value.copy(retryCount = retries, error = null)
            player.prepare()
            player.play()
            return
        }

        _state.value = _state.value.copy(
            error = describeError(error),
            retryCount = retries,
            isBuffering = false,
        )
    }

    /** Übersetzt ExoPlayer-Fehlercodes in Klartext für die Oberfläche. */
    private fun describeError(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "Keine Verbindung zum Server"

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "Der Server hat den Stream abgelehnt (evtl. zu viele Verbindungen)"

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "Dieser Sender ist derzeit nicht verfügbar"

        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        -> "Format wird von diesem Gerät nicht unterstützt"

        else -> "Wiedergabe fehlgeschlagen (${error.errorCodeName})"
    }

    // -----------------------------------------------------------------------
    // Zustandsaufbereitung
    // -----------------------------------------------------------------------

    private fun updateState() {
        val player = exoPlayer ?: return
        val tracks = player.currentTracks

        _state.value = _state.value.copy(
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            videoResolution = player.videoSize
                .takeIf { it.width > 0 }
                ?.let { "${it.width}×${it.height}" },
            audioTracks = tracks.toOptions(C.TRACK_TYPE_AUDIO),
            subtitleTracks = buildList {
                add(
                    TrackOption(
                        groupIndex = -1,
                        trackIndex = -1,
                        label = "Aus",
                        language = null,
                        isSelected = !subtitlesEnabled,
                        isOffOption = true,
                    ),
                )
                addAll(tracks.toOptions(C.TRACK_TYPE_TEXT))
            },
        )
    }

    /** Baut aus den ExoPlayer-Spuren die Liste für das Schnellmenü. */
    private fun Tracks.toOptions(trackType: Int): List<TrackOption> =
        groups
            .filter { it.type == trackType }
            .flatMapIndexed { groupIndex, group ->
                (0 until group.length).mapNotNull { trackIndex ->
                    if (!group.isTrackSupported(trackIndex)) return@mapNotNull null
                    val format = group.getTrackFormat(trackIndex)

                    TrackOption(
                        groupIndex = groupIndex,
                        trackIndex = trackIndex,
                        label = format.describe(trackType, groupIndex + trackIndex),
                        language = format.language,
                        isSelected = group.isTrackSelected(trackIndex),
                    )
                }
            }

    /** Erzeugt eine lesbare Beschriftung: "Deutsch · AC-3 · 5.1". */
    private fun androidx.media3.common.Format.describe(trackType: Int, fallbackIndex: Int): String {
        val parts = mutableListOf<String>()

        language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
            val normalized = Util.normalizeLanguageCode(code) ?: code
            parts += Locale.forLanguageTag(normalized).displayLanguage
                .takeIf { it.isNotBlank() } ?: code.uppercase()
        }
        label?.takeIf { it.isNotBlank() }?.let { parts += it }

        if (trackType == C.TRACK_TYPE_AUDIO) {
            sampleMimeType?.substringAfterLast('/')?.uppercase()?.let { parts += it }
            when (channelCount) {
                1 -> parts += "Mono"
                2 -> parts += "Stereo"
                6 -> parts += "5.1"
                8 -> parts += "7.1"
            }
        }

        return parts.distinct().joinToString(" · ").ifBlank { "Spur ${fallbackIndex + 1}" }
    }

    /** Vereinheitlicht ISO-639-1/2-Codes ("de" und "deu" sollen gleich sein). */
    private fun String?.normalizeLanguage(): String? =
        this?.takeIf { it.isNotBlank() }?.let { Util.normalizeLanguageCode(it) }

    companion object {
        private const val TAG = "PlayerManager"
        private const val MAX_RETRIES = 3
    }
}

/** Übersetzt den App-Modus in den Resize-Modus der Media3-PlayerView. */
fun AspectRatioMode.toResizeMode(): Int = when (this) {
    AspectRatioMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    AspectRatioMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    AspectRatioMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectRatioMode.FIXED_16_9 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
    AspectRatioMode.FIXED_4_3 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
}
