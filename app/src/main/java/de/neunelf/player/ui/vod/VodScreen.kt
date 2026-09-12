package de.neunelf.player.ui.vod

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.neunelf.player.data.model.StreamKind
import de.neunelf.player.ui.theme.TvAccent
import de.neunelf.player.ui.theme.TvBackground
import de.neunelf.player.ui.theme.TvOnSurfaceMuted
import de.neunelf.player.ui.theme.TvSpacing
import de.neunelf.player.ui.theme.TvSurface
import de.neunelf.player.ui.theme.TvSurfaceElevated
import de.neunelf.player.ui.theme.TvSurfaceVariant

/**
 * Übersicht für Filme und Serien.
 *
 * Beide Bereiche benutzen dieselbe Ansicht – Kategorien links, ein
 * Poster-Raster rechts. Der einzige Unterschied ist die Datenquelle,
 * gesteuert über [StreamKind].
 *
 * Poster werden im Verhältnis 2:3 dargestellt (Kinoplakat-Format); das
 * entspricht dem, was Xtream-Panels liefern, und vermeidet Verzerrungen.
 */
@Composable
fun VodScreen(
    kind: StreamKind,
    onOpenMovie: (playlistId: Long, streamId: String) -> Unit,
    onOpenSeries: (playlistId: Long, seriesId: String) -> Unit,
    viewModel: VodViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(kind) { viewModel.setKind(kind) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBackground),
    ) {
        // --- Kategorien ------------------------------------------------------
        LazyColumn(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(TvSurface),
            contentPadding = PaddingValues(TvSpacing.small),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(state.categories, key = { it.id }) { category ->
                Surface(
                    onClick = { viewModel.selectCategory(category.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .onFocusChanged { if (it.isFocused) viewModel.selectCategory(category.id) },
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = if (category.id == state.selectedCategoryId) {
                            TvSurfaceVariant
                        } else {
                            Color.Transparent
                        },
                        focusedContainerColor = TvAccent,
                    ),
                    scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // --- Raster ----------------------------------------------------------
        LazyVerticalGrid(
            // Feste Spaltenzahl statt adaptiver Breite: auf TV ist die
            // Bildschirmgröße bekannt, und ein stabiles Raster macht die
            // Navigation mit dem Steuerkreuz vorhersagbar.
            columns = GridCells.Fixed(6),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(TvSpacing.large),
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.medium),
        ) {
            items(state.items, key = { it.id }) { item ->
                PosterCard(
                    title = item.title,
                    subtitle = item.subtitle,
                    posterUrl = item.posterUrl,
                    onClick = {
                        if (kind == StreamKind.VOD) {
                            onOpenMovie(item.playlistId, item.id)
                        } else {
                            onOpenSeries(item.playlistId, item.id)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PosterCard(
    title: String,
    subtitle: String?,
    posterUrl: String?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = TvSurfaceElevated,
            focusedContainerColor = TvSurfaceElevated,
        ),
        // Hier ist eine Vergrößerung sinnvoll: im Raster gibt es genug Luft,
        // und der aktive Poster hebt sich dadurch klar ab.
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, TvAccent),
                shape = RoundedCornerShape(10.dp),
            ),
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .background(TvSurfaceVariant),
            ) {
                if (posterUrl != null) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = TvOnSurfaceMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
