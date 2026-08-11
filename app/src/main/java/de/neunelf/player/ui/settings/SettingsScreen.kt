package de.neunelf.player.ui.settings

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.neunelf.player.core.TimeFormat
import de.neunelf.player.data.model.AspectRatioMode
import de.neunelf.player.data.prefs.AppLanguage
import de.neunelf.player.data.prefs.SettingsStore
import de.neunelf.player.ui.components.DeveloperCredit
import de.neunelf.player.ui.theme.TvAccent
import de.neunelf.player.ui.theme.TvBackground
import de.neunelf.player.ui.theme.TvOnSurfaceMuted
import de.neunelf.player.ui.theme.TvSpacing
import de.neunelf.player.ui.theme.TvSurfaceElevated
import de.neunelf.player.ui.theme.TvSurfaceVariant

/**
 * Einstellungen und Wartung.
 *
 * Bewusst flach gehalten: eine Liste, jeder Eintrag entweder eine Aktion
 * oder ein Wert, der bei OK auf die nächste Stufe weiterschaltet. Auf einer
 * Fernbedienung ist das schneller als Untermenüs mit Dialogen.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenContact: () -> Unit,
    onOpenEpgSource: () -> Unit,
    onPlaylistRemoved: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var isLanguageMenuOpen by remember { mutableStateOf(false) }
    val languageRowFocus = remember { FocusRequester() }

    // Nur solange das Menü offen ist: Zurück schließt es, statt den
    // Bildschirm zu verlassen (siehe VodScreen.SortMenu für dasselbe Muster).
    BackHandler(enabled = isLanguageMenuOpen) { isLanguageMenuOpen = false }

    var wasLanguageMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(isLanguageMenuOpen) {
        if (!isLanguageMenuOpen && wasLanguageMenuOpen) {
            runCatching { languageRowFocus.requestFocus() }
        }
        wasLanguageMenuOpen = isLanguageMenuOpen
    }

    Box(modifier = Modifier.fillMaxSize().background(TvBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = TvSpacing.overscanHorizontal,
                    vertical = TvSpacing.overscanVertical,
                ),
        ) {
            Text("Einstellungen", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(TvSpacing.medium))

            state.message?.let {
                Text(it, color = TvAccent, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(TvSpacing.small))
            }

            LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = TvSpacing.large),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                SettingsSection("Playlist")
            }
            item {
                SettingsRow(
                    title = "Aktive Playlist",
                    value = state.playlistName ?: "Keine",
                    onClick = {},
                )
            }
            item {
                SettingsRow(
                    title = "Senderliste aktualisieren",
                    value = state.lastSyncLabel,
                    onClick = viewModel::refreshPlaylist,
                )
            }
            item {
                SettingsRow(
                    title = "EPG-Quelle",
                    value = state.epgUrl.ifBlank { "Automatisch (Panel-Daten)" },
                    onClick = onOpenEpgSource,
                )
            }
            item {
                SettingsRow(
                    title = "Programmzeitschrift aktualisieren",
                    value = state.lastEpgSyncLabel,
                    onClick = viewModel::refreshEpg,
                )
            }
            item {
                SettingsRow(
                    title = "Playlist entfernen",
                    value = "Löscht alle lokalen Daten dieser Playlist",
                    onClick = {
                        viewModel.removePlaylist()
                        onPlaylistRemoved()
                    },
                )
            }

            item { SettingsSection("Wiedergabe") }
            item {
                SettingsRow(
                    title = "Puffergröße",
                    value = SettingsStore.BUFFER_PRESETS.entries
                        .firstOrNull { it.value == state.settings.bufferMs }?.key
                        ?: "${state.settings.bufferMs / 1000} s",
                    onClick = viewModel::cycleBuffer,
                )
            }
            item {
                SettingsRow(
                    title = "Stream-Format",
                    value = if (state.settings.preferHls) {
                        "HLS (.m3u8) – besseres Umschalten der Qualität"
                    } else {
                        "MPEG-TS (.ts) – startet schneller"
                    },
                    onClick = viewModel::togglePreferHls,
                )
            }
            item {
                SettingsRow(
                    title = "Seitenverhältnis",
                    value = state.settings.aspectRatio.label,
                    onClick = { viewModel.setAspectRatio(state.settings.aspectRatio.next()) },
                )
            }
            item {
                SettingsRow(
                    title = "Bevorzugte Tonspur",
                    value = state.settings.preferredAudioLanguage.ifBlank { "Automatisch" },
                    onClick = viewModel::cycleAudioLanguage,
                )
            }
            item {
                SettingsRow(
                    title = "Untertitel",
                    value = if (state.settings.subtitlesEnabled) "An" else "Aus",
                    onClick = viewModel::toggleSubtitles,
                )
            }
            item {
                SettingsRow(
                    title = "Live-Vorschau",
                    value = if (state.settings.showPreviewPlayer) {
                        "An – der gewählte Sender läuft rechts in der Vorschau"
                    } else {
                        "Aus – spart eine Verbindung zum Server"
                    },
                    onClick = viewModel::togglePreviewPlayer,
                )
            }

            item { SettingsSection("App") }
            item {
                SettingsRow(
                    title = "Sprache",
                    value = state.language.label,
                    onClick = { isLanguageMenuOpen = true },
                    modifier = Modifier.focusRequester(languageRowFocus),
                )
            }
            item {
                SettingsRow(
                    title = "Zugang verlängern",
                    value = "QR-Code zum Chat mit 9elf",
                    onClick = onOpenContact,
                )
            }
            item {
                SettingsRow(
                    title = "Aktualisierung",
                    value = state.update.describe(),
                    onClick = viewModel::onUpdateRowClick,
                )
            }
            item {
                SettingsRow(
                    title = "9elf Player",
                    value = "Version ${state.currentVersion} · ${state.programCount} EPG-Einträge im Cache",
                    onClick = onBack,
                )
            }
            // Ganz unten und nicht fokussierbar: der Hinweis soll da sein,
            // aber niemandem im Weg stehen.
            item { DeveloperCredit(textAlign = TextAlign.Start) }
        }
    }

        if (isLanguageMenuOpen) {
            LanguageMenu(
                current = state.language,
                onSelect = {
                    viewModel.setLanguage(it)
                    isLanguageMenuOpen = false
                },
                modifier = Modifier
                    .align(Alignment.Center),
            )
        }
    }
}

/** Beschriftung der Aktualisierungs-Zeile – sagt zugleich, was OK bewirkt. */
private fun UpdateUiState.describe(): String = when (this) {
    UpdateUiState.Unknown -> "OK drücken, um nach einer neuen Fassung zu suchen"
    UpdateUiState.Checking -> "Suche…"
    UpdateUiState.UpToDate -> "Diese Fassung ist aktuell"
    is UpdateUiState.Available -> "Version ${info.versionName} verfügbar · OK zum Laden"
    is UpdateUiState.Downloading -> "Wird geladen… $percent %"
    is UpdateUiState.ReadyToInstall -> "Version $versionName geladen · OK zum Installieren"
    is UpdateUiState.Failed -> "Fehlgeschlagen: $message · OK für erneuten Versuch"
}

