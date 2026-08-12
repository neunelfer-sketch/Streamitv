package de.qwikster.player.ui.settings

import android.app.Activity
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.qwikster.player.R
import de.qwikster.player.data.prefs.AppLanguage
import de.qwikster.player.data.prefs.SettingsStore
import de.qwikster.player.ui.common.touchClickable
import de.qwikster.player.ui.components.DeveloperCredit
import de.qwikster.player.ui.theme.TvAccent
import de.qwikster.player.ui.theme.TvBackground
import de.qwikster.player.ui.theme.TvOnSurfaceMuted
import de.qwikster.player.ui.theme.TvSpacing
import de.qwikster.player.ui.theme.TvSurfaceElevated
import de.qwikster.player.ui.theme.TvSurfaceVariant

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
    onOpenRecordings: () -> Unit,
    onOpenProxy: () -> Unit,
    onPlaylistRemoved: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineLarge,
            )
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
                SettingsSection(stringResource(R.string.settings_section_playlist))
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_active_playlist),
                    value = state.playlistName ?: stringResource(R.string.settings_playlist_none),
                    onClick = {},
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_refresh_channels),
                    value = state.lastSyncLabel
                        .ifBlank { stringResource(R.string.settings_sync_never) },
                    onClick = viewModel::refreshPlaylist,
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_epg_source),
                    value = state.epgUrl.ifBlank {
                        stringResource(R.string.settings_epg_source_auto)
                    },
                    onClick = onOpenEpgSource,
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_refresh_epg),
                    value = state.lastEpgSyncLabel
                        .ifBlank { stringResource(R.string.settings_sync_never) },
                    onClick = viewModel::refreshEpg,
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_remove_playlist),
                    value = stringResource(R.string.settings_remove_playlist_desc),
                    onClick = {
                        viewModel.removePlaylist()
                        onPlaylistRemoved()
                    },
                )
            }

            item { SettingsSection(stringResource(R.string.settings_section_playback)) }
            item {
                val preset = SettingsStore.BUFFER_PRESETS
                    .firstOrNull { it.valueMs == state.settings.bufferMs }
                SettingsRow(
                    title = stringResource(R.string.settings_buffer_size),
                    value = preset?.let { stringResource(it.labelRes) }
                        ?: stringResource(
                            R.string.settings_buffer_seconds,
                            state.settings.bufferMs / 1000,
                        ),
                    onClick = viewModel::cycleBuffer,
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_stream_format),
                    value = if (state.settings.preferHls) {
                        stringResource(R.string.settings_stream_format_hls)
                    } else {
                        stringResource(R.string.settings_stream_format_ts)
                    },
                    onClick = viewModel::togglePreferHls,
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_aspect_ratio),
                    value = stringResource(state.settings.aspectRatio.labelRes),
                    onClick = { viewModel.setAspectRatio(state.settings.aspectRatio.next()) },
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_preferred_audio),
                    value = state.settings.preferredAudioLanguage.ifBlank {
                        stringResource(R.string.settings_audio_auto)
                    },
                    onClick = viewModel::cycleAudioLanguage,
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_subtitles),
                    value = if (state.settings.subtitlesEnabled) {
                        stringResource(R.string.settings_on)
                    } else {
                        stringResource(R.string.settings_off)
                    },
                    onClick = viewModel::toggleSubtitles,
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_live_preview),
                    value = if (state.settings.showPreviewPlayer) {
                        stringResource(R.string.settings_live_preview_on)
                    } else {
                        stringResource(R.string.settings_live_preview_off)
                    },
                    onClick = viewModel::togglePreviewPlayer,
                )
            }

            item { SettingsSection(stringResource(R.string.settings_section_app)) }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_language),
                    value = state.language.displayLabel(),
                    onClick = { isLanguageMenuOpen = true },
                    modifier = Modifier.focusRequester(languageRowFocus),
                )
            }
            // Nur nach dem Entsperren – siehe SettingsViewModel.onAboutRowClick.
            if (state.settings.recordingUnlocked) {
                item {
                    SettingsRow(
                        title = stringResource(R.string.recordings_title),
                        value = stringResource(R.string.recordings_settings_desc),
                        onClick = onOpenRecordings,
                    )
                }
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.proxy_title),
                    value = state.settings.proxyHost.ifBlank {
                        stringResource(R.string.proxy_off)
                    },
                    onClick = onOpenProxy,
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_extend_access),
                    value = stringResource(R.string.settings_extend_access_desc),
                    onClick = onOpenContact,
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_update),
                    value = state.update.describe(),
                    onClick = viewModel::onUpdateRowClick,
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.app_name),
                    value = stringResource(
                        R.string.settings_about_value,
                        state.currentVersion,
                        state.programCount,
                    ),
                    onClick = viewModel::onAboutRowClick,
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
                    // `setLanguage` stellt den Anwendungskontext sofort um,
                    // aber erst ein Neuaufbau der Activity übernimmt die
                    // Sprache wirklich überall – sonst blieben bereits
                    // aufgebaute Bildschirme (z. B. der Hauptbildschirm im
                    // Rückstapel) auf der alten Sprache stehen, bis man
                    // zufällig neu dorthin navigiert. Beim Neuaufbau läuft
                    // `attachBaseContext` erneut und legt die neue Sprache
                    // über alle Ressourcen. So wechselt die App komplett und
                    // sofort, wie bei Netflix und anderen Streaming-Apps.
                    (context as? Activity)?.recreate()
                },
                modifier = Modifier
                    .align(Alignment.Center),
            )
        }
    }
}

/** Beschriftung der Aktualisierungs-Zeile – sagt zugleich, was OK bewirkt. */
@Composable
private fun UpdateUiState.describe(): String = when (this) {
    UpdateUiState.Unknown -> stringResource(R.string.update_unknown)
    UpdateUiState.Checking -> stringResource(R.string.update_checking)
    UpdateUiState.UpToDate -> stringResource(R.string.update_up_to_date)
    is UpdateUiState.Available -> stringResource(R.string.update_available, info.versionName)
    is UpdateUiState.Downloading -> stringResource(R.string.update_downloading, percent)
    is UpdateUiState.ReadyToInstall -> stringResource(R.string.update_ready, versionName)
    is UpdateUiState.Failed -> stringResource(R.string.update_failed, message)
}

/**
 * Beschriftung eines Spracheintrags.
 *
 * Die echten Sprachen tragen ihren Eigennamen ("Türkçe"), der bewusst
 * unübersetzt bleibt – so findet man seine Sprache auch dann, wenn die App
 * gerade in einer fremden angezeigt wird. Nur "Systemsprache" ist ein
 * gewöhnlicher Oberflächentext und kommt deshalb aus den Ressourcen.
 */
@Composable
private fun AppLanguage.displayLabel(): String =
    labelRes?.let { stringResource(it) } ?: label

/**
 * Auswahlliste der Sprachen.
 *
 * Dasselbe Muster wie [de.qwikster.player.ui.vod.VodScreen]s Sortiermenü:
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
                    text = stringResource(R.string.settings_language),
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
                        .touchClickable({ onSelect(option) })
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
                            text = option.displayLabel(),
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
            .height(64.dp)
            .touchClickable(onClick),
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
