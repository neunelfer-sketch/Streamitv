package de.qwikster.player.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.qwikster.player.R
import de.qwikster.player.core.TimeFormat
import de.qwikster.player.data.model.Channel
import de.qwikster.player.data.model.ChannelWithProgram
import de.qwikster.player.ui.common.COMPACT_WIDTH_BREAKPOINT
import de.qwikster.player.ui.components.ChannelListItem
import de.qwikster.player.ui.components.ChannelLogo
import de.qwikster.player.ui.components.ProgramProgressBar
import de.qwikster.player.ui.settings.UpdateUiState
import de.qwikster.player.ui.theme.TvAccent
import de.qwikster.player.ui.theme.TvBackground
import de.qwikster.player.ui.theme.TvLive
import de.qwikster.player.ui.theme.TvOnSurfaceMuted
import de.qwikster.player.ui.theme.TvSpacing
import de.qwikster.player.ui.theme.TvSurface
import de.qwikster.player.ui.theme.TvSurfaceElevated
import de.qwikster.player.ui.theme.TvSurfaceVariant

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
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val channelListFocus = remember { FocusRequester() }
    val previewPlayer = remember { viewModel.previewPlayer() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Beim Betreten soll der Fokus auf der Senderliste stehen – nicht auf der
    // Kopfzeile. Sonst muss der Nutzer bei jedem Start zweimal nach unten.
    LaunchedEffect(state.channels.isNotEmpty()) {
        if (state.channels.isNotEmpty()) {
            runCatching { channelListFocus.requestFocus() }
        }
    }

    // Die Vorschau läuft nur, solange dieser Bildschirm auch vorne ist.
    //
    // Zwei verschiedene Fälle, die beide abgedeckt sein müssen:
    // - **Wechsel ins Vollbild.** Der Hauptbildschirm verlässt dabei die
    //   Komposition (`onDispose`), die Activity selbst pausiert aber nicht.
    //   Ohne das Anhalten liefen zwei Streams gleichzeitig – bei Panels mit
    //   wenigen erlaubten Verbindungen bricht dann ausgerechnet das
    //   Vollbild ab.
    // - **App in den Hintergrund** (Home-Taste). Da endet die Komposition
    //   nicht, dafür kommt ON_PAUSE.
    //
    // Freigegeben wird der Player hier bewusst *nicht*, sondern erst in
    // `HomeViewModel.onCleared`: Ob die `PlayerView` oder dieser Effekt
    // zuerst abgeräumt wird, ist nicht festgelegt – eine noch angebundene
    // Ansicht auf einem bereits freigegebenen Player beendet die App.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setPreviewEnabled(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setPreviewEnabled(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setPreviewEnabled(false)
        }
    }

    // Nur "Zeigt gerade nichts an" lässt Zurück normal wirken – sowohl der
    // "Was ist neu"-Hinweis als auch der Update-Vorschlag sollen sich
    // ausschließlich über ihre eigenen Knöpfe schließen lassen, nicht über
    // Zurück (das würde sonst stattdessen die App verlassen).
    val isUpdatePromptVisible = !state.showWhatsNew && state.updatePrompt != UpdateUiState.Unknown
    BackHandler(enabled = state.showWhatsNew || isUpdatePromptVisible) { }

    Box(modifier = Modifier.fillMaxSize().background(TvBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
        ) {
        HomeTopBar(
            playlistName = state.playlist?.name.orEmpty(),
            // Der Update-Hinweis steht hinten an: Was gerade lädt oder
            // schiefging, ist dringlicher als eine verfügbare neue Fassung.
            statusMessage = state.syncMessage
                ?: state.errorMessage
                ?: state.updateVersion?.let {
                    stringResource(R.string.home_update_available, it)
                },
            isError = state.errorMessage != null,
            onOpenGuide = onOpenGuide,
            onOpenMovies = onOpenMovies,
            onOpenSeries = onOpenSeries,
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Auf einem Handy im Querformat bleibt für die Vorschau sonst
            // kaum Platz – siehe COMPACT_WIDTH_BREAKPOINT.
            val isCompact = maxWidth < COMPACT_WIDTH_BREAKPOINT
            val categoryWidth = if (isCompact) 180.dp else 260.dp
            val channelWidth = if (isCompact) 260.dp else 460.dp

            Row(modifier = Modifier.fillMaxSize()) {

                // ---------------- Spalte 1: Kategorien ----------------
                CategoryColumn(
                    categories = state.categories,
                    selectedKey = state.selectedCategoryKey,
                    onSelect = viewModel::selectCategory,
                    modifier = Modifier
                        .width(categoryWidth)
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
                        .width(channelWidth)
                        .fillMaxHeight()
                        .background(TvSurface.copy(alpha = 0.5f)),
                )

                // ---------------- Spalte 3: Vorschau + Details ----------------
                DetailPane(
                    item = state.focusedChannel,
                    upcoming = state.upcoming,
                    previewPlayer = previewPlayer,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(TvSpacing.large),
                )
            }
        }
    }

        if (state.showWhatsNew) {
            WhatsNewDialog(onDismiss = viewModel::dismissWhatsNew)
        } else if (isUpdatePromptVisible) {
            UpdatePromptDialog(
                state = state.updatePrompt,
                onInstallNow = viewModel::installUpdateNow,
                onLater = viewModel::dismissUpdatePrompt,
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
    onOpenSearch: () -> Unit,
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
        TopBarAction(
            Icons.Default.LiveTv,
            stringResource(R.string.nav_live),
            isActive = true,
            onClick = {},
        )
        TopBarAction(
            Icons.Default.CalendarMonth,
            stringResource(R.string.nav_guide),
            onClick = onOpenGuide,
        )
        TopBarAction(
            Icons.Default.Movie,
            stringResource(R.string.content_movies),
            onClick = onOpenMovies,
        )
        TopBarAction(
            Icons.Default.Subscriptions,
            stringResource(R.string.content_series),
            onClick = onOpenSeries,
        )
        TopBarAction(
            Icons.Default.Search,
            stringResource(R.string.search_title),
            onClick = onOpenSearch,
        )
        TopBarAction(
            Icons.Default.Settings,
            stringResource(R.string.settings_title),
            onClick = onOpenSettings,
        )

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

/**
 * Beschriftung eines Kategorie-Eintrags.
 *
 * Die drei virtuellen Kategorien sind Oberflächentext und werden übersetzt;
 * der Name einer echten Kategorie stammt aus der Playlist des Nutzers und
 * bleibt deshalb unverändert stehen.
 */
@Composable
private fun CategoryItem.label(): String = when (this) {
    is CategoryItem.All -> stringResource(R.string.category_all)
    is CategoryItem.Favorites -> stringResource(R.string.category_favorites)
    is CategoryItem.Recent -> stringResource(R.string.category_recent)
    is CategoryItem.Group -> category.name
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
                text = item.label(),
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
            isLoading -> CenteredHint(stringResource(R.string.home_loading_channels))
            channels.isEmpty() -> CenteredHint(stringResource(R.string.home_no_channels))
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
    upcoming: List<de.qwikster.player.data.model.EpgProgram>,
    previewPlayer: ExoPlayer?,
    modifier: Modifier = Modifier,
) {
    if (item == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            CenteredHint(stringResource(R.string.home_select_channel))
        }
        return
    }

    val channel = item.channel
    val current = item.current

    Column(modifier = modifier) {
        // --- Vorschaufläche -------------------------------------------------
        // Das Live-Bild des fokussierten Senders. Das Senderlogo liegt
        // darunter und bleibt sichtbar, solange noch kein Bild da ist –
        // ein leerer schwarzer Kasten wirkte wie ein Fehler.
        //
        // Bewusst im 16:9-Format statt einer festen Höhe: Bei einer festen
        // Höhe wirkte die Fläche auf breiten Bildschirmen klein und
        // gequetscht. Der Rahmen und der weiche Schatten heben sie sichtbar
        // vom Hintergrund ab, ohne dass Text oder andere Elemente sie
        // überlagern – die Fläche selbst bleibt so immer ungestört.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .shadow(elevation = 10.dp, shape = RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .border(1.dp, TvSurfaceVariant, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(
                logoUrl = channel.logoUrl,
                contentDescription = channel.name,
                modifier = Modifier.size(120.dp),
            )

            if (previewPlayer != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            // Ohne das bliebe zwischen zwei Sendern kurz das
                            // letzte Bild des vorherigen stehen.
                            setKeepContentOnPlayerReset(false)
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            player = previewPlayer
                        }
                    },
                    update = { view -> view.player = previewPlayer },
                    onRelease = { view -> view.player = null },
                )

                LiveBadge(modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
            }
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
                text = stringResource(
                    R.string.program_time_remaining,
                    TimeFormat.range(current.startAt, current.endAt),
                    TimeFormat.remaining(LocalContext.current, current.endAt),
                ),
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
                text = stringResource(R.string.player_no_program),
                style = MaterialTheme.typography.bodyMedium,
                color = TvOnSurfaceMuted,
            )
        }

        // --- Was danach kommt ----------------------------------------------
        if (upcoming.size > 1) {
            Spacer(Modifier.height(TvSpacing.large))
            Text(
                text = stringResource(R.string.program_up_next),
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

/**
 * Kleiner Hinweis oben links auf der Vorschaufläche – rein dekorativ, also
 * bewusst ein einfacher [Box] statt einer fokussierbaren `Surface`: Er darf
 * dem Steuerkreuz niemals im Weg stehen.
 */
@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(TvLive),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}

// ---------------------------------------------------------------------------
// "Was ist neu"-Hinweis
// ---------------------------------------------------------------------------

/**
 * Kurzer, einmaliger Hinweis nach einer Aktualisierung.
 *
 * Bewusst kein Startbildschirm-Dialog bei jedem Öffnen (siehe
 * [HomeViewModel]s Kommentar zur Update-Prüfung) – er erscheint genau
 * einmal pro neuer Fassung. Einzig der OK-Knopf schließt ihn: `onKeyEvent`
 * am äußeren Rahmen fängt alles ab, was der Knopf selbst nicht schon
 * verbraucht (Pfeiltasten, sonstige Tasten) – ohne das würde das
 * Steuerkreuz den Fokus unbemerkt zur Senderliste dahinter weiterreichen,
 * und ein Fokuswechsel dort löst von selbst Hintergrundaktionen aus
 * (Vorschau starten, Programmdaten nachladen). Genau das soll nicht
 * passieren, solange der Hinweis noch offen ist.
 */
@Composable
private fun WhatsNewDialog(onDismiss: () -> Unit) {
    val buttonFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { buttonFocus.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .onKeyEvent { true },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = TvSurfaceElevated),
        ) {
            Column(modifier = Modifier.padding(TvSpacing.large)) {
                Text(
                    text = stringResource(R.string.whats_new_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(TvSpacing.medium))

                WhatsNewItem("📺", stringResource(R.string.whats_new_item_recent))
                WhatsNewItem("🌍", stringResource(R.string.whats_new_item_languages))
                WhatsNewItem("📡", stringResource(R.string.whats_new_item_epg))
                WhatsNewItem("🔊", stringResource(R.string.whats_new_item_preview_sound))
                WhatsNewItem("🐞", stringResource(R.string.whats_new_item_bugfixes))

                Spacer(Modifier.height(TvSpacing.medium))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .focusRequester(buttonFocus),
                ) {
                    Text(stringResource(R.string.whats_new_button))
                }
            }
        }
    }
}