/**
 * Auswahlliste der Sprachen.
 *
 * Dasselbe Muster wie [de.neunelf.player.ui.vod.VodScreen]s Sortiermenü:
 * eine schlichte, fokussierbare Liste statt eines Dialogs, der sich mit
 * dem Steuerkreuz nur mühsam bedienen ließe. Jede Sprache steht in ihrer
 * eigenen Schrift, damit sie unabhängig von der gerade aktiven
 * Anzeigesprache wiedererkennbar bleibt.
 */
@Composable
private fun LanguageMenu(
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstEntry = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { firstEntry.requestFocus() } }

    Surface(
        modifier = modifier
            .width(320.dp)
            .heightIn(max = 520.dp),
        shape = RoundedCornerShape(8.dp),
        colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = TvSurfaceElevated),
    ) {
        LazyColumn(modifier = Modifier.padding(vertical = 6.dp)) {
            item {
                Text(
                    text = "Sprache",
                    style = MaterialTheme.typography.labelMedium,
                    color = TvOnSurfaceMuted,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            items(AppLanguage.entries.toList(), key = { it.name }) { option ->
                val isFirst = option == AppLanguage.entries.first()
                Surface(
                    onClick = { onSelect(option) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .then(if (isFirst) Modifier.focusRequester(firstEntry) else Modifier),
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (option == current) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Ausgewählt",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Column {
        Spacer(Modifier.height(TvSpacing.medium))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = TvOnSurfaceMuted,
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SettingsRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = TvSurfaceVariant,
            focusedContainerColor = TvAccent,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = TvSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(320.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
