package de.qwikster.player.ui.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.qwikster.player.R
import de.qwikster.player.core.TimeFormat
import de.qwikster.player.data.model.AspectRatioMode
import de.qwikster.player.data.model.Channel
import de.qwikster.player.data.repository.RecordingVariant
import de.qwikster.player.player.TrackOption
import de.qwikster.player.player.toResizeMode
import de.qwikster.player.ui.common.touchClickable
import de.qwikster.player.ui.components.ChannelListItem
import de.qwikster.player.ui.components.ChannelLogo
import de.qwikster.player.ui.components.ProgramProgressBar
import de.qwikster.player.ui.common.KeepScreenOn
import de.qwikster.player.ui.common.LockScreenOrientation
import de.qwikster.player.ui.common.dpadEvents
import de.qwikster.player.ui.theme.TvAccent
import de.qwikster.player.ui.theme.TvFavorite
import de.qwikster.player.ui.theme.TvLive
import de.qwikster.player.ui.theme.TvOnSurfaceMuted
import de.qwikster.player.ui.theme.TvSpacing
import de.qwikster.player.ui.theme.TvSurface

/**
 * Vollbild-Player mit einblendbaren Overlays – die zentrale Ansicht der App.
 *
 * **Belegung der Fernbedienung** (wie bei TiviMate):
 *
 * | Taste          | Wirkung                                              |
 * |----------------|------------------------------------------------------|
 * | ▼ (unten)      | Senderliste mit EPG einblenden                        |
 * | ▲ (oben)       | Schnelloptionen (Ton, Untertitel, Format, Favorit)    |
 * | OK             | Info-Leiste zur laufenden Sendung ein-/ausblenden      |
 * | ◀ / ▶          | Sender zurück / weiter                                |
 * | Kanal +/−      | Sender zurück / weiter                                |
 * | Zurück         | Overlay schließen, sonst zurück zur Senderübersicht    |
 *
 * Die Overlays sind bewusst *nicht* modal gestapelt: es ist immer höchstens
 * eines sichtbar, und Zurück schließt genau dieses. Alles andere führt auf
 * einer Fernbedienung schnell zu "wo bin ich?"-Momenten.
 */
