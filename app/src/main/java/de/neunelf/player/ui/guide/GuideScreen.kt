package de.neunelf.player.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.neunelf.player.core.TimeFormat
import de.neunelf.player.data.model.Channel
import de.neunelf.player.data.model.EpgProgram
import de.neunelf.player.ui.components.ChannelLogo
import de.neunelf.player.ui.theme.TvAccent
import de.neunelf.player.ui.theme.TvBackground
import de.neunelf.player.ui.theme.TvLive
import de.neunelf.player.ui.theme.TvOnSurfaceMuted
import de.neunelf.player.ui.theme.TvSpacing
import de.neunelf.player.ui.theme.TvSurface
import de.neunelf.player.ui.theme.TvSurfaceElevated
import de.neunelf.player.ui.theme.TvSurfaceVariant
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Der TV-Guide: EPG-Raster mit horizontaler Zeitachse.
 *
 * ```
 *            │ 20:00      20:30      21:00      21:30      22:00
 * ───────────┼──────────────────────────────────────────────────
 * ▸ RTL HD   │ Wer wird Millionär?      │ Spiegel TV  │ RTL Ne…
 * ▸ Sat.1 HD │ Navy CIS   │ Navy CIS: L.A.           │ Akte
 * ▸ ProSieben│ TV total              │ Late Night Berlin
 * ```
 *
 * **Wie das Layout funktioniert**
 *
 * Jede Sendung wird als Kachel gerendert, deren Breite proportional zu ihrer
 * Dauer ist ([MINUTE_WIDTH] pro Minute). Lücken im EPG werden durch
 * Platzhalter derselben Rechenlogik gefüllt, damit die Zeilen zueinander
 * ausgerichtet bleiben.
 *
 * Alle Zeilen teilen sich **einen** horizontalen ScrollState. Dadurch wandert
 * die komplette Tabelle synchron, sobald der Fokus in einer beliebigen Zeile
 * nach rechts läuft – ohne dass wir Scroll-Ereignisse manuell weiterreichen
 * müssten.
 *
 * Die Sender selbst liegen in einer `LazyColumn`: bei 2.000 Sendern werden
 * so nur die ~15 sichtbaren Zeilen aufgebaut.
 */
@Composable
fun GuideScreen(
    onPlayChannel: (Channel) -> Unit,
    onBack: () -> Unit,
    viewModel: GuideViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Ein gemeinsamer Scroll-Zustand für Zeitleiste und alle Programmzeilen.
    val timelineScroll = rememberScrollState()
    val rowsState = rememberLazyListState()
    val density = LocalDensity.current

    // Beim Öffnen auf "jetzt" scrollen – nicht auf den Anfang des Fensters.
    LaunchedEffect(state.window.start) {
        val now = System.currentTimeMillis()
        if (state.window.contains(now)) {
            val offsetMinutes = (now - state.window.start) / 60_000f
            val targetPx = with(density) { (MINUTE_WIDTH * offsetMinutes).toPx() }
            // Etwas Vorlauf, damit die laufende Sendung nicht am Rand klebt.
            timelineScroll.scrollTo((targetPx - 200f).roundToInt().coerceAtLeast(0))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBackground),
    ) {
        GuideHeader(
            dayLabel = state.dayLabel,
            selectedProgram = state.selectedProgram,
            onPreviousDay = viewModel::previousDay,
            onNextDay = viewModel::nextDay,
            onJumpToNow = viewModel::jumpToNow,
        )

        // --- Zeitleiste ----------------------------------------------------
        Row(modifier = Modifier.fillMaxWidth()) {
            // Platzhalter über der Senderspalte, damit die Achse passt.
            Box(
                modifier = Modifier
                    .width(CHANNEL_COLUMN_WIDTH)
                    .height(TIMELINE_HEIGHT)
                    .background(TvSurface),
            )
            TimeRuler(
                windowStart = state.window.start,
                windowMinutes = state.window.durationMinutes,
                scrollState = timelineScroll,
            )
        }

        // --- Rasterzeilen ---------------------------------------------------
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Lade Programmzeitschrift…", color = TvOnSurfaceMuted)
            }
        } else {
            LazyColumn(
                state = rowsState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = TvSpacing.large),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.channels, key = { it.streamId }) { channel ->
                    GuideRow(
                        channel = channel,
                        programs = channel.epgChannelId
                            ?.let { state.programsByChannel[it] }
                            .orEmpty(),
                        windowStart = state.window.start,
                        windowEnd = state.window.end,
                        scrollState = timelineScroll,
                        onProgramFocused = { program -> viewModel.onProgramFocused(channel, program) },
                        onProgramClick = { onPlayChannel(channel) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Kopfbereich
// ---------------------------------------------------------------------------

@Composable
private fun GuideHeader(
    dayLabel: String,
    selectedProgram: EpgProgram?,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onJumpToNow: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TvSurface)
            .padding(
                horizontal = TvSpacing.overscanHorizontal,
                vertical = TvSpacing.small,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "TV-Guide",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(end = TvSpacing.large),
            )

            HeaderButton(onClick = onPreviousDay) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Vorheriger Tag")
            }
            Text(
                text = dayLabel,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(horizontal = TvSpacing.small)
                    .width(110.dp),
            )
            HeaderButton(onClick = onNextDay) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Nächster Tag")
            }

            Spacer(Modifier.width(TvSpacing.medium))
            HeaderButton(onClick = onJumpToNow) {
                Text("Jetzt", style = MaterialTheme.typography.labelLarge)
            }
        }

        // Beschreibung der fokussierten Sendung – im Guide der wichtigste
        // Informationsblock, deshalb dauerhaft sichtbar statt als Popup.
        if (selectedProgram != null) {
            Spacer(Modifier.height(TvSpacing.small))
            Text(
                text = "${TimeFormat.range(selectedProgram.startAt, selectedProgram.endAt)}  ·  ${selectedProgram.title}",
                style = MaterialTheme.typography.titleMedium,
                color = TvAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            selectedProgram.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvOnSurfaceMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HeaderButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = TvSurfaceVariant,
            focusedContainerColor = TvAccent,
        ),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
            content = { content() },
        )
    }
}

