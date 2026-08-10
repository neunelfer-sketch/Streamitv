package de.neunelf.player.player

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
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

    /** Erzeugt den Player beim ersten Zugriff. */
    fun getOrCreate(): ExoPlayer =
        player ?: playerFactory.create(bufferMs = PREVIEW_BUFFER_MS).also { created ->
            // Ton aus: Beim Durchblättern der Senderliste wäre er nur störend,
            // und beim schnellen Wechseln entstünde ein Tonsalat.
            created.volume = 0f
            player = created
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
    }

    /** Gibt den Player frei – beim Verlassen des Hauptbildschirms. */
    fun release() {
        player?.release()
        player = null
        currentUrl = null
    }

    private companion object {
        /**
         * Kurz halten: Die Vorschau soll rasch ein Bild zeigen. Der
         * Mindestwert von [PlayerFactory] liegt bei zwei Sekunden.
         */
        const val PREVIEW_BUFFER_MS = 2_000
    }
}
