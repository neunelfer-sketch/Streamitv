package de.qwikster.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.qwikster.player.R
import de.qwikster.player.core.CrashReporter
import de.qwikster.player.ui.common.dpadEvents
import de.qwikster.player.ui.theme.TvAccent
import de.qwikster.player.ui.theme.TvBackground
import de.qwikster.player.ui.theme.TvOnSurfaceMuted
import de.qwikster.player.ui.theme.TvSpacing
import kotlinx.coroutines.launch

/**
 * Zeigt den zuletzt aufgezeichneten Absturz – siehe [CrashReporter].
 *
 * Bewusst ohne fokussierbare Zeilen: Der Text soll gelesen und abgetippt
 * oder abfotografiert werden, nicht durchgeklickt. Das Steuerkreuz blättert
 * deshalb direkt in der Liste, und OK löscht den Bericht. Zwei Bedienungen,
 * beide auf dem Bildschirm angeschrieben – auf einer Fernbedienung ist das
 * verlässlicher als Knöpfe, zwischen denen man erst navigieren muss.
 */
@Composable
fun CrashReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var report by remember { mutableStateOf(CrashReporter.read(context)) }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    // Eine Bildschirmhöhe je Druck wäre zu grob, eine Zeile zu fein –
    // ein knappes Drittel liest sich flüssig weiter.
    val scrollStep = 240f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBackground)
            .focusRequester(focusRequester)
            .focusable()
            .dpadEvents(
                onUp = {
                    scope.launch { listState.animateScrollBy(-scrollStep) }
                    true
                },
                onDown = {
                    scope.launch { listState.animateScrollBy(scrollStep) }
                    true
                },
                onSelect = {
                    CrashReporter.clear(context)
                    report = null
                    true
                },
                onBack = { onBack(); true },
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = TvSpacing.overscanHorizontal,
                    vertical = TvSpacing.overscanVertical,
                ),
        ) {
            Text(
                text = stringResource(R.string.crash_title),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = stringResource(R.string.crash_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TvAccent,
            )
            Box(Modifier.height(TvSpacing.medium))

            val lines = report?.lines()
            if (lines.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.crash_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TvOnSurfaceMuted,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = TvSpacing.large),
                ) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            // Feste Zeichenbreite: Ein Stapelabzug lebt von
                            // seiner Einrückung, die eine Proportionalschrift
                            // zerreißt.
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}
