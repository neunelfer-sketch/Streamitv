package de.qwikster.player.ui.recordings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
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
import de.qwikster.player.ui.components.ChannelLogo
import de.qwikster.player.ui.theme.TvAccent
import de.qwikster.player.ui.theme.TvBackground
import de.qwikster.player.ui.theme.TvLive
import de.qwikster.player.ui.theme.TvOnSurfaceMuted
import de.qwikster.player.ui.theme.TvSpacing
import de.qwikster.player.ui.theme.TvSurface
import de.qwikster.player.ui.theme.TvSurfaceVariant

/**
 * Wählt eine künftige Sendung zum Aufnehmen aus.
 *
 * Aufbau wie im Hauptbildschirm, weil die Bewegung dieselbe ist: links die
 * Sender, rechts deren Programm. Die Anzeige folgt dem Fokus – wer durch die
 * Senderliste blättert, sieht sofort, was dort in den nächsten zehn Stunden
 * läuft, ohne jeden Sender einzeln bestätigen zu müssen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScheduleScreen(
    onBack: () -> Unit,
    viewModel: ScheduleViewModel = hiltViewModel(),
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
            text = stringResource(R.string.recording_schedule_title),
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = state.message ?: stringResource(R.string.recording_schedule_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.message != null) TvAccent else TvOnSurfaceMuted,
        )
        Spacer(Modifier.height(TvSpacing.medium))

        Row(modifier = Modifier.fillMaxSize()) {
            // --- Sender mit ihren Angaben -----------------------------------
            LazyColumn(
                modifier = Modifier
                    .width(340.dp)
                    .fillMaxHeight()
                    .background(TvSurface)
                    .focusRestorer(),
                contentPadding = PaddingValues(TvSpacing.small),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.channels, key = { it.streamId }) { channel ->
                    Surface(
                        onClick = { viewModel.selectChannel(channel) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .touchClickable({ viewModel.selectChannel(channel) })
                            .onFocusChanged { if (it.isFocused) viewModel.selectChannel(channel) },
                        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                            containerColor = if (channel.streamId == state.selectedChannelId) {
                                TvSurfaceVariant
                            } else {
                                Color.Transparent
                            },
                            focusedContainerColor = TvAccent,
                        ),
                        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = channel.number.takeIf { it > 0 }?.toString().orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                color = TvOnSurfaceMuted,
                                modifier = Modifier.width(34.dp),
                            )
                            ChannelLogo(
                                logoUrl = channel.logoUrl,
                                contentDescription = channel.name,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = channel.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // --- Sendungen der nächsten zehn Stunden ------------------------
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = TvSpacing.medium),
            ) {
                when {
                    state.selectedChannel == null -> Hint(stringResource(R.string.recording_schedule_pick_channel))
                    state.isLoadingPrograms -> Hint(stringResource(R.string.guide_loading))
                    state.programs.isEmpty() -> Hint(stringResource(R.string.recording_schedule_no_epg))
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize().focusRestorer(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(state.programs, key = { it.startAt }) { program ->
                            ProgramRow(
                                program = program,
                                onClick = {
                                    viewModel.schedule(program) { title ->
                                        viewModel.showMessage(
                                            context.getString(R.string.recording_scheduled, title),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgramRow(program: ScheduleProgram, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .touchClickable(onClick),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = TvAccent,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.width(150.dp)) {
                Text(
                    text = TimeFormat.range(program.startAt, program.endAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvOnSurfaceMuted,
                )
                Text(
                    text = TimeFormat.dayShort(program.startAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = TvOnSurfaceMuted,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                program.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvOnSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Ein bereits vorgemerkter Eintrag zeigt das an, statt sich ein
            // zweites Mal anlegen zu lassen.
            Icon(
                imageVector = if (program.isPlanned) Icons.Default.Check else Icons.Default.FiberManualRecord,
                contentDescription = null,
                tint = if (program.isPlanned) TvLive else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = TvOnSurfaceMuted)
    }
}