@Composable
fun PlayerScreen(
    startChannel: Channel?,
    onExit: () -> Unit,
    onEnterPip: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rootFocus = remember { FocusRequester() }

    // Auf einem Handy nur während der Wiedergabe im Querformat verharren –
    // in Menüs soll sich das Gerät frei drehen lassen. Auf einem Fernseher
    // ohne Sensor ist das ein Aufruf ohne Wirkung.
    LockScreenOrientation()

    // Solange das Vollbild offen ist, bleibt der Bildschirmschoner weg.
    // Live-TV kennt keine Pause: Wer hier steht, schaut zu – auch wenn er
    // minutenlang keine Taste drückt.
    KeepScreenOn()

    // Startkanal nur einmal anspielen – nicht bei jeder Recomposition.
    LaunchedEffect(startChannel?.streamId) {
        startChannel?.let { viewModel.playChannel(it) }
    }

    // Nach dem Schließen eines Overlays muss der Fokus zurück auf die
    // Wurzel, sonst kommen keine Tastenereignisse mehr an.
    LaunchedEffect(state.overlay) {
        if (state.overlay == OverlayMode.NONE) {
            runCatching { rootFocus.requestFocus() }
        }
    }

    BackHandler(enabled = true) {
        if (state.overlay != OverlayMode.NONE) viewModel.hideOverlay() else onExit()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .dpadEvents(
                onDown = {
                    if (state.overlay == OverlayMode.NONE) {
                        viewModel.showOverlay(OverlayMode.CHANNELS); true
                    } else {
                        false
                    }
                },
                onUp = {
                    if (state.overlay == OverlayMode.NONE) {
                        viewModel.showOverlay(OverlayMode.QUICK_OPTIONS); true
                    } else {
                        false
                    }
                },
                onSelect = {
                    if (state.overlay == OverlayMode.NONE) {
                        viewModel.toggleInfo(); true
                    } else {
                        false
                    }
                },
                onLeft = {
                    if (state.overlay == OverlayMode.NONE) {
                        viewModel.previousChannel(); true
                    } else {
                        false
                    }
                },
                onRight = {
                    if (state.overlay == OverlayMode.NONE) {
                        viewModel.nextChannel(); true
                    } else {
                        false
                    }
                },
                onChannelUp = { viewModel.nextChannel(); true },
                onChannelDown = { viewModel.previousChannel(); true },
            ),
    ) {
        // --- Videofläche ----------------------------------------------------
        VideoSurface(
            player = viewModel.player(),
            aspectRatio = state.settings.aspectRatio,
            modifier = Modifier.fillMaxSize(),
        )

        // --- Ladeanzeige / Fehler -------------------------------------------
        PlaybackStatusOverlay(
            isBuffering = state.playback.isBuffering,
            retryCount = state.playback.retryCount,
            error = state.playback.error,
        )

        // --- Info-Leiste (OK) -----------------------------------------------
        AnimatedVisibility(
            visible = state.overlay == OverlayMode.INFO,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            InfoBar(state = state)
        }

        // --- Schnelloptionen (▲) --------------------------------------------
        AnimatedVisibility(
            visible = state.overlay == OverlayMode.QUICK_OPTIONS,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            QuickOptionsBar(
                isRecording = state.isRecording,
                onStartRecording = viewModel::startRecording,
                onStopRecording = viewModel::stopRecording,
                audioTracks = state.playback.audioTracks,
                subtitleTracks = state.playback.subtitleTracks,
                aspectRatio = state.settings.aspectRatio,
                isFavorite = state.isFavorite,
                onSelectAudio = viewModel::selectAudioTrack,
                onSelectSubtitle = viewModel::selectSubtitleTrack,
                onCycleAspectRatio = viewModel::cycleAspectRatio,
                onToggleFavorite = viewModel::toggleFavorite,
                onEnterPip = onEnterPip,
            )
        }

        // --- Senderliste (▼) -------------------------------------------------
        AnimatedVisibility(
            visible = state.overlay == OverlayMode.CHANNELS,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            ChannelZapper(
                state = state,
                onSelect = { channel ->
                    viewModel.playChannel(channel)
                    viewModel.hideOverlay()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Videofläche
// ---------------------------------------------------------------------------

/**
 * Bindet die Media3-`PlayerView` ein.
 *
 * `useController = false`: die Standard-Bedienelemente sind für Touch
 * gedacht und lassen sich auf einer Fernbedienung kaum sinnvoll bedienen –
 * wir zeichnen unsere eigenen Overlays in Compose darüber.
 */
@Composable
private fun VideoSurface(
    player: androidx.media3.exoplayer.ExoPlayer,
    aspectRatio: AspectRatioMode,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                // SurfaceView statt TextureView: deutlich weniger CPU-Last
                // und korrekte HDR-Ausgabe auf TV-Geräten.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        update = { view ->
            view.player = player
            view.resizeMode = aspectRatio.toResizeMode()
        },
        onRelease = { view ->
            // Player nicht freigeben – er gehört dem PlayerManager und
            // überlebt Bildschirmwechsel (z. B. für PiP).
            view.player = null
        },
    )
}

@Composable
private fun PlaybackStatusOverlay(
    isBuffering: Boolean,
    retryCount: Int,
    error: String?,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(TvSpacing.small))
                Text(
                    text = stringResource(R.string.player_switch_channel_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvOnSurfaceMuted,
                )
            }

            retryCount > 0 -> Text(
                text = stringResource(R.string.player_reconnecting, retryCount, 3),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )

            isBuffering -> Text(
                text = stringResource(R.string.player_buffering),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Info-Leiste (OK-Taste)
// ---------------------------------------------------------------------------

@Composable
private fun InfoBar(state: PlayerUiState) {
    val channel = state.currentChannel ?: return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                ),
            )
            .padding(
                horizontal = TvSpacing.overscanHorizontal,
                vertical = TvSpacing.large,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(
                logoUrl = channel.logoUrl,
                contentDescription = channel.name,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.width(TvSpacing.medium))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = channel.number.takeIf { it > 0 }
                            ?.let { stringResource(R.string.player_channel_number_prefix, it) }
                            .orEmpty() + channel.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                    state.playback.videoResolution?.let { resolution ->
                        Spacer(Modifier.width(TvSpacing.small))
                        Text(
                            text = resolution,
                            style = MaterialTheme.typography.labelMedium,
                            color = TvOnSurfaceMuted,
                        )
                    }
                }

                val current = state.currentProgram
                if (current != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = TvAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.program_time_remaining,
                            TimeFormat.range(current.startAt, current.endAt),
                            TimeFormat.remaining(LocalContext.current, current.endAt),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvOnSurfaceMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    ProgramProgressBar(
                        progress = current.progressAt(System.currentTimeMillis()),
                        modifier = Modifier.fillMaxWidth(0.6f),
                    )
                    state.nextProgram?.let { next ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.player_up_next,
                                TimeFormat.clock(next.startAt),
                                next.title,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TvOnSurfaceMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.player_no_program),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvOnSurfaceMuted,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Schnelloptionen (▲)
// ---------------------------------------------------------------------------

/**
 * Leiste am oberen Rand mit den Einstellungen, die man während des Schauens
 * braucht. Jede Schaltfläche ist einzeln fokussierbar; Tonspur und
 * Untertitel öffnen eine Auswahlliste darunter.
 */
@Composable
private fun QuickOptionsBar(
    isRecording: Boolean,
    onStartRecording: (RecordingVariant) -> Unit,
    onStopRecording: () -> Unit,
    audioTracks: List<TrackOption>,
    subtitleTracks: List<TrackOption>,
    aspectRatio: AspectRatioMode,
    isFavorite: Boolean,
    onSelectAudio: (TrackOption) -> Unit,
    onSelectSubtitle: (TrackOption) -> Unit,
    onCycleAspectRatio: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEnterPip: () -> Unit,
) {
    // Welche Unterliste gerade offen ist (null = keine).
    var expanded by remember { mutableStateOf<String?>(null) }
    val firstItemFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { firstItemFocus.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.92f), Color.Transparent),
                ),
            )
            .padding(
                horizontal = TvSpacing.overscanHorizontal,
                vertical = TvSpacing.medium,
            ),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.small)) {
            QuickAction(
                icon = Icons.Default.VolumeUp,
                label = stringResource(R.string.player_audio_track),
                value = audioTracks.firstOrNull { it.isSelected }?.label,
                modifier = Modifier.focusRequester(firstItemFocus),
                onClick = { expanded = if (expanded == "audio") null else "audio" },
            )
            QuickAction(
                icon = Icons.Default.ClosedCaption,
                label = stringResource(R.string.player_subtitles),
                value = subtitleTracks.firstOrNull { it.isSelected }?.label,
                onClick = { expanded = if (expanded == "subs") null else "subs" },
            )
            QuickAction(
                icon = Icons.Default.AspectRatio,
                label = stringResource(R.string.player_format),
                value = stringResource(aspectRatio.labelRes),
                onClick = onCycleAspectRatio,
            )
            QuickAction(
                icon = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                label = if (isFavorite) {
                    stringResource(R.string.player_remove_favorite)
                } else {
                    stringResource(R.string.player_add_favorite)
                },
                tint = if (isFavorite) TvFavorite else Color.White,
                onClick = onToggleFavorite,
            )
            QuickAction(
                icon = Icons.Default.FiberManualRecord,
                label = if (isRecording) {
                    stringResource(R.string.recording_stop)
                } else {
                    stringResource(R.string.recording_start)
                },
                tint = if (isRecording) TvLive else Color.White,
                onClick = {
                    if (isRecording) {
                        onStopRecording()
                        expanded = null
                    } else {
                        expanded = if (expanded == "rec") null else "rec"
                    }
                },
            )
            QuickAction(
                icon = Icons.Default.PictureInPictureAlt,
                label = stringResource(R.string.player_pip),
                onClick = onEnterPip,
            )
        }

        // --- Aufgeklappte Aufnahme-Varianten --------------------------------
        // Wie lange aufgenommen wird, entscheidet der Zuschauer hier. "Bis
        // Sendungsende" ist der Regelfall; die festen Längen springen ein,
        // wenn der Sender kein EPG hat und damit gar kein Ende bekannt ist.
        if (expanded == "rec") {
            Spacer(Modifier.height(TvSpacing.small))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.small)) {
                RecordingVariant.entries.forEach { variant ->
                    QuickAction(
                        icon = Icons.Default.FiberManualRecord,
                        label = stringResource(variant.labelRes),
                        tint = TvLive,
                        onClick = {
                            onStartRecording(variant)
                            expanded = null
                        },
                    )
                }
            }
        }

        // --- Aufgeklappte Spurauswahl ---------------------------------------
        val options = when (expanded) {
            "audio" -> audioTracks
            "subs" -> subtitleTracks
            else -> emptyList()
        }

        if (options.isNotEmpty()) {
            Spacer(Modifier.height(TvSpacing.small))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.small)) {
                options.forEach { option ->
                    TrackChip(
                        option = option,
                        onClick = {
                            if (expanded == "audio") onSelectAudio(option) else onSelectSubtitle(option)
                            expanded = null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    tint: Color = Color.White,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.touchClickable(onClick),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = TvSurface.copy(alpha = 0.9f),
            focusedContainerColor = TvAccent,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge)
                value?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = TvOnSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackChip(option: TrackOption, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.touchClickable(onClick),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (option.isSelected) TvAccent.copy(alpha = 0.35f) else TvSurface,
            focusedContainerColor = TvAccent,
        ),
    ) {
        Text(
            text = option.label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Senderliste im Player (▼)
// ---------------------------------------------------------------------------

/**
 * Halbtransparente Senderliste am unteren Bildrand. Das Bild läuft weiter –
 * genau das macht das Zappen bei TiviMate so angenehm.
 */
@Composable
private fun ChannelZapper(
    state: PlayerUiState,
    onSelect: (Channel) -> Unit,
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // Beim Öffnen zum laufenden Sender springen.
    LaunchedEffect(Unit) {
        val index = state.currentIndex
        if (index >= 0) listState.scrollToItem(index)
        runCatching { focusRequester.requestFocus() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.95f)),
                ),
            ),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .width(520.dp)
                .fillMaxHeight()
                .padding(start = TvSpacing.overscanHorizontal, top = TvSpacing.medium)
                .focusRequester(focusRequester),
            contentPadding = PaddingValues(bottom = TvSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(state.channels, key = { it.channel.streamId }) { item ->
                ChannelListItem(
                    item = item,
                    isPlaying = item.channel.streamId == state.currentChannel?.streamId,
                    onClick = { onSelect(item.channel) },
                    onFocused = {},
                )
            }
        }
    }
}
