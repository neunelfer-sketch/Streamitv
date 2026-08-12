package de.qwikster.player.player

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Media-Session der App.
 *
 * Zweck auf Android TV / Fire TV:
 * - Die Wiedergabetasten der Fernbedienung (Play/Pause, Vor/Zurück) werden
 *   auch dann zugestellt, wenn die App im Bild-in-Bild-Modus läuft oder das
 *   Overlay des Systems im Vordergrund ist.
 * - Der laufende Sender erscheint in der "Weiterschauen"-Zeile des Launchers.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var playerManager: PlayerManager

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession.Builder(this, playerManager.getOrCreate()).build()
            // Beim [PlayerManager] hinterlegen: Gibt der die ExoPlayer-Instanz
            // frei, muss die Session **vorher** zu sein. Auf `onDestroy` dieses
            // Dienstes ist dabei kein Verlass – es läuft erst eine
            // Botschaftsschlange später.
            .also { playerManager.attachSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        // Über den PlayerManager, damit ein bereits geschlossener Zustand
        // erkannt wird und die Session nicht doppelt freigegeben wird.
        playerManager.releaseSession()
        mediaSession = null
        super.onDestroy()
    }
}
