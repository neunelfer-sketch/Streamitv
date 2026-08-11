package de.qwikster.player.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.qwikster.player.core.TimeFormat
import de.qwikster.player.data.model.Channel
import de.qwikster.player.data.model.EpgProgram
import de.qwikster.player.data.repository.ChannelFilter
import de.qwikster.player.data.repository.EpgRepository
import de.qwikster.player.data.repository.IptvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Ein Zeitfenster des EPG-Rasters.
 *
 * Das Raster zeigt immer genau einen Tag. Der Startpunkt ist auf die halbe
 * Stunde abgerundet, damit die Zeitleiste auf runden Werten sitzt.
 */
data class GuideWindow(
    val start: Long,
    val end: Long,
) {
    val durationMinutes: Int get() = ((end - start) / 60_000L).toInt()

    fun contains(timestamp: Long): Boolean = timestamp in start until end
}

data class GuideUiState(
    val channels: List<Channel> = emptyList(),
    /** Sendungen je `epgChannelId` – die Zeile greift in O(1) darauf zu. */
    val programsByChannel: Map<String, List<EpgProgram>> = emptyMap(),
    val window: GuideWindow = GuideWindow(0L, 0L),
    /** 0 = heute, -1 = gestern, +1 = morgen … */
    val dayOffset: Int = 0,
    val selectedProgram: EpgProgram? = null,
    val selectedChannel: Channel? = null,
    val isLoading: Boolean = true,
)
// Die Tagesbeschriftung ("Heute"/"Morgen"/…) liefert `GuideUiState.dayLabel()`
// in GuideScreen – als Oberflächentext gehört sie in die Ressourcen, und die
// erreicht ein reines Zustandsobjekt ohne Context nicht.

/**
 * Zustand des TV-Guides.
 *
 * Bewusst wird immer nur **ein Tag** geladen, nicht die ganze Woche: eine
 * Woche EPG für 500 Sender sind schnell 200.000 Zeilen, und der Nutzer
 * scrollt ohnehin selten mehr als ein paar Stunden weit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuideViewModel @Inject constructor(
    private val repository: IptvRepository,
    private val epgRepository: EpgRepository,
) : ViewModel() {

    private val dayOffset = MutableStateFlow(0)
    private val selection = MutableStateFlow<Pair<Channel, EpgProgram?>?>(null)

    /**
     * Sichtbarer Ausschnitt der Senderliste, vom Bildschirm gemeldet.
     *
     * Nur für diese Sender werden Programmdaten geholt – siehe [programs].
     */
    private val visibleRows = MutableStateFlow(0 until VISIBLE_ROW_BUFFER)

    /** Sender des Rasters – aktuell alle; die Kategorie-Auswahl folgt später. */
    private val channels: StateFlow<List<Channel>> =
        repository.observeChannels(ChannelFilter.All)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val window: StateFlow<GuideWindow> = dayOffset
        .map { offset -> currentWindow(offset) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, currentWindow(0))

    /**
     * Die eigentlichen Rasterdaten – **nur für die sichtbaren Zeilen**.
     *
     * Ein voller Tag für alle Sender wäre nicht tragbar: Bei 30.000 Sendern
     * und einem Dutzend Sendungen je Sender und Tag sind das mehrere
     * hunderttausend Zeilen samt Beschreibungstexten, also weit mehr
     * Speicher, als ein Fire TV Stick hat. Sichtbar sind aber immer nur rund
     * 15 Zeilen; mit Puffer landet man bei einigen hundert Sendungen.
     *
     * Der Puffer ober- und unterhalb sorgt dafür, dass beim Scrollen schon
     * Daten bereitstehen, statt erst nachgeladen zu werden.
     */
    private val programs = combine(channels, window, visibleRows) { channelList, guideWindow, rows ->
        Triple(channelList, guideWindow, rows)
    }.flatMapLatest { (channelList, guideWindow, rows) ->
        val from = (rows.first - VISIBLE_ROW_BUFFER).coerceAtLeast(0)
        val to = (rows.last + VISIBLE_ROW_BUFFER).coerceAtMost(channelList.lastIndex)
        val slice = if (from <= to) channelList.subList(from, to + 1) else emptyList()
        epgRepository.observeGuide(slice, guideWindow.start, guideWindow.end)
    }

    val uiState: StateFlow<GuideUiState> = combine(
        channels,
        window,
        dayOffset,
        selection,
        programs,
    ) { channelList, guideWindow, offset, selected, programs ->
        GuideUiState(
            channels = channelList,
            programsByChannel = programs,
            window = guideWindow,
            dayOffset = offset,
            selectedChannel = selected?.first ?: channelList.firstOrNull(),
            selectedProgram = selected?.second,
            isLoading = channelList.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuideUiState())

    // -----------------------------------------------------------------------
    // Aktionen
    // -----------------------------------------------------------------------

    fun nextDay() {
        dayOffset.value = (dayOffset.value + 1).coerceAtMost(MAX_DAYS_FORWARD)
    }

    fun previousDay() {
        dayOffset.value = (dayOffset.value - 1).coerceAtLeast(-MAX_DAYS_BACK)
    }

    fun jumpToNow() {
        dayOffset.value = 0
    }

    fun onProgramFocused(channel: Channel, program: EpgProgram?) {
        selection.value = channel to program
    }

    /**
     * Meldet, welche Zeilen gerade sichtbar sind. Nur für diese (plus
     * Puffer) werden Programmdaten geladen.
     */
    fun onVisibleRowsChanged(firstIndex: Int, lastIndex: Int) {
        // Auf Blöcke gerundet: Ohne das löste jede einzelne vorbeigescrollte
        // Zeile eine neue Datenbankabfrage aus.
        val from = (firstIndex / ROW_BLOCK) * ROW_BLOCK
        val to = ((lastIndex / ROW_BLOCK) + 1) * ROW_BLOCK
        val range = from..to
        if (visibleRows.value != range) visibleRows.value = range
    }

    /**
     * Erzeugt das Zeitfenster für einen Tagesversatz.
     *
     * Für "heute" beginnt das Raster bei der laufenden halben Stunde – so
     * sieht der Nutzer sofort, was gerade läuft, statt bei 00:00 zu landen.
     */
    private fun currentWindow(offset: Int): GuideWindow {
        val now = System.currentTimeMillis()
        return if (offset == 0) {
            val start = TimeFormat.floorToHalfHour(now)
            GuideWindow(start, start + TimeUnit.HOURS.toMillis(WINDOW_HOURS))
        } else {
            val dayStart = TimeFormat.startOfDay(now) + TimeUnit.DAYS.toMillis(offset.toLong())
            GuideWindow(dayStart, dayStart + TimeUnit.HOURS.toMillis(24))
        }
    }

    companion object {
        /** Sichtbarer Zeitraum für "heute" – ein Tagesrest reicht praktisch immer. */
        private const val WINDOW_HOURS = 24L

        private const val MAX_DAYS_FORWARD = 7
        private const val MAX_DAYS_BACK = 1

        /**
         * Zusätzlich geladene Zeilen ober- und unterhalb des sichtbaren
         * Bereichs. Groß genug, dass zügiges Scrollen nicht auf leere Zeilen
         * trifft, klein genug, dass die Datenmenge unabhängig von der Größe
         * der Playlist bleibt.
         */
        private const val VISIBLE_ROW_BUFFER = 15

        /** Rundungsblock für den gemeldeten Sichtbereich (siehe [onVisibleRowsChanged]). */
        private const val ROW_BLOCK = 10
    }
}
