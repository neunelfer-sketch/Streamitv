package de.qwikster.player.ui.vodplayer

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.qwikster.player.R
import de.qwikster.player.core.TimeFormat
import de.qwikster.player.ui.common.KeepScreenOn
import de.qwikster.player.ui.common.LockScreenOrientation
import de.qwikster.player.ui.common.dpadEvents
import de.qwikster.player.ui.components.ProgramProgressBar
import de.qwikster.player.ui.theme.TvOnSurfaceMuted
import de.qwikster.player.ui.theme.TvSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Vollbild-Player für einen einzelnen Film oder eine Episode.
 *
 * Bewusst einfacher als der Live-Player: kein Zappen, keine Senderliste –
 * dafür Vor-/Zurückspulen, weil das bei VOD tatsächlich gebraucht wird.
 *
 * | Taste  | Wirkung                        |
 * |--------|----------------------------------|
 * | OK     | Play/Pause                        |
 * | ◀ / ▶  | 10 Sekunden zurück / vor          |
 * | Zurück | Verlassen                         |
 */
@Composable
fun VodPlayerScreen(
    onExit: () -> Unit,
    viewModel: VodPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rootFocus = remember { FocusRequester() }
    val player = viewModel.player()

    // Auf einem Handy nur während der Wiedergabe im Querformat verharren.
    LockScreenOrientation()

    // Anders als bei Live-TV an die Wiedergabe gekoppelt: Wer einen Film
    // anhält und weggeht, soll den Bildschirmschoner ganz normal bekommen.
    // Das Puffern zählt mit, sonst käme er ausgerechnet bei einer längeren
    // Ladepause.
    KeepScreenOn(enabled = state.playback.isPlaying || state.playback.isBuffering)

    // Hochgezählt bei jeder Eingabe. Der Zähler – nicht die Sichtbarkeit –
    // ist der Schlüssel des Ausblend-Timers: Sonst liefe bei einer zweiten
    // Eingabe immer noch der Timer der ersten weiter, und die Leiste
    // verschwände mitten im Spulen.
    var interactions by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }

    // ExoPlayer aktualisiert seine Position nicht von sich aus als Flow –
    // für einen sich bewegenden Fortschrittsbalken wird deshalb hier gepollt.
    var positionMs by remember { mutableLongStateOf(player.currentPosition) }
    LaunchedEffect(player) {
        while (isActive) {
            positionMs = player.currentPosition
            delay(500)
        }
    }

    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    // Bedienleiste blendet nach ein paar Sekunden ohne Eingabe wieder aus.
    LaunchedEffect(interactions) {
        controlsVisible = true
        delay(5_000)
        controlsVisible = false
    }

    BackHandler(enabled = true) { onExit() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .dpadEvents(
                onSelect = { viewModel.togglePlayPause(); interactions++; true },
                onPlayPause = { viewModel.togglePlayPause(); interactions++; true },
                onLeft = { viewModel.seekBy(-10_000L); interactions++; true },
                onRight = { viewModel.seekBy(10_000L); interactions++; true },
                onUp = { interactions++; true },
                onDown = { interactions++; true },
            ),
    ) {
        VideoSurface(player = player, modifier = Modifier.fillMaxSize())

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                state.error != null -> Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                )

                state.playback.isBuffering -> Text(
                    text = stringResource(R.string.player_buffering),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        ) {
            ControlsBar(
                title = state.title,
                isPlaying = state.playback.isPlaying,
                positionMs = positionMs,
                durationMs = state.playback.durationMs,
            )
        }
    }
}

@Composable
private fun VideoSurface(player: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        update = { view -> view.player = player },
        onRelease = { view ->
            // Player nicht freigeben – er gehört dem PlayerManager.
            view.player = null
        },
    )
}

@Composable
private fun ControlsBar(
    title: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()) else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))),
            )
            .padding(horizontal = TvSpacing.overscanHorizontal, vertical = TvSpacing.large),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
        Spacer(Modifier.height(TvSpacing.small))
        ProgramProgressBar(progress = progress, modifier = Modifier.fillMaxWidth(), height = 4.dp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) {
                    stringResource(R.string.player_pause)
                } else {
                    stringResource(R.string.player_play)
                },
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (durationMs > 0) {
                    stringResource(
                        R.string.player_position_of_duration,
                        TimeFormat.position(positionMs),
                        TimeFormat.position(durationMs),
                    )
                } else {
                    TimeFormat.position(positionMs)
                },
                style = MaterialTheme.typography.labelLarge,
                color = TvOnSurfaceMuted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
