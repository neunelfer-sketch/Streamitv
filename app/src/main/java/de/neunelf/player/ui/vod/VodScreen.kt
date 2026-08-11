package de.neunelf.player.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.neunelf.player.R
import de.neunelf.player.data.model.StreamKind
import de.neunelf.player.data.model.VodSort
import de.neunelf.player.ui.common.COMPACT_WIDTH_BREAKPOINT
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
    onPlayMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onPlayEpisode: (String) -> Unit = {},
    viewModel: VodViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var isSortMenuOpen by remember { mutableStateOf(false) }
    val sortButtonFocus = remember { FocusRequester() }

    LaunchedEffect(kind) { viewModel.setKind(kind) }

    // Nur solange das Menü offen ist: Zurück schließt es, statt den
    // Bildschirm zu verlassen. Ist es zu, bleibt die Taste unangetastet
    // und die Navigation verhält sich wie überall sonst.
    BackHandler(enabled = isSortMenuOpen) { isSortMenuOpen = false }

    // Schließt das Menü, verschwindet das gerade fokussierte Element aus der
    // Komposition – ohne Zutun bliebe der Fokus im Nichts hängen und die
    // Fernbedienung wirkungslos. Der Merker sorgt dafür, dass das nur beim
    // Schließen greift und nicht schon beim ersten Aufbau des Bildschirms
    // den Fokus vom Raster wegzieht.
    var wasSortMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(isSortMenuOpen) {
        if (!isSortMenuOpen && wasSortMenuOpen) {
            runCatching { sortButtonFocus.requestFocus() }
        }
        wasSortMenuOpen = isSortMenuOpen
    }

    Box(modifier = Modifier.fillMaxSize().background(TvBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            VodHeader(
                title = if (kind == StreamKind.SERIES) {
                    stringResource(R.string.content_series)
                } else {
                    stringResource(R.string.content_movies)
                },
                sortLabel = stringResource(state.sort.labelRes),
                buttonFocusRequester = sortButtonFocus,
                onOpenSortMenu = { isSortMenuOpen = true },
            )
            VodBody(
                state = state,
                kind = kind,
                onSelectCategory = viewModel::selectCategory,
                onPlayMovie = onPlayMovie,
                onOpenSeries = onOpenSeries,
                onPlayEpisode = onPlayEpisode,
            )
        }

        if (isSortMenuOpen) {
            SortMenu(
                current = state.sort,
                onSelect = {
                    viewModel.setSort(it)
                    isSortMenuOpen = false
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = TvSpacing.overscanHorizontal),
            )
        }
    }
}

/** Kopfzeile mit Bereichsnamen und dem Drei-Punkte-Knopf zum Sortieren. */
@Composable
private fun VodHeader(
    title: String,
    sortLabel: String,
    buttonFocusRequester: FocusRequester,
    onOpenSortMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(TvSurface)
            .padding(horizontal = TvSpacing.overscanHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)

        Spacer(Modifier.weight(1f))

        // Die aktive Reihenfolge steht neben dem Knopf: Sonst müsste man das
        // Menü öffnen, nur um zu sehen, wonach gerade sortiert ist.
        Text(
            text = sortLabel,
            style = MaterialTheme.typography.labelLarge,
            color = TvOnSurfaceMuted,
            modifier = Modifier.padding(end = TvSpacing.small),
        )

        Surface(
            onClick = onOpenSortMenu,
            modifier = Modifier.focusRequester(buttonFocusRequester),
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = TvAccent,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.vod_sort_action),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .size(24.dp),
            )
        }
    }
}

/**
 * Auswahlliste der Reihenfolgen.
 *
 * Bewusst kein `DropdownMenu` aus Material: Das ist auf Fokus per Zeiger
 * ausgelegt und lässt sich mit dem Steuerkreuz nur mühsam bedienen. Eine
 * schlichte Liste fokussierbarer Zeilen macht daraus ein Auf und Ab.
 */
@Composable
private fun SortMenu(
    current: VodSort,
    onSelect: (VodSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstEntry = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { firstEntry.requestFocus() } }

    Surface(
        modifier = modifier.width(280.dp),
        shape = RoundedCornerShape(8.dp),
        colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = TvSurfaceElevated),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Text(
                text = stringResource(R.string.vod_sort_by),
                style = MaterialTheme.typography.labelMedium,
                color = TvOnSurfaceMuted,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
            VodSort.entries.forEachIndexed { index, option ->
                Surface(
                    onClick = { onSelect(option) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .then(if (index == 0) Modifier.focusRequester(firstEntry) else Modifier),
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = TvAccent,
                    ),
                    scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(option.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (option == current) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.selected),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Kategorien links, Poster-Raster rechts. */
@Composable
private fun VodBody(
    state: VodUiState,
    kind: StreamKind,
    onSelectCategory: (String?) -> Unit,
    onPlayMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Auf einem Handy im Querformat bleibt für das Poster-Raster sonst
        // kaum Platz – siehe COMPACT_WIDTH_BREAKPOINT.
        val categoryWidth = if (maxWidth < COMPACT_WIDTH_BREAKPOINT) 200.dp else 280.dp

        Row(modifier = Modifier.fillMaxSize()) {
            // --- Kategorien --------------------------------------------------
            LazyColumn(
                modifier = Modifier
                    .width(categoryWidth)
                    .fillMaxHeight()
                    .background(TvSurface),
                contentPadding = PaddingValues(TvSpacing.small),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.categories, key = { it.id }) { category ->
                    Surface(
                        onClick = { onSelectCategory(category.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .onFocusChanged { if (it.isFocused) onSelectCategory(category.id) },
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

            // --- Raster --------------------------------------------------------
            LazyVerticalGrid(
                // Adaptiv statt einer festen Spaltenzahl: Bei fester Zahl würde
                // ein schmales Handy im Querformat sechs Poster in seine Breite
                // quetschen, bis Titel unlesbar werden. So bleibt die Postergröße
                // etwa gleich, und es passen einfach weniger Spalten hinein.
                columns = GridCells.Adaptive(minSize = 150.dp),
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
                        progress = item.progress,
                        onClick = {
                            when {
                                item.resumeEpisodeId != null -> onPlayEpisode(item.resumeEpisodeId)
                                kind == StreamKind.VOD -> onPlayMovie(item.id)
                                else -> onOpenSeries(item.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterCard(
    title: String,
    subtitle: String?,
    posterUrl: String?,
    progress: Float?,
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

                // Fortsetzpunkt aus "Zuletzt gesehen" – am unteren Bildrand,
                // wie bei TiviMate und Netflix, damit man ihn nicht suchen muss.
                if (progress != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.4f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(TvAccent),
                        )
                    }
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
