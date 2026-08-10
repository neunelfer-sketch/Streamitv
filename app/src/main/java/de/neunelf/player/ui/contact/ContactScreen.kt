package de.neunelf.player.ui.contact

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/** Ein Telegram-Kanal mit zugehörigem QR-Code. */
private data class ContactChannel(
    val label: String,
    val handle: String,
    val qrRes: Int,
)

private val CHANNELS = listOf(
    ContactChannel("9elf", "t.me/neunelfzig", R.drawable.qr_telegram_neunelfzig),
    ContactChannel("Streamingpate", "t.me/streamingpate", R.drawable.qr_telegram_streamingpate),
)

/**
 * Kontaktseite zum Verlängern des Zugangs.
 *
 * Auf einem Fernseher lässt sich keine Adresse anklicken und schon gar
 * nicht bequem abtippen – der QR-Code ist hier der eigentliche Weg: Handy
 * davorhalten, fertig. Die Adresse steht trotzdem darunter, für alle, die
 * gerade kein Handy zur Hand haben.
 *
 * Die Codes liegen als Vektorgrafik vor und bleiben deshalb auf jeder
 * Bildschirmgröße scharf. Ihre weiße Fläche samt Ruhezone ist Teil der
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
            text = "Code mit der Handykamera scannen – der Chat öffnet sich direkt in Telegram.",
            style = MaterialTheme.typography.bodyLarge,
            color = TvOnSurfaceMuted,
        )

        Spacer(Modifier.height(TvSpacing.large))

        Row(
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.large),
            verticalAlignment = Alignment.Top,
        ) {
            CHANNELS.forEach { channel ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(channel.qrRes),
                        contentDescription = "QR-Code zu ${channel.handle}",
                        modifier = Modifier
                            .size(260.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.height(TvSpacing.small))
                    Text(
                        text = channel.label,
                        style = MaterialTheme.typography.titleLarge,
                        color = TvAccent,
                    )
                    Text(
                        text = channel.handle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvOnSurfaceMuted,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        DeveloperCredit()
    }
}
