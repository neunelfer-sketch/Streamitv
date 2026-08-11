package de.qwikster.player.ui.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.qwikster.player.data.model.Channel
import de.qwikster.player.data.repository.ChannelFilter
import de.qwikster.player.data.repository.EpgRepository
import de.qwikster.player.data.repository.IptvRepository
import de.qwikster.player.data.repository.RecordingRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Eine wählbare Sendung im Vorlauf. */
data class ScheduleProgram(
    val title: String,
    val startAt: Long,
    val endAt: Long,
    val description: String?,
    /** Bereits vorgemerkt? Dann zeigt die Liste es an, statt doppelt anzulegen. */
    val isPlanned: Boolean = false,
)

data class ScheduleUiState(
    val channels: List<Channel> = emptyList(),
    val selectedChannelId: String? = null,
    val programs: List<ScheduleProgram> = emptyList(),
    val isLoadingPrograms: Boolean = false,
    val message: String? = null,
) {
    val selectedChannel: Channel?
        get() = channels.firstOrNull { it.streamId == selectedChannelId }
}

/**
 * Auswahl einer künftigen Sendung zum Aufnehmen.
 *
 * Die Programme werden **je Sender** geladen, nicht für alle auf einmal.
 * Das ist keine Bequemlichkeit, sondern Notwendigkeit: Zehn Stunden über
 * eine Playlist mit zehntausenden Sendern wären hunderttausende Sendungen –
 * ein sicherer Speicherüberlauf auf einem Fire TV Stick. So ist es immer
 * genau eine schmale Abfrage für den Sender, auf dem der Fokus steht.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val repository: IptvRepository,
    private val epgRepository: EpgRepository,
    private val recordingRepository: RecordingRepository,
) : ViewModel() {

    private val selectedChannelId = MutableStateFlow<String?>(null)
    private val programs = MutableStateFlow<List<ScheduleProgram>>(emptyList())
    private val isLoadingPrograms = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val channels: StateFlow<List<Channel>> =
        repository.observeChannels(ChannelFilter.All)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Titel der bereits vorgemerkten Sendungen – zum Markieren in der Liste. */
    private val plannedTitles: StateFlow<Set<String>> = recordingRepository.observeAll()
        .map { list -> list.map { "${it.streamId}@${it.startedAt}" }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val uiState: StateFlow<ScheduleUiState> = combine(
        channels,
        selectedChannelId,
        programs,
        isLoadingPrograms,
        message,
    ) { channelList, channelId, programList, loading, statusMessage ->
        ScheduleUiState(
            channels = channelList,
            selectedChannelId = channelId,
            programs = programList,
            isLoadingPrograms = loading,
            message = statusMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState())

    /**
     * Lädt die Sendungen der nächsten [LOOKAHEAD_HOURS] Stunden.
     *
     * Wird beim Fokuswechsel in der Senderliste aufgerufen – genau wie im
     * Hauptbildschirm folgt die Anzeige dem Fokus, nicht erst einem Klick.
     */
    fun selectChannel(channel: Channel) {
        if (selectedChannelId.value == channel.streamId) return
        selectedChannelId.value = channel.streamId

        val epgId = channel.epgChannelId
        if (epgId == null) {
            programs.value = emptyList()
            return
        }

        viewModelScope.launch {
            isLoadingPrograms.value = true
            val now = System.currentTimeMillis()
            val windowEnd = now + TimeUnit.HOURS.toMillis(LOOKAHEAD_HOURS)
            val planned = plannedTitles.value
            programs.value = epgRepository
                .getUpcoming(channel.playlistId, epgId, limit = MAX_PROGRAMS)
                .filter { it.startAt < windowEnd }
                .map { program ->
                    ScheduleProgram(
                        title = program.title,
                        startAt = program.startAt,
                        endAt = program.endAt,
                        description = program.description,
                        isPlanned = "${channel.streamId}@${program.startAt}" in planned,
                    )
                }
            isLoadingPrograms.value = false
        }
    }

    /** Merkt die gewählte Sendung vor. */
    fun schedule(program: ScheduleProgram, onScheduled: (String) -> Unit) {
        val channel = uiState.value.selectedChannel ?: return
        viewModelScope.launch {
            recordingRepository.schedule(
                channel = channel,
                programTitle = program.title,
                programStartAt = program.startAt,
                programEndAt = program.endAt,
            )
            // Die Liste neu aufbauen, damit der Eintrag sofort als vorgemerkt
            // erscheint – sonst wüsste niemand, ob der Druck angekommen ist.
            programs.value = programs.value.map {
                if (it.startAt == program.startAt) it.copy(isPlanned = true) else it
            }
            onScheduled(program.title)
        }
    }

    fun showMessage(text: String?) {
        message.value = text
    }

    private companion object {
        /** Vorlauf, aus dem gewählt werden kann – wie bei TiviMate zehn Stunden. */
        const val LOOKAHEAD_HOURS = 10L

        /**
         * Obergrenze je Sender. Zehn Stunden sind selten mehr als ein Dutzend
         * Sendungen; die Grenze fängt nur Panels ab, die minutenweise
         * Platzhalter liefern.
         */
        const val MAX_PROGRAMS = 40
    }
}
