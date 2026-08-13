package de.qwikster.player.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.qwikster.player.R
import de.qwikster.player.ui.common.touchClickable
import de.qwikster.player.ui.theme.TvAccent
import de.qwikster.player.ui.theme.TvBackground
import de.qwikster.player.ui.theme.TvOn
import de.qwikster.player.ui.theme.TvOnSurfaceMuted
import de.qwikster.player.ui.theme.TvSpacing
import de.qwikster.player.ui.theme.TvSurface

/**
 * Unter welchem Namen sich die App beim Anbieter meldet.
 *
 * Vor manchen Playlist-Adressen sitzt eine Schutzschicht, die nur eine
 * Handvoll bekannter Abspielprogramme durchlässt und allem anderen eine
 * Abfuhr erteilt – oft mit einem Code, den es im HTTP-Standard gar nicht
 * gibt. Welches Programm sie kennt, weiß nur der Zuschauer: nämlich das, in
 * dem dieselbe Playlist bereits läuft. Genau das wird hier ausgewählt.
 *
 * Eine Auswahlliste und kein Eingabefeld: Diese Kennungen sind lange
 * Zeichenketten voller Klammern und Schrägstriche, und sie mit einer
 * Fernbedienung fehlerfrei einzutippen ist eine Zumutung. Ein Fehler wäre
 * dabei nicht einmal erkennbar – die Meldung sähe genauso aus wie vorher.
 *
 * Die Wahl versperrt nichts: Kommt sie nicht durch, probiert die App
 * anschließend die übrigen Namen wie bisher durch.
 */
@Composable
fun UserAgentScreen(
    onBack: () -> Unit,
    viewModel: UserAgentViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val firstRow = remember { FocusRequester() }

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBackground)
            .padding(
                horizontal = TvSpacing.overscanHorizontal,
                vertical = TvSpacing.overscanVertical,
            ),
    ) {
        Text(
            text = stringResource(R.string.user_agent_title),
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = stringResource(R.string.user_agent_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = TvOnSurfaceMuted,
        )
        Spacer(Modifier.height(TvSpacing.medium))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = TvSpacing.large),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.options, key = { it.value }) { option ->
                AgentRow(
                    label = stringResource(option.labelRes),
                    detail = option.value,
                    isSelected = option.value == state.selected,
                    onClick = { viewModel.select(option.value) },
                    modifier = if (option.value == state.options.firstOrNull()?.value) {
                        Modifier.focusRequester(firstRow)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun AgentRow(
    label: String,
    detail: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .touchClickable(onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = TvSurface,
            focusedContainerColor = TvAccent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Die Kennung selbst darunter: Wer weiß, wonach er sucht,
                // erkennt sie wieder – und wer bei einem Anbieter nachfragt,
                // kann sie vorlesen.
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelMedium,
                        color = TvOnSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (isSelected) {
                Spacer(Modifier.width(TvSpacing.small))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.user_agent_selected),
                    tint = TvOn,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
