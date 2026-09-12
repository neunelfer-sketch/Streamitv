package de.xott.player.ui.home

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.xott.player.core.TimeFormat
import de.xott.player.data.model.Channel
import de.xott.player.data.model.ChannelWithProgram
import de.xott.player.ui.components.ChannelListItem
import de.xott.player.ui.components.ChannelLogo
import de.xott.player.ui.components.ProgramProgressBar
import de.xott.player.ui.theme.TvAccent
import de.xott.player.ui.theme.TvBackground
import de.xott.player.ui.theme.TvOnSurfaceMuted
import de.xott.player.ui.theme.TvSpacing
import de.xott.player.ui.theme.TvSurface
import de.xott.player.ui.theme.TvSurfaceVariant

/**
 * Hauptbildschirm im TiviMate-Layout.
 *
 * ```
 * ┌──────────────────────────────────────────────────────────────────┐
 * │  Live TV │ Filme │ Serien │ TV-Guide │ ⚙        Playlist   20:15 │
 * ├────────────┬───────────────────────┬─────────────────────────────┤
 * │ Kategorien │ Sender                │ Vorschau                    │
 * │ ▸ Alle     │ 12 ▸ RTL HD           │  ┌───────────────────────┐  │
 * │ ▸ Favoriten│      20:15 Wer wird…  │  │      Live-Bild        │  │
 * │ ▸ Deutsch  │ 13   Sat.1 HD         │  └───────────────────────┘  │
 * │ ▸ Sport    │      20:15 Navy CIS   │  Wer wird Millionär?        │
 * │            │                       │  20:15 – 21:45 · noch 23 M. │
 * │            │                       │  Danach: 21:45 Spiegel TV   │
 * └────────────┴───────────────────────┴─────────────────────────────┘
 * ```
 *
 * Fokusführung (alles über das Steuerkreuz):
 * - **Rechts/Links** wechselt zwischen den drei Spalten.
 * - **Hoch/Runter** bewegt sich innerhalb einer Spalte.
 * - **OK** auf einem Sender öffnet den Vollbild-Player.
 * - **Lange OK** setzt/entfernt einen Favoriten.
 */
