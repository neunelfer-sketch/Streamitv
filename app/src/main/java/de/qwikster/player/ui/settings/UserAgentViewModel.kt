package de.qwikster.player.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.qwikster.player.R
import de.qwikster.player.data.prefs.SettingsStore
import de.qwikster.player.data.remote.UserAgents
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Eine wählbare Kennung: sprechender Name für den Zuschauer, dahinter die
 * Zeichenkette, die tatsächlich gesendet wird.
 */
data class UserAgentOption(
    @StringRes val labelRes: Int,
    val value: String,
)

data class UserAgentUiState(
    val options: List<UserAgentOption> = emptyList(),
    /** Leer = automatisch durchprobieren. */
    val selected: String = "",
)

/**
 * Auswahl der Abspielprogramm-Kennung.
 *
 * Die Liste beginnt mit "Automatisch" – dem Standard, bei dem die App wie
 * bisher der Reihe nach durchprobiert. Wer einen Anbieter hat, der nur ein
 * bestimmtes Programm durchlässt, stellt hier genau dieses ein und spart
 * sich damit die Fehlversuche davor.
 */
@HiltViewModel
class UserAgentViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val options = buildList {
        add(UserAgentOption(R.string.user_agent_auto, ""))
        UserAgents.ALL.forEach { agent ->
            add(UserAgentOption(labelFor(agent), agent))
        }
    }

    val uiState: StateFlow<UserAgentUiState> = settingsStore.settings
        .map { UserAgentUiState(options = options, selected = it.userAgent) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UserAgentUiState(options = options),
        )

    fun select(value: String) {
        viewModelScope.launch { settingsStore.setUserAgent(value) }
    }

    /**
     * Der Name, unter dem der Zuschauer das Programm kennt.
     *
     * Erkannt wird an einem Merkmal der Kennung statt an einer zweiten,
     * parallel gepflegten Liste: So kann in [UserAgents] eine Kennung
     * dazukommen, ohne dass sie hier ohne Beschriftung auftaucht.
     */
    @StringRes
    private fun labelFor(agent: String): Int = when {
        agent.startsWith("Qwikster") -> R.string.user_agent_own
        agent.startsWith("VLC") -> R.string.user_agent_vlc
        agent.startsWith("Lavf") -> R.string.user_agent_ffmpeg
        agent.startsWith("Dalvik") -> R.string.user_agent_android
        agent.startsWith("okhttp") -> R.string.user_agent_okhttp
        agent.contains("ExoPlayerLib") -> R.string.user_agent_exoplayer
        agent.startsWith("IBOPlayer") -> R.string.user_agent_ibo
        agent.startsWith("TiviMate") -> R.string.user_agent_tivimate
        agent.startsWith("IPTVSmarters") -> R.string.user_agent_smarters
        else -> R.string.user_agent_browser
    }
}