// ---------------------------------------------------------------------------
// Zeitleiste
// ---------------------------------------------------------------------------

/**
 * Zeigt alle 30 Minuten eine Beschriftung und markiert die aktuelle Uhrzeit.
 * Teilt sich den [scrollState] mit den Programmzeilen.
 */
@Composable
private fun TimeRuler(
    windowStart: Long,
    windowMinutes: Int,
    scrollState: androidx.compose.foundation.ScrollState,
) {
    val slotCount = windowMinutes / SLOT_MINUTES
    val now = System.currentTimeMillis()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TIMELINE_HEIGHT)
            .background(TvSurface)
            .horizontalScroll(scrollState),
    ) {
        Row {
            repeat(slotCount) { index ->
                val slotStart = windowStart + TimeUnit.MINUTES.toMillis((index * SLOT_MINUTES).toLong())
                Box(
                    modifier = Modifier
                        .width(MINUTE_WIDTH * SLOT_MINUTES)
                        .fillMaxHeight()
                        .padding(start = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = TimeFormat.clock(slotStart),
                        style = MaterialTheme.typography.labelLarge,
                        color = TvOnSurfaceMuted,
                    )
                }
            }
        }

        // Markierung "jetzt": eine dünne rote Linie an der aktuellen Position.
        val nowOffsetMinutes = (now - windowStart) / 60_000f
        if (nowOffsetMinutes >= 0 && nowOffsetMinutes <= windowMinutes) {
            Box(
                modifier = Modifier
                    .padding(start = MINUTE_WIDTH * nowOffsetMinutes)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(TvLive),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Eine Rasterzeile
// ---------------------------------------------------------------------------

/**
 * Eine Senderzeile: links der fixe Sendername, rechts die Programmkacheln
 * im gemeinsamen Horizontal-Scroll.
 */
@Composable
private fun GuideRow(
    channel: Channel,
    programs: List<EpgProgram>,
    windowStart: Long,
    windowEnd: Long,
    scrollState: androidx.compose.foundation.ScrollState,
    onProgramFocused: (EpgProgram?) -> Unit,
    onProgramClick: () -> Unit,
) {
    Row(modifier = Modifier.height(ROW_HEIGHT)) {

        // --- Fixe Senderspalte ---------------------------------------------
        Row(
            modifier = Modifier
                .width(CHANNEL_COLUMN_WIDTH)
                .fillMaxHeight()
                .background(TvSurface)
                .padding(horizontal = TvSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = channel.number.takeIf { it > 0 }?.toString().orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = TvOnSurfaceMuted,
                modifier = Modifier.width(32.dp),
            )
            ChannelLogo(
                logoUrl = channel.logoUrl,
                contentDescription = channel.name,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // --- Programmkacheln ------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(scrollState),
        ) {
            if (programs.isEmpty()) {
                // Ohne EPG-Daten eine durchgehende Platzhalterkachel über die
                // volle Fensterbreite – sonst wirkt die Zeile "kaputt".
                ProgramCell(
                    title = "Keine Programminformationen",
                    widthMinutes = ((windowEnd - windowStart) / 60_000L).toInt(),
                    isPlaceholder = true,
                    isLive = false,
                    onFocused = { onProgramFocused(null) },
                    onClick = onProgramClick,
                )
                return@Row
            }

            // Cursor läuft über das Fenster; Lücken zwischen zwei Sendungen
            // werden als Platzhalter aufgefüllt, damit alle Zeilen zeitlich
            // exakt untereinander stehen.
            var cursor = windowStart
            val now = System.currentTimeMillis()

            programs.forEach { program ->
                val start = program.startAt.coerceAtLeast(windowStart)
                val end = program.endAt.coerceAtMost(windowEnd)
                if (end <= cursor) return@forEach

                // Lücke vor der Sendung auffüllen.
                if (start > cursor) {
                    ProgramCell(
                        title = "",
                        widthMinutes = ((start - cursor) / 60_000L).toInt(),
                        isPlaceholder = true,
                        isLive = false,
                        onFocused = { onProgramFocused(null) },
                        onClick = onProgramClick,
                    )
                }

                ProgramCell(
                    title = program.title,
                    subtitle = TimeFormat.range(program.startAt, program.endAt),
                    widthMinutes = ((end - start) / 60_000L).toInt(),
                    isPlaceholder = false,
                    isLive = program.isLiveAt(now),
                    progress = if (program.isLiveAt(now)) program.progressAt(now) else null,
                    onFocused = { onProgramFocused(program) },
                    onClick = onProgramClick,
                )

                cursor = end
            }

            // Rest bis zum Fensterende auffüllen.
            if (cursor < windowEnd) {
                ProgramCell(
                    title = "",
                    widthMinutes = ((windowEnd - cursor) / 60_000L).toInt(),
                    isPlaceholder = true,
                    isLive = false,
                    onFocused = { onProgramFocused(null) },
                    onClick = onProgramClick,
                )
            }
        }
    }
}

/**
 * Eine einzelne Programmkachel.
 *
 * Die Breite ergibt sich ausschließlich aus der Dauer – nur so bleibt die
 * Zeitachse über alle Zeilen hinweg konsistent. Sehr kurze Sendungen
 * (Nachrichten, Werbeblöcke) bekommen eine Mindestbreite, damit sie
 * überhaupt fokussierbar bleiben.
 */
@Composable
private fun ProgramCell(
    title: String,
    widthMinutes: Int,
    isPlaceholder: Boolean,
    isLive: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    subtitle: String? = null,
    progress: Float? = null,
) {
    if (widthMinutes <= 0) return

    var isFocused by remember { mutableStateOf(false) }
    val width = (MINUTE_WIDTH * widthMinutes).coerceAtLeast(MIN_CELL_WIDTH)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(end = 2.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = when {
                isPlaceholder -> TvSurfaceVariant.copy(alpha = 0.35f)
                isLive -> TvAccent.copy(alpha = 0.25f)
                else -> TvSurfaceElevated
            },
            focusedContainerColor = TvAccent,
            focusedContentColor = Color.White,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isLive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Zeitangabe nur zeigen, wenn die Kachel breit genug ist –
                // sonst überlagert sie den Titel.
                if (subtitle != null && width >= 140.dp) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isFocused) Color.White.copy(alpha = 0.8f) else TvOnSurfaceMuted,
                        maxLines = 1,
                    )
                }
            }

            // Fortschrittsbalken am unteren Rand der laufenden Sendung.
            if (progress != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .clip(RoundedCornerShape(topEnd = 2.dp))
                        .background(TvLive),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Maße des Rasters
// ---------------------------------------------------------------------------

/**
 * Breite einer Minute. 5 dp bedeutet: eine 30-Minuten-Sendung ist 150 dp
 * breit – genug für einen lesbaren Titel auf zwei Meter Abstand, ohne dass
 * ein Abend zu einer endlosen Scrollstrecke wird.
 */
private val MINUTE_WIDTH: Dp = 5.dp

/** Raster der Zeitleiste in Minuten. */
private const val SLOT_MINUTES = 30

private val MIN_CELL_WIDTH: Dp = 60.dp
private val ROW_HEIGHT: Dp = 56.dp
private val TIMELINE_HEIGHT: Dp = 36.dp
private val CHANNEL_COLUMN_WIDTH: Dp = 220.dp
