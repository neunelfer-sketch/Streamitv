package de.neunelf.player.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import de.neunelf.player.R
import de.neunelf.player.data.model.Category
import de.neunelf.player.data.model.Channel
import de.neunelf.player.data.model.ChannelWithProgram
import de.neunelf.player.data.model.EpgProgram
import de.neunelf.player.data.model.Playlist
import de.neunelf.player.data.model.StreamKind
import de.neunelf.player.data.prefs.AppSettings
import de.neunelf.player.data.prefs.SettingsStore
import de.neunelf.player.data.repository.ChannelFilter
import de.neunelf.player.data.repository.EpgRepository
import de.neunelf.player.data.repository.IptvRepository
import de.neunelf.player.data.repository.PlaylistSyncer
import de.neunelf.player.data.repository.SyncProgress
import de.neunelf.player.data.repository.UpdateRepository
import de.neunelf.player.player.PreviewPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Einträge der linken Spalte. Neben den echten Panel-Kategorien gibt es
 * drei virtuelle, die TiviMate genauso anzeigt.
 */
sealed interface CategoryItem {
    val count: Int

    // Die Beschriftung liefert `CategoryItem.label()` in HomeScreen: Die
    // drei virtuellen Einträge tragen übersetzbaren Oberflächentext, der
    // Name einer echten Kategorie stammt dagegen aus der Playlist des
    // Nutzers und bleibt deshalb unangetastet.
    data class All(override val count: Int) : CategoryItem

    data class Favorites(override val count: Int) : CategoryItem

    data class Recent(override val count: Int) : CategoryItem

    data class Group(val category: Category) : CategoryItem {
        override val count get() = category.channelCount
    }

    /** Stabiler Schlüssel für `LazyColumn`-Keys und Fokus-Wiederherstellung. */
    val key: String
        get() = when (this) {
            is All -> "__all__"
            is Favorites -> "__fav__"
            is Recent -> "__recent__"
            is Group -> category.id
        }

    fun toFilter(): ChannelFilter = when (this) {
        is All -> ChannelFilter.All
        is Favorites -> ChannelFilter.Favorites
        is Recent -> ChannelFilter.Recent
        is Group -> ChannelFilter.Group(category.id)
    }
}