@Composable
private fun WhatsNewItem(emoji: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = emoji, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(32.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = TvOnSurfaceMuted,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// Update-Vorschlag (stündliche Hintergrundprüfung)
// ---------------------------------------------------------------------------

/**
 * Erscheint auf dem Hauptbildschirm, sobald die stündliche Hintergrundprüfung
 * (siehe [HomeViewModel]) eine neue Fassung gefunden hat – nie mitten in der
 * Wiedergabe, weil dieser Bildschirm dann gar nicht komponiert ist.
 *
 * Beide Knöpfe bleiben über alle Zustände hinweg bestehen (auch während des
 * Ladens), nur der Text darüber wechselt: Verschwände der fokussierte Knopf
 * zwischenzeitlich aus der Komposition, verlöre der Fokus sein Ziel, und
 * genau dieselbe Lücke, die [WhatsNewDialog] mit `onKeyEvent` schließt,
 * stünde wieder offen.
 */
@Composable
private fun UpdatePromptDialog(
    state: UpdateUiState,
    onInstallNow: () -> Unit,
    onLater: () -> Unit,
) {
    val installFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { installFocus.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .onKeyEvent { true },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 480.dp),
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = TvSurfaceElevated),
        ) {
            Column(modifier = Modifier.padding(TvSpacing.large)) {
                Text(
                    text = stringResource(R.string.update_prompt_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(TvSpacing.small))

                val (message, isError) = when (state) {
                    is UpdateUiState.Available ->
                        stringResource(R.string.update_prompt_version, state.info.versionName) to false
                    is UpdateUiState.Downloading ->
                        stringResource(R.string.update_prompt_downloading, state.percent) to false
                    is UpdateUiState.Failed ->
                        stringResource(R.string.update_prompt_failed, state.message) to true
                    else -> "" to false
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isError) MaterialTheme.colorScheme.error else TvOnSurfaceMuted,
                )

                Spacer(Modifier.height(TvSpacing.medium))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(TvSpacing.small),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Button(onClick = onLater) {
                        Text(stringResource(R.string.update_prompt_later))
                    }
                    Button(
                        onClick = onInstallNow,
                        modifier = Modifier.focusRequester(installFocus),
                    ) {
                        Text(stringResource(R.string.update_prompt_install_now))
                    }
                }
            }
        }
    }
}
