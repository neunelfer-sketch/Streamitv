package de.neunelf.player.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Erzwingt für die Lebensdauer der aufrufenden Komposition eine feste
 * Bildschirmausrichtung und stellt beim Verlassen die vorherige wieder her.
 *
 * Gedacht für die beiden Vollbild-Player: Auf einem Handy soll sich das
 * Gerät überall frei drehen lassen (Menüs, Einstellungen, Senderliste) –
 * nur während tatsächlich ein Video läuft, ist Querformat sinnvoll. Auf
 * einem Fernseher hat der Aufruf keine Wirkung, da dort weder Sensor noch
 * eine andere Ausrichtung existiert.
 *
 * `requestedOrientation` statt eines Manifest-Eintrags, weil die App eine
 * einzige Activity für alle Bildschirme ist (siehe [de.neunelf.player.MainActivity])
 * – eine feste Ausrichtung im Manifest würde für die ganze App gelten,
 * nicht nur für den Player.
 */
@Composable
fun LockScreenOrientation(orientation: Int = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
    val context = LocalContext.current
    DisposableEffect(orientation) {
        val activity = context.findActivity()
        val original = activity?.requestedOrientation
        activity?.requestedOrientation = orientation
        onDispose {
            if (original != null) activity.requestedOrientation = original
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
