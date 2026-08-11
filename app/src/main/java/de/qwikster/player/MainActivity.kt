package de.qwikster.player

import android.app.PictureInPictureParams
import android.content.Context
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
import de.qwikster.player.data.model.Playlist
import de.qwikster.player.data.prefs.LanguageStore
import de.qwikster.player.data.repository.IptvRepository
import de.qwikster.player.player.PlayerManager
import de.qwikster.player.ui.navigation.QwiksterNavHost
import de.qwikster.player.ui.theme.QwiksterTheme
import de.qwikster.player.ui.theme.TvBackground
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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

    /**
     * Legt die gewählte App-Sprache über sämtliche Ressourcen dieses
     * Bildschirms – siehe [LanguageStore]. Muss hier geschehen und nicht in
     * `onCreate`: Zu dem Zeitpunkt sind Layouts und Texte längst aufgelöst.
     * Zusammen mit `recreate()` beim Umstellen wechselt die Oberfläche
     * dadurch sofort und vollständig die Sprache.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageStore.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Vollflächig zeichnen: TVs haben keine System-Leisten, die Ränder
        // gehören dem Inhalt (Overscan wird im Layout berücksichtigt).
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Nullable statt Boolean: `null` heißt "die Datenbankabfrage läuft
        // noch". Ohne diese Unterscheidung würde der Navigationsgraph beim
        // Start immer mit "keine Playlist" beginnen – die Room-Abfrage
        // braucht ein paar Millisekunden –, einen wiederkehrenden Nutzer kurz
        // auf den Einrichtungsbildschirm werfen und dort steckenbleiben,
        // sobald der echte Wert eintrifft: dieser Bildschirm reagiert nur auf
        // den Abschluss der eigenen Einrichtung, nicht auf Datenbankänderungen
        // von außen.
        // Vor der ersten Antwort wird die hinterlegte Verbindung
        // wiederhergestellt, falls die Datenbank keine mehr kennt – etwa
        // nachdem ein Update ihr Schema geändert hat und sie deshalb neu
        // angelegt wurde. Die Reihenfolge ist wesentlich: Der
        // Navigationsgraph legt sein Startziel einmalig fest, käme die
        // Wiederherstellung danach, bliebe der Nutzer trotz vorhandener
        // Zugangsdaten auf der Einrichtungsseite stehen.
        val hasPlaylistFlow = flow {
            repository.restorePlaylistIfMissing()
            emitAll(repository.observeActivePlaylist().map<Playlist?, Boolean?> { it != null })
        }.stateIn(lifecycleScope, SharingStarted.Eagerly, null)

        setContent {
            val hasPlaylist by hasPlaylistFlow.collectAsState()

            QwiksterTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TvBackground),
                ) {
                    // Erst rendern, wenn der echte Wert da ist – sonst baut
                    // sich der Navigationsgraph mit dem falschen Startziel auf,
                    // und niemand korrigiert das mehr (siehe QwiksterNavHost).
                    hasPlaylist?.let { resolved ->
                        QwiksterNavHost(
                            hasPlaylist = resolved,
                            onEnterPip = ::enterPipMode,
                        )
                    }
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
