package de.neunelf.player.ui.search

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.neunelf.player.data.model.Channel
import de.neunelf.player.ui.components.ChannelLogo
import de.neunelf.player.ui.theme.TvAccent
import de.neunelf.player.ui.theme.TvBackground
import de.neunelf.player.ui.theme.TvOnSurface
import de.neunelf.player.ui.theme.TvOnSurfaceMuted
import de.neunelf.player.ui.theme.TvSpacing
import de.neunelf.player.ui.theme.TvSurfaceVariant

/**
 * Übergreifende Suche über Sender, Filme und Serien.
 *
 * Anders als die Eingabefelder der Ersteinrichtung öffnet dieses Feld die
 * Bildschirmtastatur sofort und behält den Fokus: Wer diesen Bildschirm
 * aufruft, will tippen – ein zusätzlicher Druck auf OK wäre hier nur im
 * Weg. Bei der Einrichtung ist es umgekehrt, dort wandert man mit dem
 * Steuerkreuz durch mehrere Felder.
 *
 * Die Treffer stehen in einer einzigen Liste, nach Bereichen gruppiert.
 * Ein Raster nebeneinander sähe aufgeräumter aus, zwänge aber bei jeder
 * Suche zur Entscheidung, welche Spalte den Fokus bekommt.
 */
@Composable
fun SearchScreen(
    onPlayChannel: (Channel) -> Unit,
    onPlayMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val inputFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { inputFocus.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBackground)
            .padding(
                horizontal = TvSpacing.overscanHorizontal,
                vertical = TvSpacing.overscanVertical,
            ),
    ) {
        Text("Suche", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(TvSpacing.small))

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            label = { androidx.compose.material3.Text("Titel oder Sender") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Search,
                autoCorrectEnabled = false,
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = TvSurfaceVariant,
                unfocusedContainerColor = TvSurfaceVariant,
                focusedIndicatorColor = TvAccent,
                unfocusedIndicatorColor = TvOnSurfaceMuted,
                focusedTextColor = TvOnSurface,
                unfocusedTextColor = TvOnSurface,
                focusedLabelColor = TvAccent,
                unfocusedLabelColor = TvOnSurfaceMuted,
            ),
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .focusRequester(inputFocus),
        )

        Spacer(Modifier.height(TvSpacing.medium))

        when {
            !state.hasQuery -> Hint("Titel eingeben – gesucht wird in Sendern, Filmen und Serien.")
            state.isEmpty -> Hint("Nichts gefunden für „${state.query}“.")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = TvSpacing.large),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (state.channels.isNotEmpty()) {
                    item(key = "h-live") { SectionHeader("Sender", state.channels.size) }
                    items(state.channels, key = { "c-${it.streamId}" }) { channel ->
                        ResultRow(
                            title = channel.name,
                            subtitle = channel.number.takeIf { it > 0 }?.let { "Platz $it" },
                            icon = Icons.Default.LiveTv,
                            logoUrl = channel.logoUrl,
                            onClick = { onPlayChannel(channel) },
                        )
                    }
                }
                if (state.movies.isNotEmpty()) {
                    item(key = "h-vod") { SectionHeader("Filme", state.movies.size) }
                    items(state.movies, key = { "m-${it.streamId}" }) { movie ->
                        ResultRow(
                            title = movie.name,
                            subtitle = movie.year,
                            icon = Icons.Default.Movie,
                            logoUrl = movie.posterUrl,
                            onClick = { onPlayMovie(movie.streamId) },
                        )
                    }
                }
                if (state.series.isNotEmpty()) {
                    item(key = "h-series") { SectionHeader("Serien", state.series.size) }
                    items(state.series, key = { "s-${it.seriesId}" }) { series ->
                        ResultRow(
                            title = series.name,
                            subtitle = series.year,
                            icon = Icons.Default.Subscriptions,
                            logoUrl = series.posterUrl,
                            onClick = { onOpenSeries(series.seriesId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Column {
        Spacer(Modifier.height(TvSpacing.medium))
        Text(
            text = "${title.uppercase()}  ·  $count",
            style = MaterialTheme.typography.labelMedium,
            color = TvOnSurfaceMuted,
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ResultRow(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    logoUrl: String?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = TvAccent,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            ChannelLogo(
                logoUrl = logoUrl,
                contentDescription = title,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
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

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = TvOnSurfaceMuted)
    }
}
