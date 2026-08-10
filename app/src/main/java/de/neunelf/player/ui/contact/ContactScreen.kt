package de.neunelf.player.ui.contact

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.neunelf.player.R
import de.neunelf.player.ui.components.DeveloperCredit
import de.neunelf.player.ui.theme.TvAccent
import de.neunelf.player.ui.theme.TvBackground
import de.neunelf.player.ui.theme.TvOnSurfaceMuted
import de.neunelf.player.ui.theme.TvSpacing

/**
 * Kontaktseite zum Verlängern des Zugangs.
 *
 * Auf einem Fernseher lässt sich keine Adresse anklicken und schon gar
 * nicht abtippen – der QR-Code ist hier der einzige brauchbare Weg: Handy
 * davorhalten, fertig. Die Adresse steht deshalb bewusst *nicht* als Text
 * darunter; es ist eine SimpleX-Einladung mit über hundert Zeichen, die
 * niemand von Hand überträgt.
 *
 * Der Code liegt als Vektorgrafik vor und bleibt deshalb auf jeder
 * Bildschirmgröße scharf. Seine weiße Fläche samt Ruhezone ist Teil der
 * Grafik: Ein QR-Code direkt auf dunklem Grund wird von vielen Kameras
 * nicht erkannt.
 */
@Composable
fun ContactScreen(onBack: () -> Unit) {
    BackHandler(enabled = true) { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBackground)
            .padding(
                horizontal = TvSpacing.overscanHorizontal,
                vertical = TvSpacing.overscanVertical,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Zugang verlängern", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(TvSpacing.small))
        Text(
            text = "Code mit der Handykamera scannen – der Chat öffnet sich direkt in SimpleX.",
            style = MaterialTheme.typography.bodyLarge,
            color = TvOnSurfaceMuted,
        )

        Spacer(Modifier.height(TvSpacing.large))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.qr_9elf),
                contentDescription = "QR-Code für den Chat mit 9elf",
                modifier = Modifier
                    .size(300.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.height(TvSpacing.small))
            Text(
                text = "9elf",
                style = MaterialTheme.typography.titleLarge,
                color = TvAccent,
            )
            Text(
                text = "SimpleX Chat",
                style = MaterialTheme.typography.bodyMedium,
                color = TvOnSurfaceMuted,
            )
        }

        Spacer(Modifier.weight(1f))
        DeveloperCredit()
    }
}
