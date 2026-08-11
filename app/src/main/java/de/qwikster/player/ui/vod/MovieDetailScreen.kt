package de.qwikster.player.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.qwikster.player.R
import de.qwikster.player.core.TimeFormat
import de.qwikster.player.ui.common.touchClickable
import de.qwikster.player.ui.components.ProgramProgressBar
import de.qwikster.player.ui.theme.TvAccent
import de.qwikster.player.ui.theme.TvBackground
import de.qwikster.player.ui.theme.TvOnSurfaceMuted
import de.qwikster.player.ui.theme.TvSpacing
import de.qwikster.player.ui.theme.TvSurfaceVariant

/**
 * Beschreibungsseite eines Films – Poster links, Angaben und Knöpfe rechts.
 *
 * Der Fokus steht beim Öffnen auf dem ersten Knopf: Wer hier landet, will in
 * aller Regel abspielen, und dann soll ein einziger Druck auf OK genügen.
 * Bei einem angefangenen Film ist das "Fortsetzen" – die wahrscheinlichere
 * Absicht, wenn man einen halb gesehenen Titel erneut anwählt.
 */
@Composable
fun MovieDetailScreen(
    onPlay: () -> Unit,
    onBack: () -> Unit,
    viewModel: MovieDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val primaryButton = remember { FocusRequester() }

    BackHandler(enabled = true) { onBack() }

    // Erst wenn die Angaben da sind – vorher gibt es den Knopf noch nicht,
    // und die Anforderung liefe ins Leere.
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) runCatching { primaryButton.requestFocus() }
    }

    val movie = state.movie
    if (movie == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(TvBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (state.isLoading) R.string.series_loading_episodes else R.string.error_content_not_loaded,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = TvOnSurfaceMuted,
            )
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBackground)
            .padding(
                horizontal = TvSpacing.overscanHorizontal,
                vertical = TvSpacing.overscanVertical,
            ),
    ) {
        // --- Poster ---------------------------------------------------------
        Box(
            modifier = Modifier
                .width(300.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(TvSurfaceVariant),
        ) {
            movie.posterUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = movie.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.width(TvSpacing.large))

        // --- Angaben und Knöpfe --------------------------------------------
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = movie.name,
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(TvSpacing.small))

            // Jahr, Laufzeit und Bewertung in einer Zeile – jede Angabe nur,
            // wenn das Panel sie überhaupt geliefert hat. Panels sind hier
            // sehr unterschiedlich gründlich, und leere Felder oder ein
            // "0 Min." wirken wie ein Fehler.
            val facts = listOfNotNull(
                movie.year,
                movie.durationSecs.takeIf { it > 0 }
                    ?.let { TimeFormat.duration(LocalContext.current, it * 1000L) },
                movie.rating.takeIf { it > 0 }
                    ?.let { stringResource(R.string.rating_stars, "%.1f".format(it)) },
            )
            if (facts.isNotEmpty()) {
                Text(
                    text = facts.joinToString("  ·  "),
                    style = MaterialTheme.typography.titleMedium,
                    color = TvOnSurfaceMuted,
                )
            }

            // Ein angefangener Film zeigt, wie weit er ist – sonst müsste man
            // ihn starten, nur um das herauszufinden.
            if (state.canResume && movie.durationSecs > 0) {
                Spacer(Modifier.height(TvSpacing.small))
                val totalMs = movie.durationSecs * 1000L
                ProgramProgressBar(
                    progress = (state.resumeMs.toFloat() / totalMs).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(0.5f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.movie_resume_at,
                        TimeFormat.position(state.resumeMs),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvOnSurfaceMuted,
                )
            }

            Spacer(Modifier.height(TvSpacing.medium))

            Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.small)) {
                if (state.canResume) {
                    Button(
                        onClick = onPlay,
                        modifier = Modifier
                            .touchClickable(onPlay)
                            .focusRequester(primaryButton),
                    ) {
                        Text(stringResource(R.string.movie_resume))
                    }
                    val fromStart = { viewModel.startFromBeginning(onPlay) }
                    Button(onClick = fromStart, modifier = Modifier.touchClickable(fromStart)) {
                        Text(stringResource(R.string.movie_from_start))
                    }
                } else {
                    Button(
                        onClick = onPlay,
                        modifier = Modifier
                            .touchClickable(onPlay)
                            .focusRequester(primaryButton),
                    ) {
                        Text(stringResource(R.string.movie_play))
                    }
                }
            }

            movie.plot?.takeIf { it.isNotBlank() }?.let { plot ->
                Spacer(Modifier.height(TvSpacing.large))
                Text(
                    text = stringResource(R.string.movie_plot),
                    style = MaterialTheme.typography.titleMedium,
                    color = TvAccent,
                )
                Spacer(Modifier.height(TvSpacing.small))
                Text(
                    text = plot,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TvOnSurfaceMuted,
                )
            }
        }
    }
}
