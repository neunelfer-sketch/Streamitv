package de.qwikster.player.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * Die aktuelle Uhrzeit, die sich nur einmal je Minute ändert.
 *
 * Klingt nach einer Kleinigkeit, ist aber der Unterschied zwischen einer
 * flüssigen und einer zähen Senderliste.
 *
 * Der Grund liegt darin, wie Compose entscheidet, ob es Arbeit sparen darf:
 * Bekommt eine Komponente dieselben Werte wie beim letzten Mal, überspringt
 * Compose sie samt allem, was darin steckt. Stand die Uhrzeit als
 * `System.currentTimeMillis()` im Aufruf, war dieser Wert bei *jedem*
 * Durchlauf ein anderer – die Zeile konnte nie übersprungen werden. Und weil
 * am Hauptbildschirm ständig etwas passiert (der Fokus wandert, die
 * Programmzeitschrift lädt im Hintergrund nach, Zählerstände ändern sich),
 * wurde bei jeder dieser Regungen jede sichtbare Zeile komplett neu
 * aufgebaut: Logo, Text, Fortschrittsbalken.
 *
 * Mit einem Wert, der eine Minute lang derselbe bleibt, fällt das weg. Die
 * Fortschrittsbalken laufen weiter – sie rücken bei einer halbstündigen
 * Sendung ohnehin nur um gut drei Prozent je Minute vor, und das ist genau
 * die Auflösung, die auf einem Balken von wenigen Zentimetern zu erkennen
 * ist.
 */
@Composable
fun rememberMinuteTicker(): Long {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}
