package de.xott.player.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.xott.player.core.TimeFormat
import de.xott.player.ui.theme.TvAccent
import de.xott.player.ui.theme.TvBackground
import de.xott.player.ui.theme.TvOnSurfaceMuted
import de.xott.player.ui.theme.TvSpacing
import de.xott.player.ui.theme.TvSurfaceVariant
import kotlinx.coroutines.launch

/**
 * Detailansicht für einen Film: Poster, Plot, Jahr/Bewertung/Laufzeit und
 * ein "Abspielen"-Button. Die Beschreibung wird beim Öffnen nachgeladen
 * (siehe [MovieDetailViewModel]), falls sie noch fehlt.
 */
@Composable
fun MovieDetailScreen(
    onPlay: (title: String, url: String) -> Unit,
    onBack: () -> Unit,
    viewModel: MovieDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(TvBackground)) {
        val movie = state.movie
        if (state.isLoading) {
            Text(
                "Lade…",
                color = TvOnSurfaceMuted,
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (movie == null) {
            Text(
                "Film nicht gefunden",
                color = TvOnSurfaceMuted,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(TvSpacing.overscanHorizontal, TvSpacing.overscanVertical),
            ) {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(TvSurfaceVariant),
                ) {
                    if (movie.posterUrl != null) {
                        AsyncImage(
                            model = movie.posterUrl,
                            contentDescription = movie.name,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Column(modifier = Modifier.padding(start = TvSpacing.large).fillMaxHeight()) {
                    Text(movie.name, style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(TvSpacing.small))

                    val meta = buildList {
                        movie.year?.let { add(it) }
                        if (movie.rating > 0.0) add("★ %.1f".format(movie.rating))
                        if (movie.durationSecs > 0) add(TimeFormat.duration(movie.durationSecs * 1000L))
                    }.joinToString("  ·  ")
                    if (meta.isNotBlank()) {
                        Text(meta, style = MaterialTheme.typography.bodyLarge, color = TvOnSurfaceMuted)
                        Spacer(Modifier.height(TvSpacing.medium))
                    }

                    PlayButton(
                        onClick = {
                            scope.launch {
                                viewModel.resolvePlaybackUrl()?.let { url -> onPlay(movie.name, url) }
                            }
                        },
                    )

                    Spacer(Modifier.height(TvSpacing.large))

                    movie.plot?.let { plot ->
                        Text(
                            plot,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TvOnSurfaceMuted,
                            modifier = Modifier.fillMaxWidth(0.8f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = TvSurfaceVariant,
            focusedContainerColor = TvAccent,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Abspielen", style = MaterialTheme.typography.titleMedium)
        }
    }
}