@Composable
fun HomeScreen(
    onOpenPlayer: (Channel) -> Unit,
    onOpenGuide: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeries: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val channelListFocus = remember { FocusRequester() }

    // Beim Betreten soll der Fokus auf der Senderliste stehen – nicht auf der
    // Kopfzeile. Sonst muss der Nutzer bei jedem Start zweimal nach unten.
    LaunchedEffect(state.channels.isNotEmpty()) {
        if (state.channels.isNotEmpty()) {
            runCatching { channelListFocus.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBackground),
    ) {
        HomeTopBar(
            playlistName = state.playlist?.name.orEmpty(),
            statusMessage = state.syncMessage ?: state.errorMessage,
            isError = state.errorMessage != null,
            onOpenGuide = onOpenGuide,
            onOpenMovies = onOpenMovies,
            onOpenSeries = onOpenSeries,
            onOpenSettings = onOpenSettings,
        )

        Row(modifier = Modifier.fillMaxSize()) {

            // ---------------- Spalte 1: Kategorien ----------------
            CategoryColumn(
                categories = state.categories,
                selectedKey = state.selectedCategoryKey,
                onSelect = viewModel::selectCategory,
                modifier = Modifier
                    .width(260.dp)
                    .fillMaxHeight()
                    .background(TvSurface),
            )

            // ---------------- Spalte 2: Sender ----------------
            ChannelColumn(
                channels = state.channels,
                isLoading = state.isLoading,
                focusRequester = channelListFocus,
                onChannelClick = { channel ->
                    viewModel.markWatched(channel)
                    onOpenPlayer(channel)
                },
                onChannelFocused = viewModel::onChannelFocused,
                onToggleFavorite = viewModel::toggleFavorite,
                modifier = Modifier
                    .width(460.dp)
                    .fillMaxHeight()
                    .background(TvSurface.copy(alpha = 0.5f)),
            )

            // ---------------- Spalte 3: Vorschau + Details ----------------
            DetailPane(
                item = state.focusedChannel,
                upcoming = state.upcoming,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(TvSpacing.large),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Kopfzeile
// ---------------------------------------------------------------------------

@Composable
private fun HomeTopBar(
    playlistName: String,
    statusMessage: String?,
    isError: Boolean,
    onOpenGuide: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeries: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // Die Uhr rechts oben ist auf einem TV überraschend wichtig – viele
    // Nutzer schauen dort statt aufs Handy. Sie muss deshalb tatsächlich
    // laufen; einmal beim Aufbau berechnen reicht nicht.
    val clock by produceState(initialValue = TimeFormat.clock(System.currentTimeMillis())) {
        while (true) {
            value = TimeFormat.clock(System.currentTimeMillis())
            kotlinx.coroutines.delay(20_000L)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(TvSurface)
            .padding(horizontal = TvSpacing.overscanHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarAction(Icons.Default.LiveTv, "Live TV", isActive = true, onClick = {})
        TopBarAction(Icons.Default.CalendarMonth, "TV-Guide", onClick = onOpenGuide)
        TopBarAction(Icons.Default.Movie, "Filme", onClick = onOpenMovies)
        TopBarAction(Icons.Default.Subscriptions, "Serien", onClick = onOpenSeries)
        TopBarAction(Icons.Default.Settings, "Einstellungen", onClick = onOpenSettings)

        Spacer(Modifier.weight(1f))

        if (statusMessage != null) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else TvOnSurfaceMuted,
                modifier = Modifier.padding(end = TvSpacing.medium),
            )
        }

        Text(
            text = playlistName,
            style = MaterialTheme.typography.bodyMedium,
            color = TvOnSurfaceMuted,
            modifier = Modifier.padding(end = TvSpacing.medium),
        )
        Text(text = clock, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TopBarAction(
    icon: ImageVector,
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .padding(end = TvSpacing.small)
            .onFocusChanged { isFocused = it.isFocused },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (isActive) TvAccent.copy(alpha = 0.2f) else Color.Transparent,
            focusedContainerColor = TvAccent,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
            // Beschriftung nur beim fokussierten Eintrag – hält die Leiste
            // schmal und lenkt weniger vom Inhalt ab.
            if (isFocused || isActive) {
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Spalte 1: Kategorien
// ---------------------------------------------------------------------------

@Composable
private fun CategoryColumn(
    categories: List<CategoryItem>,
    selectedKey: String?,
    onSelect: (CategoryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.padding(vertical = TvSpacing.small),
        contentPadding = PaddingValues(horizontal = TvSpacing.small),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(categories, key = { it.key }) { category ->
            CategoryRow(
                item = category,
                isSelected = category.key == selectedKey,
                // Wichtig: die Auswahl folgt dem *Fokus*, nicht erst dem Klick.
                // Genau so verhält sich TiviMate – ein Druck weniger pro Wechsel.
                onFocused = { onSelect(category) },
                onClick = { onSelect(category) },
            )
        }
    }
}

@Composable
private fun CategoryRow(
    item: CategoryItem,
    isSelected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) TvSurfaceVariant else Color.Transparent,
            focusedContainerColor = TvAccent,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onSurface else TvOnSurfaceMuted,
            focusedContentColor = Color.White,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.count > 0) {
                Text(
                    text = item.count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Spalte 2: Sender
// ---------------------------------------------------------------------------

@Composable
private fun ChannelColumn(
    channels: List<ChannelWithProgram>,
    isLoading: Boolean,
    focusRequester: FocusRequester,
    onChannelClick: (Channel) -> Unit,
    onChannelFocused: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    Box(modifier = modifier) {
        when {
            isLoading -> CenteredHint("Lade Sender…")
            channels.isEmpty() -> CenteredHint("Keine Sender in dieser Kategorie")
            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester),
                contentPadding = PaddingValues(
                    horizontal = TvSpacing.small,
                    vertical = TvSpacing.small,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(channels, key = { it.channel.streamId }) { item ->
                    ChannelListItem(
                        item = item,
                        isPlaying = false,
                        onClick = { onChannelClick(item.channel) },
                        onFocused = { onChannelFocused(item.channel) },
                        onLongClick = { onToggleFavorite(item.channel) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Spalte 3: Vorschau und Programmdetails
// ---------------------------------------------------------------------------

@Composable
private fun DetailPane(
    item: ChannelWithProgram?,
    upcoming: List<de.xott.player.data.model.EpgProgram>,
    modifier: Modifier = Modifier,
) {
    if (item == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            CenteredHint("Sender auswählen")
        }
        return
    }

    val channel = item.channel
    val current = item.current

    Column(modifier = modifier) {
        // --- Vorschaufläche -------------------------------------------------
        // Hier läuft im fertigen Aufbau das Live-Bild des fokussierten Senders
        // (siehe PreviewPlayer). Solange nichts gestartet ist, zeigen wir das
        // Senderlogo – ein leerer schwarzer Kasten wirkt wie ein Fehler.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(
                logoUrl = channel.logoUrl,
                contentDescription = channel.name,
                modifier = Modifier.size(120.dp),
            )
        }

        Spacer(Modifier.height(TvSpacing.medium))

        Text(
            text = channel.name,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (current != null) {
            Spacer(Modifier.height(TvSpacing.small))
            Text(
                text = current.title,
                style = MaterialTheme.typography.titleLarge,
                color = TvAccent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${TimeFormat.range(current.startAt, current.endAt)} · ${TimeFormat.remaining(current.endAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = TvOnSurfaceMuted,
            )
            Spacer(Modifier.height(TvSpacing.small))
            ProgramProgressBar(
                progress = current.progressAt(System.currentTimeMillis()),
                modifier = Modifier.fillMaxWidth(),
            )

            current.description?.let { description ->
                Spacer(Modifier.height(TvSpacing.medium))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvOnSurfaceMuted,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Spacer(Modifier.height(TvSpacing.small))
            Text(
                text = "Keine Programminformationen",
                style = MaterialTheme.typography.bodyMedium,
                color = TvOnSurfaceMuted,
            )
        }

        // --- Was danach kommt ----------------------------------------------
        if (upcoming.size > 1) {
            Spacer(Modifier.height(TvSpacing.large))
            Text(
                text = "Danach",
                style = MaterialTheme.typography.titleMedium,
                color = TvOnSurfaceMuted,
            )
            Spacer(Modifier.height(TvSpacing.small))
            upcoming.drop(1).take(4).forEach { program ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(
                        text = TimeFormat.clock(program.startAt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvOnSurfaceMuted,
                        modifier = Modifier.width(60.dp),
                    )
                    Text(
                        text = program.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = TvOnSurfaceMuted,
        )
    }
}
