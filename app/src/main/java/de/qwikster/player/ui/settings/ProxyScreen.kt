package de.qwikster.player.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.qwikster.player.R
import de.qwikster.player.ui.common.touchClickable
import de.qwikster.player.ui.theme.TvAccent
import de.qwikster.player.ui.theme.TvBackground
import de.qwikster.player.ui.theme.TvOnSurface
import de.qwikster.player.ui.theme.TvOnSurfaceMuted
import de.qwikster.player.ui.theme.TvSpacing
import de.qwikster.player.ui.theme.TvSurfaceVariant

/**
 * SOCKS5-Proxy für den gesamten Netzverkehr der App.
 *
 * Ein Proxy statt eines eingebauten VPN: Die App holt alles über einen
 * einzigen HTTP-Client – Playlist, Programmzeitschrift, Update-Prüfung,
 * Live-Streams, Filme und Aufnahmen. Eine Angabe genügt deshalb für alles.
 * Ein VPN müsste dagegen das ganze Gerät umleiten und bräuchte einen
 * eigenen Server; dafür gibt es fertige Apps.
 */
@Composable
fun ProxyScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // Erst füllen, wenn die gespeicherten Werte eingetroffen sind – sonst
    // stünden die Felder leer und ein Speichern löschte die Einstellung.
    var isPrefilled by remember { mutableStateOf(false) }
    LaunchedEffect(state.settings.proxyHost, state.settings.proxyPort) {
        if (isPrefilled) return@LaunchedEffect
        isPrefilled = true
        host = state.settings.proxyHost
        port = state.settings.proxyPort.toString()
        user = state.settings.proxyUser
        password = state.settings.proxyPassword
    }

    BackHandler(enabled = true) { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBackground)
            .padding(
                horizontal = TvSpacing.overscanHorizontal,
                vertical = TvSpacing.overscanVertical,
            ),
    ) {
        Text(stringResource(R.string.proxy_title), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(TvSpacing.small))
        Text(
            text = state.message ?: stringResource(R.string.proxy_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.message != null) TvAccent else TvOnSurfaceMuted,
        )
        Spacer(Modifier.height(TvSpacing.medium))

        Row {
            ProxyField(
                value = host,
                onValueChange = { host = it },
                label = stringResource(R.string.proxy_host),
                modifier = Modifier.width(420.dp),
            )
            Spacer(Modifier.width(TvSpacing.small))
            ProxyField(
                value = port,
                onValueChange = { new -> port = new.filter(Char::isDigit).take(5) },
                label = stringResource(R.string.proxy_port),
                keyboardType = KeyboardType.Number,
                modifier = Modifier.width(160.dp),
            )
        }

        Spacer(Modifier.height(TvSpacing.small))

        Row {
            ProxyField(
                value = user,
                onValueChange = { user = it },
                label = stringResource(R.string.proxy_user),
                modifier = Modifier.width(290.dp),
            )
            Spacer(Modifier.width(TvSpacing.small))
            ProxyField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.proxy_password),
                isPassword = true,
                modifier = Modifier.width(290.dp),
            )
        }

        Spacer(Modifier.height(TvSpacing.medium))

        Row {
            val save = {
                viewModel.saveProxy(host, port.toIntOrNull() ?: 1080, user, password)
            }
            Button(onClick = save, modifier = Modifier.touchClickable(save)) {
                Text(stringResource(R.string.proxy_save))
            }
            Spacer(Modifier.width(TvSpacing.small))
            val disable = {
                host = ""
                viewModel.saveProxy("", port.toIntOrNull() ?: 1080, user, password)
            }
            Button(onClick = disable, modifier = Modifier.touchClickable(disable)) {
                Text(stringResource(R.string.proxy_disable))
            }
        }

        Spacer(Modifier.height(TvSpacing.medium))
        Text(
            text = stringResource(R.string.proxy_note),
            style = MaterialTheme.typography.bodyMedium,
            color = TvOnSurfaceMuted,
        )
    }
}

@Composable
private fun ProxyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { androidx.compose.material3.Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Next,
            keyboardType = keyboardType,
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
        modifier = modifier.fillMaxWidth(),
    )
}
