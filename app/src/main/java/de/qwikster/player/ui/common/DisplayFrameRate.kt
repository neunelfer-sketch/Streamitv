package de.qwikster.player.ui.common

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.annotation.RequiresApi
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "DisplayFrameRate"

/**
 * Stellt die Bildwiederholrate des Fernsehers auf den laufenden Film um.
 *
 * **Das ist der eigentliche Grund für ruckelndes Bild auf einem Fernseher.**
 * Europäisches Material läuft mit 25 oder 50 Bildern je Sekunde, ein Fire TV
 * Stick gibt aber standardmäßig 60 Hz aus. 60 lässt sich nicht glatt durch 25
 * teilen: Der Player muss Bilder ungleichmäßig doppelt zeigen (mal zwei, mal
 * drei Ausgabebilder je Filmbild). Das Ergebnis ist ein regelmäßiges Stocken
 * bei Kameraschwenks – auch dann, wenn Netz, Puffer und Decoder tadellos
 * arbeiten. Kein Puffer der Welt behebt das, weil es gar kein Ladeproblem ist.
 *
 * Schaltet der Bildschirm dagegen auf 50 Hz, geht jedes Filmbild in genau
 * zwei Ausgabebilder auf, und der Schwenk läuft glatt.
 *
 * Die Umschaltung kostet einmalig einen kurzen Schwarzbild-Moment, weil sich
 * die HDMI-Verbindung neu abstimmt. Deshalb passiert sie nur, wenn die neue
 * Rate wirklich besser passt als die eingestellte – beim Umschalten zwischen
 * Sendern mit gleicher Bildrate bleibt alles, wie es ist.
 */
@Composable
fun MatchDisplayFrameRate(frameRate: Float?, enabled: Boolean) {
    val context = LocalContext.current

    LaunchedEffect(frameRate, enabled) {
        // Die Versionsprüfung steht bewusst ganz außen und für sich: So
        // erkennt auch die Werkzeugkette (lintVitalRelease bricht den
        // Release-Build bei einem ungeprüften Aufruf ab), dass der Zugriff
        // auf die Anzeigemodi abgesichert ist.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activity = context.findActivity()
            if (enabled && frameRate != null && frameRate > 0f && activity != null) {
                applyBestMode(activity, frameRate)
            }
        }
    }

    // Beim Verlassen des Players zurück auf die Voreinstellung des Geräts:
    // Die Oberfläche selbst profitiert nicht von 50 Hz, und ein dauerhaft
    // umgestellter Bildschirm wäre eine Nebenwirkung, die niemand erwartet.
    DisposableEffect(Unit) {
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.findActivity()?.let { clearPreferredMode(it) }
            }
        }
    }
}

/**
 * Wie schlecht [refreshRate] zu [frameRate] passt – kleiner ist besser.
 *
 * Maß ist der Abstand zum nächsten ganzzahligen Vielfachen: Bei 50 Hz und 25
 * Bildern ist das Verhältnis genau 2, der Abstand also 0 (perfekt). Bei 60 Hz
 * und 25 Bildern liegt es bei 2,4 – ein Abstand von 0,4, und genau diese 0,4
 * sieht man als Ruckeln.
 *
 * Ein Bildschirm, der langsamer läuft als das Material, fällt ganz heraus:
 * Dann müssten Bilder verworfen werden, was schlimmer aussieht als jedes
 * ungleichmäßige Vervielfachen.
 */
private fun mismatch(refreshRate: Float, frameRate: Float): Float {
    val ratio = refreshRate / frameRate
    if (ratio < 0.99f) return Float.MAX_VALUE
    return abs(ratio - ratio.roundToInt())
}

@RequiresApi(Build.VERSION_CODES.M)
private fun applyBestMode(activity: Activity, frameRate: Float) {
    val display = activity.currentDisplay() ?: return
    val current = display.mode ?: return

    // Nur die Bildrate ändern, nie die Auflösung: Wer seinen Fernseher auf
    // 4K stehen hat, will nicht, dass ein 720p-Sender das Panel umstellt.
    val candidates = display.supportedModes
        ?.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        .orEmpty()
    if (candidates.isEmpty()) return

    // Erst die Passgenauigkeit, bei Gleichstand die höhere Rate: 25 Bilder
    // gehen sowohl in 50 als auch in 100 Hz glatt auf, und die höhere Rate
    // lässt zusätzlich die Oberfläche weicher laufen.
    val best = candidates.minWithOrNull(
        compareBy<Display.Mode> { mismatch(it.refreshRate, frameRate) }
            .thenByDescending { it.refreshRate },
    ) ?: return

    if (best.modeId == current.modeId) return
    // Nur wechseln, wenn es messbar besser wird – ein Schwarzbild für nichts
    // wäre schlechter als das bisschen Stocken.
    if (mismatch(best.refreshRate, frameRate) >= mismatch(current.refreshRate, frameRate)) return

    runCatching {
        activity.window.attributes = activity.window.attributes.apply {
            preferredDisplayModeId = best.modeId
        }
        Log.i(TAG, "Bildrate ${frameRate}fps -> ${best.refreshRate}Hz (Modus ${best.modeId})")
    }.onFailure { Log.w(TAG, "Bildwiederholrate ließ sich nicht umstellen", it) }
}

@RequiresApi(Build.VERSION_CODES.M)
private fun clearPreferredMode(activity: Activity) {
    runCatching {
        activity.window.attributes = activity.window.attributes.apply {
            preferredDisplayModeId = 0
        }
    }
}

private fun Activity.currentDisplay(): Display? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
    }
