package de.qwikster.player.ui.common

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Hält Bildschirm und Bildschirmschoner zurück, solange [enabled] gilt.
 *
 * Ohne das blendet der Fire TV Stick nach einigen Minuten ohne Tastendruck
 * seinen Bildschirmschoner über das laufende Bild – beim Fernsehen genau
 * dann, wenn man die Fernbedienung erwartungsgemäß gerade *nicht* benutzt.
 * `FLAG_KEEP_SCREEN_ON` ist der von Amazon und Android dafür vorgesehene
 * Weg; er verhindert zugleich, dass das Gerät den Bildschirm abschaltet.
 *
 * Bewusst an die Wiedergabe gekoppelt und nicht global gesetzt: In Menüs
 * und bei angehaltenem Film soll sich das Gerät ganz normal verhalten. Das
 * Flag wird deshalb auch beim Verlassen der Komposition wieder entfernt –
 * andernfalls bliebe der Bildschirmschoner für den Rest der Sitzung
 * ausgeschaltet.
 */
@Composable
fun KeepScreenOn(enabled: Boolean = true) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val window = context.findActivity()?.window
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
