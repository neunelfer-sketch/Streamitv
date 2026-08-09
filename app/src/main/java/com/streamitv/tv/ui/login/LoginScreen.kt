package com.streamitv.tv.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamitv.tv.data.model.PlaylistType
import com.streamitv.tv.ui.theme.TvAccent
import com.streamitv.tv.ui.theme.TvBackground
import com.streamitv.tv.ui.theme.TvOnSurface
import com.streamitv.tv.ui.theme.TvOnSurfaceMuted
import com.streamitv.tv.ui.theme.TvSpacing
import com.streamitv.tv.ui.theme.TvSurface
import com.streamitv.tv.ui.theme.TvSurfaceVariant

/**
 * Ersteinrichtung: Xtream-Zugangsdaten oder M3U-Link.
 *
 * Die Eingabe per Fernbedienung ist mühsam, deshalb:
 * - so wenige Pflichtfelder wie möglich,
 * - der Fokus startet direkt im ersten Feld,
 * - `imeAction = Next` springt zum jeweils nächsten Feld,
 * - die Server-URL wird beim Speichern automatisch normalisiert
 *   (siehe `XtreamCredentials.normalizedBaseUrl`), sodass ein versehentlich
 *   mitkopiertes `/player_api.php?...` nicht stört.
 */
@Composable
fun LoginScreen(
    onDone: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val firstField = remember { FocusRequester() }

    LaunchedEffect(state.isDone) {
        if (state.isDone) onDone()
    }

    LaunchedEffect(Unit) {
        runCatching { firstField.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBackground),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(720.dp),
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = TvSurface),
        ) {
            Column(
                modifier = Modifier
                    .padding(TvSpacing.large)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("StreamiTV einrichten", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(TvSpacing.small))
                Text(
                    text = "Wähle den Typ deiner Playlist",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TvOnSurfaceMuted,
                )

                Spacer(Modifier.height(TvSpacing.medium))

                // --- Typauswahl -------------------------------------------------
                Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.small)) {
                    TypeChip(
                        label = "Xtream Codes",
                        isSelected = state.type == PlaylistType.XTREAM,
                        onClick = { viewModel.setType(PlaylistType.XTREAM) },
                    )
                    TypeChip(
                        label = "M3U / M3U8 Link",
                        isSelected = state.type == PlaylistType.M3U,
                        onClick = { viewModel.setType(PlaylistType.M3U) },
                    )
                }

                Spacer(Modifier.height(TvSpacing.medium))

                // --- Felder -----------------------------------------------------
                TvTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = "Name der Playlist",
                    modifier = Modifier.focusRequester(firstField),
                )

                if (state.type == PlaylistType.XTREAM) {
                    TvTextField(
                        value = state.serverUrl,
                        onValueChange = viewModel::setServerUrl,
                        label = "Server-URL (z. B. http://server.tv:8080)",
                        keyboardType = KeyboardType.Uri,
                    )
                    TvTextField(
                        value = state.username,
                        onValueChange = viewModel::setUsername,
                        label = "Benutzername",
                    )
                    TvTextField(
                        value = state.password,
                        onValueChange = viewModel::setPassword,
                        label = "Passwort",
                        isPassword = true,
                    )
                } else {
                    TvTextField(
                        value = state.m3uUrl,
                        onValueChange = viewModel::setM3uUrl,
                        label = "M3U-URL",
                        keyboardType = KeyboardType.Uri,
                    )
                }

                TvTextField(
                    value = state.epgUrl,
                    onValueChange = viewModel::setEpgUrl,
                    label = "EPG-URL (XMLTV, optional)",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                )

                Spacer(Modifier.height(TvSpacing.medium))

                // --- Status / Fehler --------------------------------------------
                state.statusMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = TvAccent)
                    Spacer(Modifier.height(TvSpacing.small))
                }
                state.errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(TvSpacing.small))
                }

                Button(
                    onClick = viewModel::submit,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isBusy) "Verbinde…" else "Verbinden")
                }
            }
        }
    }
}

@Composable
private fun TypeChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) TvAccent.copy(alpha = 0.35f) else TvSurfaceVariant,
            focusedContainerColor = TvAccent,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/**
 * Eingabefeld im TV-Look.
 *
 * `androidx.tv.material3` bringt keine Textfelder mit, deshalb hier das
 * Material3-Feld mit angepassten Farben – wichtig ist vor allem ein klar
 * sichtbarer Fokus-Rahmen.
 */
@Composable
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { androidx.compose.material3.Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
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
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    )
}
