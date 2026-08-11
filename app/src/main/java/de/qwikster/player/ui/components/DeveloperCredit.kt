package de.qwikster.player.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.qwikster.player.ui.theme.TvOnSurfaceMuted

/**
 * Entwicklerhinweis – bewusst an einer Stelle, damit er nur hier gepflegt wird.
 *
 * Bleibt "9elf": Qwikster ist der Name der App, 9elf der des Entwicklers.
 * Die Umbenennung der App betrifft diese Zeile also ausdrücklich nicht.
 */
const val DEVELOPER_CREDIT = "Developed by 9elf"

/**
 * Dezente Signaturzeile am unteren Rand eines Bildschirms.
 *
 * Bewusst zurückhaltend gehalten: kleinste Schriftgröße, stark gedämpfte
 * Farbe und nicht fokussierbar. Damit taucht sie beim Navigieren mit der
 * Fernbedienung nie im Fokuspfad auf und stört den eigentlichen Inhalt nicht.
 */
@Composable
fun DeveloperCredit(
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
) {
    Text(
        text = DEVELOPER_CREDIT,
        style = MaterialTheme.typography.labelMedium,
        color = TvOnSurfaceMuted.copy(alpha = 0.5f),
        textAlign = textAlign,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    )
}
