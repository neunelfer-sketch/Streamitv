package de.neunelf.player.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.neunelf.player.core.TimeFormat
import de.neunelf.player.data.model.Episode
import de.neunelf.player.ui.theme.TvAccent
import de.neunelf.player.ui.theme.TvBackground
import de.neunelf.player.ui.theme.TvOnSurfaceMuted
import de.neunelf.player.ui.theme.TvSpacing
import de.neunelf.player.ui.theme.TvSurfaceElevated
import de.neunelf.player.ui.theme.TvSurfaceVariant

/**
 * Staffel-/Episodenübersicht einer Serie.
 *
 * Links Poster und Beschreibung, rechts die Episoden gruppiert nach Staffel –
 * ein Druck auf OK bei einer Episode startet sie direkt im Player.
 */
@Composable
fun SeriesDetailScreen(
    onPlayEpisode: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SeriesDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = true) { onBack() }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBackground),
    ) {
        // --- Poster und Beschreibung ------------------------------------
        Column(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
                .padding(TvSpacing.large),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TvSurfaceVariant),
            ) {
                state.series?.posterUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = state.series?.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.height(TvSpacing.medium))
            Text(
                text = state.series?.name.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            state.series?.year?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = TvOnSurfaceMuted)
            }
            state.series?.plot?.takeIf { it.isNotBlank() }?.let { plot ->
                Spacer(Modifier.height(TvSpacing.small))
                Text(
                    text = plot,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvOnSurfaceMuted,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // --- Episoden nach Staffel ---------------------------------------
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(TvSpacing.large),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when {
                state.isLoading && state.episodesBySeason.isEmpty() -> item {
                    Text(
                        "Lade Episoden…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TvOnSurfaceMuted,
                    )
                }

                state.episodesBySeason.isEmpty() -> item {
                    Text(
                        "Keine Episoden gefunden",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TvOnSurfaceMuted,
                    )
                }

                else -> state.episodesBySeason.forEach { (season, episodes) ->
                    item(key = "season-$season") {
                        Text(
                            text = "Staffel $season",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = TvSpacing.small, bottom = 4.dp),
                        )
                    }
                    items(episodes, key = { it.episodeId }) { episode ->
                        EpisodeRow(episode = episode, onClick = { onPlayEpisode(episode.episodeId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = TvSurfaceElevated,
            focusedContainerColor = TvAccent,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${episode.episodeNumber}",
                style = MaterialTheme.typography.titleMedium,
                color = TvOnSurfaceMuted,
                modifier = Modifier.width(32.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episode.durationSecs > 0) {
                    Text(
                        text = TimeFormat.duration(episode.durationSecs * 1000L),
                        style = MaterialTheme.typography.labelMedium,
                        color = TvOnSurfaceMuted,
                    )
                }
            }
        }
    }
}
