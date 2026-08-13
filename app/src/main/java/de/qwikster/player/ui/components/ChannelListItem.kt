package de.qwikster.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.qwikster.player.R
import de.qwikster.player.core.TimeFormat
import de.qwikster.player.data.model.ChannelWithProgram
import de.qwikster.player.ui.common.touchClickable
import de.qwikster.player.ui.theme.TvAccent
import de.qwikster.player.ui.theme.TvFavorite
import de.qwikster.player.ui.theme.TvOnSurfaceMuted
import de.qwikster.player.ui.theme.TvSurfaceElevated

/**
 * Eine Zeile der Senderliste – das Herzstück der TiviMate-Optik.
 *
 * Aufbau (von links nach rechts):
 * ```
 * [ 12 ] [Logo] RTL HD                          ★
 *              20:15 Wer wird Millionär?
 *              ▓▓▓▓▓▓▓▓░░░░░░░░  noch 23 Min.
 * ```
 *
 * Die Komponente wird an zwei Stellen benutzt: im Hauptbildschirm und im
 * eingeblendeten Sender-Overlay des Players. Deshalb ist sie zustandslos –
 * Fokus und Auswahl kommen von außen.
 */
@Composable
fun ChannelListItem(
    item: ChannelWithProgram,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * Bezugszeit für den Fortschrittsbalken.
     *
     * Bewusst **ohne** Standardwert. Hier stand `System.currentTimeMillis()`,
     * und ein Standardwert, der sich bei jedem Aufruf ändert, nimmt Compose
     * jede Möglichkeit, diese Zeile zu überspringen – siehe
     * [de.qwikster.player.ui.common.rememberMinuteTicker]. Wer diese
     * Komponente benutzt, gibt deshalb ausdrücklich eine Zeit an, die eine
     * Weile stabil bleibt.
     */
    now: Long,
) {
    var isFocused by remember { mutableStateOf(false) }
    val channel = item.channel

    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .touchClickable(onClick, onLongClick)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (focusState.isFocused) onFocused()
            },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (isPlaying) TvAccent.copy(alpha = 0.18f) else Color.Transparent,
            focusedContainerColor = TvAccent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = Color.White,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // --- Senderplatz ------------------------------------------------
            Text(
                text = channel.number.takeIf { it > 0 }?.toString().orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = if (isFocused) Color.White.copy(alpha = 0.8f) else TvOnSurfaceMuted,
                modifier = Modifier.width(34.dp),
            )

            // --- Logo -------------------------------------------------------
            ChannelLogo(
                logoUrl = channel.logoUrl,
                contentDescription = channel.name,
                modifier = Modifier.size(44.dp),
            )

            Spacer(Modifier.width(10.dp))

            // --- Name + laufende Sendung ------------------------------------
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                val current = item.current
                if (current != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.program_time_title,
                            TimeFormat.clock(current.startAt),
                            current.title,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isFocused) Color.White.copy(alpha = 0.85f) else TvOnSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    ProgramProgressBar(
                        progress = current.progressAt(now),
                        modifier = Modifier.fillMaxWidth(0.9f),
                        trackColor = if (isFocused) Color.White.copy(alpha = 0.25f) else TvSurfaceElevated,
                        progressColor = if (isFocused) Color.White else TvAccent,
                    )
                }
            }

            // --- Favoritenstern ---------------------------------------------
            if (channel.isFavorite) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = stringResource(R.string.favorite),
                    tint = if (isFocused) Color.White else TvFavorite,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(18.dp),
                )
            }
        }
    }
}

/**
 * Senderlogo mit Platzhalter.
 *
 * Sehr viele Panels liefern tote Logo-URLs; ohne Platzhalter hätte die
 * Liste dann unruhige Lücken.
 *
 * **Bewusst `AsyncImage` und nicht `SubcomposeAsyncImage`.** Letzteres war
 * hier der teuerste Posten der ganzen Senderliste: Es baut je Bild ein
 * `SubcomposeLayout` auf, also eine eigene kleine Komposition mitten im
 * Messvorgang, und das je sichtbarer Zeile. Coil rät davon in
 * Bildlauflisten ausdrücklich ab. Auf einem Fire TV Stick war das beim
 * Durchblättern deutlich zu spüren.
 *
 * Der Fehlerfall braucht dafür kein Subcompose, nur ein Merkmal: Schlägt
 * das Laden fehl, tritt derselbe Platzhalter an die Stelle, den auch ein
 * Sender ganz ohne Logo bekommt.
 */
@Composable
fun ChannelLogo(
    logoUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    // Schlüssel auf die Adresse: Beim Weiterblättern verwendet Compose
    // dieselbe Einheit für den nächsten Sender wieder – ohne den Schlüssel
    // bliebe das Fehlermerkmal des vorigen Senders stehen, und ein Sender
    // mit gültigem Logo zeigte grundlos den Platzhalter.
    var failed by remember(logoUrl) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(TvSurfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        if (logoUrl.isNullOrBlank() || failed) {
            Icon(
                imageVector = Icons.Default.Tv,
                contentDescription = contentDescription,
                tint = TvOnSurfaceMuted,
                modifier = Modifier.size(20.dp),
            )
        } else {
            AsyncImage(
                model = logoUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
            )
        }
    }
}

/** Schmaler Fortschrittsbalken der laufenden Sendung. */
@Composable
fun ProgramProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = TvSurfaceElevated,
    progressColor: Color = TvAccent,
    height: androidx.compose.ui.unit.Dp = 3.dp,
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                // Reihenfolge ist wichtig: `fillMaxSize()` würde die
                // Breitenangabe wieder auf 100 % zurücksetzen.
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(progressColor),
        )
    }
}
