package de.neunelf.player.ui.vod

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.neunelf.player.data.model.Episode
import de.neunelf.player.ui.theme.TvAccent
import de.neunelf.player.ui.theme.TvBackground
import de.neunelf.player.ui.theme.TvOnSurfaceMuted
import de.neunelf.player.ui.theme.TvSpacing
import de.neunelf.player.ui.theme.TvSurfaceVariant
import kotlinx.coroutines.launch

/**
 * Detailansicht einer Serie: Poster/Plot links, Staffel-Umschalter und
 * Episodenliste rechts. Episoden werden beim ersten Öffnen nachgeladen
 * (siehe [SeriesDetailViewModel]) – Panels liefern sie nicht im Listing mit.
 */
@Composable
fun SeriesDetailScreen(
    onPlayEpisode: (title: String, url: String) -> Unit,
    onBack: () -> Unit,
    viewModel: SeriesDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(TvBackground)) {
        val series = state.series
        if (state.isLoading || series == null) {
            Text(
                if (state.isLoading) "Lade…" else "Serie nicht gefunden",
                color = TvOnSurfaceMuted,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(TvSpacing.overscanHorizontal, TvSpacing.overscanVertical),
        ) {
            // --- Poster + Plot ---------------------------------------------
            Column(modifier = Modifier.width(280.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TvSurfaceVariant),
                ) {
                    if (series.posterUrl != null) {
                        AsyncImage(
                            model = series.posterUrl,
                            contentDescription = series.name,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.height(TvSpacing.medium))
                Text(series.name, style = MaterialTheme.typography.headlineMedium)
                series.plot?.let {
                    Spacer(Modifier.height(TvSpacing.small))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvOnSurfaceMuted,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // --- Staffeln + Episoden -----------------------------------------
            Column(modifier = Modifier.padding(start = TvSpacing.large).fillMaxHeight()) {
                if (state.seasons.isEmpty()) {
                    Text("Keine Episoden gefunden", color = TvOnSurfaceMuted)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.small)) {
                        state.seasons.forEach { season ->
                            SeasonChip(
                                label = "Staffel $season",
                                isSelected = season == state.selectedSeason,
                                onClick = { viewModel.selectSeason(season) },
                            )
                        }
                    }
                    Spacer(Modifier.height(TvSpacing.medium))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = TvSpacing.large),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(state.visibleEpisodes, key = { it.episodeId }) { episode ->
                            EpisodeRow(
                                episode = episode,
                                onClick = {
                                    scope.launch {
                                        viewModel.resolveEpisodeUrl(episode)?.let { url ->
                                            onPlayEpisode("${series.name} · ${episode.title}", url)
                                        }
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
private fun SeasonChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) TvAccent.copy(alpha = 0.35f) else TvSurfaceVariant,
            focusedContainerColor = TvAccent,
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun EpisodeRow(episode: Episode, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = TvSurfaceVariant,
            focusedContainerColor = TvAccent,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = TvSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${episode.episodeNumber}.",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(40.dp),
            )
            Text(
                episode.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
