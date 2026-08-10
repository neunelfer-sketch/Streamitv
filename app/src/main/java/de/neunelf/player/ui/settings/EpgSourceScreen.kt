package de.neunelf.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.neunelf.player.ui.theme.TvAccent
import de.neunelf.player.ui.theme.TvBackground
import de.neunelf.player.ui.theme.TvOnSurface
import de.neunelf.player.ui.theme.TvOnSurfaceMuted
import de.neunelf.player.ui.theme.TvSpacing
import de.neunelf.player.ui.theme.TvSurfaceVariant

/**
 * Eingabe der EPG-Quelle nach der Ersteinrichtung.
 *
 * Bis hierher ließ sich eine XMLTV-Adresse nur beim allerersten Einrichten
 * eintragen. Wer damals keine hatte – oder eine falsche –, kam ohne
 * Löschen der ganzen Playlist nicht mehr heran. Genau das ist der übliche
 * Fall: Die Adresse bekommt man oft erst später vom Anbieter.
 *
 * Bei Xtream ist das Feld optional; ohne Eintrag holt die App die Daten
 * über `xmltv.php` des Panels. Bei M3U übernimmt sie eine im Kopf der
 * Datei angegebene `url-tvg`, sofern vorhanden.
 */
@Composable
fun EpgSourceScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }

    // Startwert erst setzen, wenn die Playlist geladen ist – sonst bliebe
    // das Feld leer, obwohl längst eine Adresse hinterlegt ist.
    var url by remember(state.epgUrl) { mutableStateOf(state.epgUrl) }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBackground)
            .padding(
                horizontal = TvSpacing.overscanHorizontal,
                vertical = TvSpacing.overscanVertical,
            ),
    ) {
        Text("EPG-Quelle", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(TvSpacing.small))
        Text(
            text = "Adresse einer XMLTV-Datei. Leer lassen, um bei Xtream die " +
                "Programmdaten des Panels zu verwenden.",
            style = MaterialTheme.typography.bodyLarge,
            color = TvOnSurfaceMuted,
        )

        Spacer(Modifier.height(TvSpacing.medium))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { androidx.compose.material3.Text("XMLTV-URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = TvSurfaceVariant,
                unfocusedContainerColor = TvSurfaceVariant,
                focusedIndicatorColor = TvAccent,
                unfocusedIndicatorColor = TvOnSurfaceMuted,
                focusedTextColor = TvOnSurface,
                unfocusedTextColor = TvOnSurface,
                focusedLabelColor = TvAccent,
                unfocusedLabelColor = TvOnSurfaceMuted,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
        )

        Spacer(Modifier.height(TvSpacing.medium))

        Button(
            onClick = {
                viewModel.setEpgUrl(url)
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Speichern und Programmdaten laden")
        }

        Spacer(Modifier.height(TvSpacing.small))
        Text(
            text = "Die Programmdaten werden anschließend beim nächsten Aufruf " +
                "des Hauptbildschirms geladen. Bei großen Dateien dauert das " +
                "einige Minuten.",
            style = MaterialTheme.typography.bodyMedium,
            color = TvOnSurfaceMuted,
        )
    }
}
