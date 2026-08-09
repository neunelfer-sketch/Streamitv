package com.streamitv.tv.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamitv.tv.core.TimeFormat
import com.streamitv.tv.data.model.AspectRatioMode
import com.streamitv.tv.data.prefs.SettingsStore
import com.streamitv.tv.ui.theme.TvAccent
import com.streamitv.tv.ui.theme.TvBackground
import com.streamitv.tv.ui.theme.TvOnSurfaceMuted
import com.streamitv.tv.ui.theme.TvSpacing
import com.streamitv.tv.ui.theme.TvSurfaceVariant

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
    onPlaylistRemoved: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBackground)
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

            item { SettingsSection("Über") }
            item {
                SettingsRow(
                    title = "StreamiTV",
                    value = "Version 1.0.0 · ${state.programCount} EPG-Einträge im Cache",
                    onClick = onBack,
                )
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
private fun SettingsRow(title: String, value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
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
