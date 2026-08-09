package de.neunelf.player

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import de.neunelf.player.data.repository.IptvRepository
import de.neunelf.player.player.PlayerManager
import de.neunelf.player.ui.navigation.NeunelfPlayerNavHost
import de.neunelf.player.ui.theme.NeunelfPlayerTheme
import de.neunelf.player.ui.theme.TvBackground
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Einzige Activity der App.
 *
 * Bewusst Single-Activity: Bild-in-Bild, die MediaSession und der laufende
 * ExoPlayer hängen alle an der Activity. Mehrere Activities würden bedeuten,
 * den Player bei jedem Bildschirmwechsel neu aufzubauen – auf einem Fire TV
 * Stick jedes Mal ein bis zwei Sekunden Schwarzbild.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var repository: IptvRepository

    @Inject lateinit var playerManager: PlayerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Vollflächig zeichnen: TVs haben keine System-Leisten, die Ränder
        // gehören dem Inhalt (Overscan wird im Layout berücksichtigt).
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val hasPlaylistFlow = repository.observeActivePlaylist()
            .map { it != null }
            .stateIn(lifecycleScope, SharingStarted.Eagerly, false)

        setContent {
            val hasPlaylist by hasPlaylistFlow.collectAsState()

            NeunelfPlayerTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TvBackground),
                ) {
                    NeunelfPlayerNavHost(
                        hasPlaylist = hasPlaylist,
                        onEnterPip = ::enterPipMode,
                    )
                }
            }
        }
    }

    /**
     * Wechselt in den Bild-in-Bild-Modus.
     *
     * Auf Android TV ab API 26 verfügbar; Fire-OS-Geräte unterstützen es
     * erst ab Fire OS 7. Fehlt die Unterstützung, passiert schlicht nichts –
     * eine Fehlermeldung wäre hier unnötig störend.
     */
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onStop() {
        super.onStop()
        // Im PiP-Modus läuft die Wiedergabe absichtlich weiter.
        if (!isInPictureInPictureModeCompat()) {
            playerManager.stop()
        }
    }

    override fun onDestroy() {
        // Nur beim endgültigen Beenden freigeben – bei Konfigurationswechseln
        // (z. B. HDMI-Auflösung ändert sich) soll der Player überleben.
        if (isFinishing) playerManager.release()
        super.onDestroy()
    }

    private fun isInPictureInPictureModeCompat(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
}
