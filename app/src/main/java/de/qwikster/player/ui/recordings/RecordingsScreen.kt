package de.qwikster.player.ui.recordings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.qwikster.player.R
import de.qwikster.player.core.TimeFormat
import de.qwikster.player.ui.common.touchClickable
import de.qwikster.player.ui.theme.TvAccent
import de.qwikster.player.ui.theme.TvBackground
import de.qwikster.player.ui.theme.TvLive
import de.qwikster.player.ui.theme.TvOnSurfaceMuted
import de.qwikster.player.ui.theme.TvSpacing
import de.qwikster.player.ui.theme.TvSurfaceElevated

/**
 * Liste der Aufnahmen.
 *
 * Je Eintrag drei Aktionen nebeneinander statt eines Kontextmenüs: Auf einer
 * Fernbedienung ist ein Menü immer ein Druck mehr, und mehr als drei
 * Möglichkeiten gibt es hier nicht. Die laufende Aufnahme zeigt statt
 * "Abspielen" einen Stopp-Knopf – abspielen lässt sich erst, was fertig ist.
 */
@Composable
fun RecordingsScreen(
    onPlay: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: RecordingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(enabled = true) { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBackground)
            .padding(
                horizontal = TvSpacing.overscanHorizontal,
                vertical = TvSpacing.overscanVertical,
            ),
    ) {
        Text(
            text = stringResource(R.string.recordings_title),
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(TvSpacing.medium))

        if (state.isEmpty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.recordings_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TvOnSurfaceMuted,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = TvSpacing.large),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.small),
        ) {
            items(state.items, key = { it.id }) { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (item.isRunning) {
                                Icon(
                                    imageVector = Icons.Default.FiberManualRecord,
                                    contentDescription = null,
                                    tint = TvLive,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = buildString {
                                append(item.channelName)
                                append("  ·  ")
                                append(TimeFormat.dayShort(item.startedAt))
                                append(' ')
                                append(TimeFormat.clock(item.startedAt))
                                if (item.sizeBytes > 0) {
                                    append("  ·  ")
                                    append(formatSize(item.sizeBytes))
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TvOnSurfaceMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.errorMessage?.takeIf { item.hasFailed }?.let { message ->
                            Text(
                                text = context.getString(R.string.error_with_message, message),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (item.isRunning) {
                        RecordingAction(
                            icon = Icons.Default.Stop,
                            label = stringResource(R.string.recording_stop),
                            onClick = { viewModel.stop(item.id) },
                        )
                    } else if (!item.hasFailed) {
                        RecordingAction(
                            icon = Icons.Default.PlayArrow,
                            label = stringResource(R.string.movie_play),
                            onClick = { onPlay(item.id) },
                        )
                    }
                    Spacer(Modifier.width(TvSpacing.small))
                    RecordingAction(
                        icon = Icons.Default.Delete,
                        label = stringResource(R.string.recording_delete),
                        onClick = { viewModel.delete(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.touchClickable(onClick),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = TvSurfaceElevated,
            focusedContainerColor = TvAccent,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Dateigröße in Megabyte bzw. Gigabyte.
 *
 * Bewusst dezimal (1000 statt 1024): Aufnahmen werden mit dem verglichen,
 * was auf dem Stick an freiem Speicher angezeigt wird, und dort rechnet
 * Android ebenso.
 */
private fun formatSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 1_000) "%.1f GB".format(mb / 1_000) else "%.0f MB".format(mb)
}
