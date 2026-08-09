package com.streamitv.tv.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/**
 * Fernbedienungs- und Fokus-Helfer.
 *
 * Auf dem Fernseher gibt es keinen Zeiger: **jede** Interaktion läuft über
 * das Steuerkreuz. Zwei Dinge müssen deshalb immer stimmen:
 *
 * 1. **Fokus ist sichtbar.** Der Nutzer sitzt zwei bis drei Meter entfernt –
 *    eine dezente Umrandung reicht nicht, es braucht Farbe *und* Skalierung.
 * 2. **Tastenereignisse werden nur einmal verarbeitet.** Android liefert je
 *    Tastendruck `KeyDown` *und* `KeyUp`; wer beides auswertet, wechselt bei
 *    einem Druck zwei Kanäle weiter.
 */

/** Alle Tasten, die als "Auswahl/OK" gelten – je nach Fernbedienung unterschiedlich. */
private val SELECT_KEYS = setOf(
    Key.DirectionCenter,
    Key.Enter,
    Key.NumPadEnter,
    Key.Spacebar,
)

/** Zurück-Tasten. Fire-TV-Fernbedienungen senden zusätzlich `Escape`. */
private val BACK_KEYS = setOf(Key.Back, Key.Escape)

/**
 * Verarbeitet D-Pad-Ereignisse.
 *
 * Nur `KeyDown` wird ausgewertet – siehe Hinweis oben. Jeder Callback gibt
 * implizit `true` zurück (Ereignis verbraucht), wenn er gesetzt ist; sonst
 * wandert das Ereignis weiter an den Fokus-Mechanismus von Compose.
 */
fun Modifier.dpadEvents(
    onLeft: (() -> Boolean)? = null,
    onRight: (() -> Boolean)? = null,
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null,
    onSelect: (() -> Boolean)? = null,
    onBack: (() -> Boolean)? = null,
    onPlayPause: (() -> Boolean)? = null,
    onChannelUp: (() -> Boolean)? = null,
    onChannelDown: (() -> Boolean)? = null,
    onNumber: ((Int) -> Boolean)? = null,
): Modifier = onKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false

    when (event.key) {
        Key.DirectionLeft -> onLeft?.invoke()
        Key.DirectionRight -> onRight?.invoke()
        Key.DirectionUp -> onUp?.invoke()
        Key.DirectionDown -> onDown?.invoke()

        in SELECT_KEYS -> onSelect?.invoke()
        in BACK_KEYS -> onBack?.invoke()

        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> onPlayPause?.invoke()

        // Kanal-Tasten gibt es auf TV-Fernbedienungen; auf Fire-TV-Sticks
        // übernehmen die Vor-/Zurückspultasten dieselbe Rolle.
        Key.ChannelUp, Key.PageUp, Key.MediaFastForward -> onChannelUp?.invoke()
        Key.ChannelDown, Key.PageDown, Key.MediaRewind -> onChannelDown?.invoke()

        // Direktwahl per Zifferntasten (z. B. "3" -> Sender 3).
        Key.Zero, Key.NumPad0 -> onNumber?.invoke(0)
        Key.One, Key.NumPad1 -> onNumber?.invoke(1)
        Key.Two, Key.NumPad2 -> onNumber?.invoke(2)
        Key.Three, Key.NumPad3 -> onNumber?.invoke(3)
        Key.Four, Key.NumPad4 -> onNumber?.invoke(4)
        Key.Five, Key.NumPad5 -> onNumber?.invoke(5)
        Key.Six, Key.NumPad6 -> onNumber?.invoke(6)
        Key.Seven, Key.NumPad7 -> onNumber?.invoke(7)
        Key.Eight, Key.NumPad8 -> onNumber?.invoke(8)
        Key.Nine, Key.NumPad9 -> onNumber?.invoke(9)

        else -> null
    } ?: false
}

/**
 * Standard-Fokusdarstellung für Listeneinträge und Kacheln:
 * Hintergrund + Rahmen + leichte Vergrößerung.
 *
 * @param scaleWhenFocused 1.0 lässt die Größe unverändert – sinnvoll in
 *        dichten Listen, wo eine Vergrößerung das Layout springen ließe.
 */
fun Modifier.tvFocusIndicator(
    isFocused: Boolean,
    shape: Shape = RoundedCornerShape(8.dp),
    focusedColor: Color = Color(0xFF3D8BFF),
    backgroundColor: Color = Color(0x333D8BFF),
    scaleWhenFocused: Float = 1f,
): Modifier = composed {
    val scale by animateFloatAsState(
        targetValue = if (isFocused) scaleWhenFocused else 1f,
        label = "focusScale",
    )
    this
        .scale(scale)
        .background(if (isFocused) backgroundColor else Color.Transparent, shape)
        .border(
            width = if (isFocused) 2.dp else 0.dp,
            color = if (isFocused) focusedColor else Color.Transparent,
            shape = shape,
        )
}

/**
 * Kombiniert `focusRequester` + `onFocusChanged` in einem Aufruf.
 * Spart in den Bildschirmen viel Boilerplate.
 */
fun Modifier.trackFocus(
    focusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit,
): Modifier = composed {
    val base = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    this.then(base).onFocusChanged { onFocusChanged(it.isFocused) }
}

/**
 * Merkt sich, ob ein Element den Fokus hat.
 *
 * Rückgabe ist ein Paar aus aktuellem Zustand und dem passenden Modifier –
 * so bleibt an der Aufrufstelle nur eine Zeile stehen.
 */
@Composable
fun rememberFocusState(): Pair<Boolean, Modifier> {
    var focused by remember { mutableStateOf(false) }
    return focused to Modifier.onFocusChanged { focused = it.isFocused }
}

/** InteractionSource, die über Compositions hinweg stabil bleibt. */
@Composable
fun rememberTvInteractionSource(): MutableInteractionSource =
    remember { MutableInteractionSource() }

/**
 * Erkennt "lange gedrückt" auf einer D-Pad-Taste.
 *
 * Wird für schnelles Durchblättern im EPG-Raster benutzt: Android wiederholt
 * `KeyDown` automatisch, `repeatCount > 0` markiert diese Wiederholungen.
 */
fun KeyEvent.isRepeat(): Boolean =
    (nativeKeyEvent.repeatCount) > 0