/** Kompletter Zustand des Hauptbildschirms. */
data class HomeUiState(
    val playlist: Playlist? = null,
    val categories: List<CategoryItem> = emptyList(),
    val selectedCategoryKey: String? = null,
    val channels: List<ChannelWithProgram> = emptyList(),
    /** Der Sender, auf dem der Fokus steht – speist die Vorschau rechts. */
    val focusedChannel: ChannelWithProgram? = null,
    /** Kommende Sendungen des fokussierten Senders (Detailspalte). */
    val upcoming: List<EpgProgram> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val isLoading: Boolean = true,
    val syncMessage: String? = null,
    val errorMessage: String? = null,
    val searchQuery: String = "",
    /** Versionsnummer einer verfügbaren Aktualisierung, sonst `null`. */
    val updateVersion: String? = null,
) {
    val hasPlaylist: Boolean get() = playlist != null
    val isEmpty: Boolean get() = !isLoading && channels.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: IptvRepository,
    private val epgRepository: EpgRepository,
    private val syncer: PlaylistSyncer,
    private val settingsStore: SettingsStore,
    private val updateRepository: UpdateRepository,
    private val previewPlayer: PreviewPlayer,
) : ViewModel() {

    /** Läuft die verzögerte Vorschau gerade an? Siehe [startPreview]. */
    private var previewJob: Job? = null

    /** Aus, solange der Vollbild-Player im Vordergrund ist. */
    private var previewEnabled = true

    /** Ausgewählte Kategorie. Startwert: "Alle Sender". */
    private val selectedCategory = MutableStateFlow<CategoryItem>(CategoryItem.All(0))
    private val focusedChannelId = MutableStateFlow<String?>(null)
    private val searchQuery = MutableStateFlow("")
    private val syncMessage = MutableStateFlow<String?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val upcoming = MutableStateFlow<List<EpgProgram>>(emptyList())
    private val updateVersion = MutableStateFlow<String?>(null)

    /** Sammelt die Nebenzustände, damit `combine` unter fünf Quellen bleibt. */
    private data class AuxState(
        val settings: AppSettings,
        val syncMessage: String?,
        val errorMessage: String?,
        val upcoming: List<EpgProgram>,
        val updateVersion: String?,
    )

    /**
     * Tickt alle 15 Sekunden. Ohne diesen Takt würden Fortschrittsbalken und
     * "läuft jetzt"-Markierungen einfrieren, solange der Nutzer nichts drückt.
     */
    private val nowTicker: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            kotlinx.coroutines.delay(TimeUnit.SECONDS.toMillis(15))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    // --- Kategorien --------------------------------------------------------

    private val categories: StateFlow<List<CategoryItem>> =
        combine(
            repository.observeCategories(StreamKind.LIVE),
            // Reine Zählungen statt der vollen Senderliste – bei 8.000+
            // Sendern spart das pro Update mehrere tausend Objektzuweisungen
            // allein für eine Zahl in der Kategorie-Leiste.
            repository.observeChannelCount(),
            repository.observeFavoriteCount(),
            repository.observeChannels(ChannelFilter.Recent).map { it.size },
        ) { groups, allCount, favoriteCount, recentCount ->
            buildList {
                add(CategoryItem.All(allCount))
                // Leere Spezial-Kategorien blenden wir aus, damit die Liste
                // bei einer frischen Installation nicht halb tot wirkt.
                if (favoriteCount > 0) add(CategoryItem.Favorites(favoriteCount))
                if (recentCount > 0) add(CategoryItem.Recent(recentCount))
                addAll(groups.map { CategoryItem.Group(it) })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Sender der gewählten Kategorie ------------------------------------

    private val channels: StateFlow<List<ChannelWithProgram>> =
        combine(selectedCategory, searchQuery) { category, query ->
            if (query.isBlank()) category.toFilter() else ChannelFilter.Search(query)
        }
            .flatMapLatest { filter -> repository.observeChannels(filter) }
            // Bei jedem Ticker-Schlag die laufende Sendung neu bestimmen.
            .combine(nowTicker) { channelList, now -> channelList to now }
            .flatMapLatest { (channelList, now) ->
                epgRepository.observeChannelsWithProgram(channelList, now)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Zusammengesetzter UI-Zustand --------------------------------------

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeActivePlaylist(),
        categories,
        channels,
        combine(selectedCategory, focusedChannelId, searchQuery) { c, f, q -> Triple(c, f, q) },
        combine(settingsStore.settings, syncMessage, errorMessage, upcoming, updateVersion, ::AuxState),
    ) { playlist, categoryList, channelList, (category, focusedId, query), aux ->
        val focused = channelList.firstOrNull { it.channel.streamId == focusedId }
            ?: channelList.firstOrNull()

        HomeUiState(
            playlist = playlist,
            categories = categoryList,
            selectedCategoryKey = category.key,
            channels = channelList,
            focusedChannel = focused,
            upcoming = aux.upcoming,
            settings = aux.settings,
            isLoading = playlist != null && categoryList.isEmpty() && channelList.isEmpty(),
            syncMessage = aux.syncMessage,
            errorMessage = aux.errorMessage,
            searchQuery = query,
            updateVersion = aux.updateVersion,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        // Nach dem ersten Laden der Playlist prüfen, ob ein Refresh fällig ist.
        repository.observeActivePlaylist()
            .onEach { playlist -> playlist?.let { maybeAutoRefresh(it) } }
            .launchIn(viewModelScope)

        // Beiläufig nach einer neuen Fassung sehen. Das Ergebnis erscheint
        // nur als Hinweis in der Kopfzeile – ein Dialog beim Start wäre auf
        // einem Fernseher aufdringlich, und ein Fehlschlag (kein Netz)
        // bleibt bewusst still: Wer aktiv sucht, tut das in den
        // Einstellungen und bekommt dort auch die Fehlermeldung.
        viewModelScope.launch {
            updateVersion.value = runCatching { updateRepository.check() }
                .getOrNull()
                ?.versionName
        }
    }

    // -----------------------------------------------------------------------
    // Aktionen aus der UI
    // -----------------------------------------------------------------------

    fun selectCategory(item: CategoryItem) {
        selectedCategory.value = item
        // Fokus zurücksetzen, damit die Vorschau sofort zum ersten Sender
        // der neuen Kategorie springt.
        focusedChannelId.value = null
    }

    fun onChannelFocused(channel: Channel) {
        focusedChannelId.value = channel.streamId
        loadUpcoming(channel)
        startPreview(channel)
    }

    /** Der ExoPlayer der Vorschaufläche – die UI bindet ihn an eine `PlayerView`. */
    fun previewPlayer(): ExoPlayer = previewPlayer.getOrCreate()

    /**
     * Startet die Vorschau des fokussierten Senders – aber erst nach einer
     * kurzen Pause.
     *
     * Ohne diese Verzögerung würde beim Durchblättern der Senderliste für
     * jeden überflogenen Sender eine Verbindung aufgebaut. Viele Panels
     * erlauben nur eine Handvoll gleichzeitiger Verbindungen und sperren den
     * Zugang bei solchen Salven zeitweise.
     *
     * `previewJob` wird bei jedem Fokuswechsel abgebrochen: Es zählt immer
     * nur der Sender, auf dem der Fokus zur Ruhe kommt.
     */
    private fun startPreview(channel: Channel) {
        previewJob?.cancel()
        if (!previewEnabled) {
            previewPlayer.stop()
            return
        }
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DELAY_MS)
            val settings = settingsStore.settings.first()
            if (!settings.showPreviewPlayer) return@launch
            val url = repository.resolveStreamUrl(channel, preferHls = settings.preferHls)
                ?: return@launch
            previewPlayer.play(url)
        }
    }

    /**
     * Hält die Vorschau an, solange der Vollbild-Player läuft.
     *
     * Sonst liefen zwei Streams gleichzeitig – bei Panels mit begrenzter
     * Verbindungszahl bricht dann ausgerechnet das Vollbild ab.
     */
    fun setPreviewEnabled(enabled: Boolean) {
        previewEnabled = enabled
        if (!enabled) {
            previewJob?.cancel()
            previewPlayer.stop()
        }
    }

    /**
     * Gibt den Vorschau-Player frei.
     *
     * Bewusst hier und nicht beim Verlassen der Komposition: Solange dieses
     * ViewModel lebt, kann der Bildschirm jederzeit zurückkommen. Und beim
     * Abräumen der Komposition ist nicht festgelegt, ob die `PlayerView`
     * oder der zugehörige Effekt zuerst drankommt – eine noch angebundene
     * Ansicht auf einem freigegebenen Player beendet die App.
     */
    override fun onCleared() {
        previewJob?.cancel()
        previewPlayer.release()
        super.onCleared()
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch { repository.toggleFavorite(channel) }
    }

    fun markWatched(channel: Channel) {
        viewModelScope.launch {
            repository.markWatched(channel.playlistId, channel.streamId)
        }
    }

    fun dismissError() {
        errorMessage.value = null
    }

    /** Vollständiger Neuimport – aus den Einstellungen heraus ausgelöst. */
    fun refreshPlaylist() {
        viewModelScope.launch {
            val playlist = repository.getActivePlaylist() ?: return@launch
            runSync(playlist)
        }
    }

    fun refreshEpg() {
        viewModelScope.launch {
            val playlist = repository.getActivePlaylist() ?: return@launch
            epgRepository.refresh(playlist).collect { progress ->
                syncMessage.value = when (progress) {
                    is de.neunelf.player.data.repository.EpgSyncProgress.Step -> progress.message
                    is de.neunelf.player.data.repository.EpgSyncProgress.Done ->
                        context.getString(R.string.epg_programs_loaded, progress.programCount)
                    is de.neunelf.player.data.repository.EpgSyncProgress.Failed -> {
                        errorMessage.value = progress.message
                        null
                    }
                }
            }
            syncMessage.value = null
        }
    }

    // -----------------------------------------------------------------------
    // Interna
    // -----------------------------------------------------------------------

    /**
     * Aktualisiert automatisch, wenn die Daten älter als [SYNC_INTERVAL_MS]
     * sind. Beim allerersten Start (lastSyncAt == 0) läuft der Import sofort.
     */
    private suspend fun maybeAutoRefresh(playlist: Playlist) {
        val now = System.currentTimeMillis()
        if (now - playlist.lastSyncAt > SYNC_INTERVAL_MS) {
            runSync(playlist)
        }
        if (now - playlist.lastEpgSyncAt > EPG_INTERVAL_MS) {
            epgRepository.refresh(playlist).collect { /* still im Hintergrund */ }
        }
    }

    private suspend fun runSync(playlist: Playlist) {
        syncer.sync(playlist).collect { progress ->
            when (progress) {
                is SyncProgress.Step -> syncMessage.value = progress.message
                is SyncProgress.LiveReady ->
                    syncMessage.value = context.getString(R.string.sync_vod_in_background)
                is SyncProgress.Done -> syncMessage.value = null
                is SyncProgress.Failed -> {
                    errorMessage.value = progress.message
                    syncMessage.value = null
                }
            }
        }
    }

    /**
     * Lädt die nächsten Sendungen des fokussierten Senders. Fehlt für ihn
     * ein EPG-Eintrag, wird einmalig das Kurz-EPG des Panels nachgeladen.
     */
    private fun loadUpcoming(channel: Channel) {
        val epgId = channel.epgChannelId
        if (epgId == null) {
            upcoming.value = emptyList()
            return
        }
        viewModelScope.launch {
            val cached = epgRepository.getUpcoming(channel.playlistId, epgId)
            upcoming.value = cached.ifEmpty {
                // Nichts im Cache: einmalig beim Panel nachfragen.
                repository.getActivePlaylist()
                    ?.let { epgRepository.fetchShortEpg(it, channel) }
                    .orEmpty()
            }
        }
    }

    companion object {
        /** Senderliste alle 12 Stunden erneuern – Panels ändern sich selten. */
        private val SYNC_INTERVAL_MS = TimeUnit.HOURS.toMillis(12)

        /** EPG alle 6 Stunden – die meisten XMLTV-Quellen aktualisieren 4x täglich. */
        private val EPG_INTERVAL_MS = TimeUnit.HOURS.toMillis(6)

        /**
         * Wartezeit, bevor die Vorschau anläuft. Lang genug, dass beim
         * Durchblättern nicht für jeden überflogenen Sender eine Verbindung
         * aufgeht, kurz genug, dass es beim Verweilen nicht träge wirkt.
         */
        private const val PREVIEW_DELAY_MS = 900L
    }
}
