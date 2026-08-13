package de.qwikster.player.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kleiner Player für die Vorschau im Hauptbildschirm.
 *
 * Bewusst **nicht** der [PlayerManager]: Der gehört dem Vollbild-Player,
 * hängt an der MediaSession und am Bild-in-Bild-Modus. Würde die Vorschau
 * ihn mitbenutzen, überschriebe sie beim bloßen Durchblättern der
 * Senderliste den laufenden Sender, und die Fernbedienungstasten des
 * Systems steuerten plötzlich die Vorschau.
 *
 * Eigenheiten gegenüber dem großen Player:
 *
 * - **Kurzer Puffer.** Die Vorschau soll schnell ein Bild zeigen; ein
 *   Aussetzer ist hier belanglos, weil ohnehin gleich weitergeblättert wird.
 * - **Keine Neuversuche.** Antwortet ein Sender nicht, bleibt die Fläche
 *   einfach leer. Ein Wiederholungslauf würde nur Verbindungen verbrauchen.
 * - **Wird beim Verlassen des Bildschirms freigegeben**, damit kein zweiter
 *   Stream offen bleibt, während im Vollbild gespielt wird.
 */
@Singleton
class PreviewPlayer @Inject constructor(
    private val playerFactory: PlayerFactory,
) {

    private var player: ExoPlayer? = null
    private var currentUrl: String? = null

    private val _isActive = MutableStateFlow(false)

    /**
     * Läuft gerade eine Vorschau – einschließlich des Ladens?
     *
     * Der Hauptbildschirm hält daran den Bildschirmschoner zurück: Wer eine
     * laufende Vorschau anschaut, benutzt die Fernbedienung gerade
     * erwartungsgemäß *nicht*, und der Fire TV Stick legt nach ein paar
     * Minuten seinen Bildschirmschoner darüber und schickt die App
     * anschließend in den Hintergrund.
     *
     * Das Laden zählt bewusst mit: Ein Sender, der zehn Sekunden puffert,
     * ist genauso wenig ein Grund für den Bildschirmschoner wie einer, der
     * schon spielt.
     */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val stateListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshActive()
        override fun onPlaybackStateChanged(playbackState: Int) = refreshActive()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refreshActive()

        // Ein Sender, der nicht antwortet, darf den Bildschirm nicht endlos
        // wach halten: Der Player landet dann in STATE_IDLE, und genau das
        // beendet den Zustand unten.
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) = refreshActive()
    }

    private fun refreshActive() {
        val exo = player
        _isActive.value = exo != null &&
            exo.playWhenReady &&
            exo.playbackState != Player.STATE_IDLE &&
            exo.playbackState != Player.STATE_ENDED
    }

    /** Erzeugt den Player beim ersten Zugriff. */
    fun getOrCreate(): ExoPlayer =
        player ?: playerFactory.create(bufferMs = PREVIEW_BUFFER_MS).also { created ->
            // Ton bewusst an: Die Vorschau soll den Sender genauso zeigen wie
            // hören, nicht nur ein stummes Bild liefern. Die Verzögerung in
            // [play] (siehe Aufrufer) sorgt dafür, dass beim bloßen
            // Durchblättern kein Tonsalat entsteht – erst wenn der Fokus auf
            // einem Sender zur Ruhe kommt, läuft überhaupt etwas an.
            created.volume = 1f
            // Kein Wake-Lock für eine Vorschau: Die läuft ausschließlich,
            // während der Hauptbildschirm vorne ist – CPU und WLAN sind dann
            // ohnehin wach. Den Lock hält allein der Vollbild-Player.
            created.setWakeMode(C.WAKE_MODE_NONE)
            created.addListener(stateListener)
            player = created
            refreshActive()
        }

    /**
     * Spielt [url] an, sofern nicht bereits dieselbe Adresse läuft.
     *
     * Die Prüfung ist wichtig: Der Fokus kann durch bloßes Neuzeichnen
     * erneut gemeldet werden, und ein Neustart bei gleicher Adresse ließe
     * das Bild sichtbar flackern.
     */
    fun play(url: String) {
        if (currentUrl == url && player?.isPlaying == true) return
        val exo = getOrCreate()
        currentUrl = url
        exo.setMediaItem(
            MediaItem.Builder()
                .setUri(url)
                .apply { PlayerFactory.mimeTypeFor(url)?.let { setMimeType(it) } }
                .build(),
        )
        exo.prepare()
        exo.play()
    }

    fun stop() {
        player?.stop()
        currentUrl = null
        refreshActive()
    }

    /** Gibt den Player frei – beim Verlassen des Hauptbildschirms. */
    fun release() {
        player?.removeListener(stateListener)
        player?.release()
        player = null
        currentUrl = null
        refreshActive()
    }

    private companion object {
        /**
         * Kurz halten: Die Vorschau soll rasch ein Bild zeigen. Der
         * Mindestwert von [PlayerFactory] liegt bei zwei Sekunden.
         */
        const val PREVIEW_BUFFER_MS = 2_000
    }
}
