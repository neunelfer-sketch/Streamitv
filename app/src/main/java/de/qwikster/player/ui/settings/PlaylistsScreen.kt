package de.qwikster.player.ui.settings

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import de.qwikster.player.data.model.Playlist
import de.qwikster.player.data.model.PlaylistType
import de.qwikster.player.ui.common.touchClickable
import de.qwikster.player.ui.theme.TvAccent
import de.qwikster.player.ui.theme.TvBackground
import de.qwikster.player.ui.theme.TvOn
import de.qwikster.player.ui.theme.TvOnSurfaceMuted
import de.qwikster.player.ui.theme.TvSpacing
import de.qwikster.player.ui.theme.TvSurface

/**
 * Alle hinterlegten Playlists, mit der aktiven an erkennbarer Stelle.
 *
 * OK schaltet um, langes OK entfernt. Bewusst dieselbe Aufteilung wie bei
 * "Weiterschauen": Die häufige Handlung liegt auf der kurzen Taste, die
 * seltene und folgenreiche auf der langen.
 *
 * Ein Wechsel lädt nichts neu. Die Inhalte aller Playlists liegen
 * nebeneinander in der Datenbank; umgeschaltet wird nur, worauf die
 * Abfragen zeigen. Wer zwischen zwei Zugängen hin- und herwechselt, wartet
 * also nicht jedes Mal auf einen Import.
 */
@Composable
fun PlaylistsScreen(
    onBack: () -> Unit,
    onAddPlaylist: () -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val firstRow = remember { FocusRequester() }

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(state.playlists.isNotEmpty()) {
        if (state.playlists.isNotEmpty()) runCatching { firstRow.requestFocus() }
    }

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
            text = stringResource(R.string.playlists_title),
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = stringResource(R.string.playlists_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = TvOnSurfaceMuted,
        )
        Spacer(Modifier.height(TvSpacing.medium))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = TvSpacing.large),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.playlists, key = { it.id }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    isActive = playlist.id == state.activeId,
                    onClick = { viewModel.select(playlist) },
                    // Die letzte verbliebene Playlist lässt sich hier nicht
                    // entfernen: Ohne eine einzige stünde der Zuschauer
                    // wieder vor der Ersteinrichtung, und dafür gibt es in
                    // den Einstellungen den ausdrücklichen Weg.
                    onLongClick = if (state.playlists.size > 1) {
                        { viewModel.remove(playlist) }
                    } else {
                        null
                    },
                    modifier = if (playlist.id == state.playlists.firstOrNull()?.id) {
                        Modifier.focusRequester(firstRow)
                    } else {
                        Modifier
                    },
                )
            }

            item {
                Spacer(Modifier.height(TvSpacing.small))
                AddPlaylistRow(onClick = onAddPlaylist)
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .touchClickable(onClick, onLongClick),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = TvSurface,
            focusedContainerColor = TvAccent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = Color.White,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // Die Adresse ohne Zugangsdaten: Sie unterscheidet zwei
                    // Einträge desselben Anbieters, und Benutzername samt
                    // Passwort haben auf einem Fernseher nichts verloren –
                    // im Wohnzimmer sitzt selten nur einer.
                    text = when (playlist.type) {
                        PlaylistType.XTREAM -> playlist.serverUrl
                        PlaylistType.M3U -> playlist.m3uUrl.substringBefore('?')
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = TvOnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isActive) {
                Spacer(Modifier.width(TvSpacing.small))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.playlists_active),
                    tint = TvOn,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun AddPlaylistRow(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .touchClickable(onClick),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = TvAccent.copy(alpha = 0.18f),
            focusedContainerColor = TvAccent,
            contentColor = TvAccent,
            focusedContentColor = Color.White,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(TvSpacing.small))
            Text(
                text = stringResource(R.string.playlists_add),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
