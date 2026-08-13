package de.qwikster.player.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.qwikster.player.R
import de.qwikster.player.data.model.StreamKind
import de.qwikster.player.ui.common.touchClickable
import de.qwikster.player.ui.theme.TvAccent
import de.qwikster.player.ui.theme.TvBackground
import de.qwikster.player.ui.theme.TvOn
import de.qwikster.player.ui.theme.TvOnSurfaceMuted
import de.qwikster.player.ui.theme.TvSpacing
import de.qwikster.player.ui.theme.TvSurface
import de.qwikster.player.ui.theme.TvSurfaceElevated

/**
 * Kategorien ein- und ausblenden.
 *
 * Panels liefern regelmäßig dreistellig viele Kategorien, den größten Teil
 * davon in Sprachen, die den Zuschauer nichts angehen. Ohne diesen
 * Bildschirm blättert er bei jedem Aufruf an dutzenden fremdsprachigen
 * Reitern vorbei.
 *
 * Bedienung mit der Fernbedienung: Die Reiter oben wechseln den Bereich, OK
 * schaltet eine Kategorie um. Ganz oben stehen zwei Schaltflächen für alles
 * auf einmal – bei dreistelligen Zahlen ist "erst alles aus, dann die
 * Handvoll wieder an" der deutlich kürzere Weg.
 */
@Composable
fun CategoryVisibilityScreen(
    onBack: () -> Unit,
    viewModel: CategoryVisibilityViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val firstTab = remember { FocusRequester() }
    val listState = rememberLazyListState()

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(Unit) { runCatching { firstTab.requestFocus() } }

    // Bereichswechsel -> Liste wieder ganz nach oben. Sonst stünde man in der
    // neuen Liste mitten im Bestand, ohne deren Anfang je gesehen zu haben.
    LaunchedEffect(state.kind) { runCatching { listState.scrollToItem(0) } }

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
            text = stringResource(R.string.categories_title),
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = stringResource(
                R.string.categories_subtitle,
                state.visibleCount,
                state.categories.size,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = TvOnSurfaceMuted,
        )
        Spacer(Modifier.height(TvSpacing.medium))

        // --- Bereichsreiter ---------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.small)) {
            KindTab(
                label = stringResource(R.string.nav_live),
                isSelected = state.kind == StreamKind.LIVE,
                onClick = { viewModel.selectKind(StreamKind.LIVE) },
                modifier = Modifier.focusRequester(firstTab),
            )
            KindTab(
                label = stringResource(R.string.content_movies),
                isSelected = state.kind == StreamKind.VOD,
                onClick = { viewModel.selectKind(StreamKind.VOD) },
            )
            KindTab(
                label = stringResource(R.string.content_series),
                isSelected = state.kind == StreamKind.SERIES,
                onClick = { viewModel.selectKind(StreamKind.SERIES) },
            )
        }

        Spacer(Modifier.height(TvSpacing.small))

        // --- Alles auf einmal -------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.small)) {
            BulkButton(
                label = stringResource(R.string.categories_show_all),
                onClick = viewModel::showAll,
            )
            BulkButton(
                label = stringResource(R.string.categories_hide_all),
                onClick = viewModel::hideAll,
            )
        }

        Spacer(Modifier.height(TvSpacing.medium))

        if (state.categories.isEmpty()) {
            Text(
                text = stringResource(R.string.categories_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = TvOnSurfaceMuted,
            )
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = TvSpacing.large),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(state.categories, key = { it.id }) { category ->
                CategoryRow(
                    name = category.name,
                    count = category.channelCount,
                    isVisible = state.isVisible(category.id),
                    onClick = { viewModel.toggle(category.id) },
                )
            }
        }
    }
}

@Composable
private fun KindTab(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.touchClickable(onClick),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) TvSurfaceElevated else Color.Transparent,
            focusedContainerColor = TvAccent,
            contentColor = if (isSelected) TvAccent else TvOnSurfaceMuted,
            focusedContentColor = Color.White,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun BulkButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.touchClickable(onClick),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = TvAccent.copy(alpha = 0.18f),
            focusedContainerColor = TvAccent,
            contentColor = TvAccent,
            focusedContentColor = Color.White,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun CategoryRow(
    name: String,
    count: Int,
    isVisible: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .touchClickable(onClick),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = TvSurface,
            focusedContainerColor = TvAccent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = Color.White,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = categoryLabel(name),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (count > 0) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = TvOnSurfaceMuted,
                )
                Spacer(Modifier.width(TvSpacing.medium))
            }
            VisibilitySwitch(isOn = isVisible)
        }
    }
}

/**
 * Ein Schalter, der aussieht wie einer – ohne es zu sein.
 *
 * Auf dem Fernseher gibt es nichts zu schieben; geschaltet wird mit OK auf
 * der ganzen Zeile. Der Knauf zeigt nur den Zustand an und muss deshalb
 * selbst weder fokussierbar noch bedienbar sein. Ein echtes Bedienelement
 * daneben wäre eine zweite Station für den Fokus, die nichts hinzufügt.
 */
@Composable
private fun VisibilitySwitch(isOn: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (isOn) TvOn else TvSurfaceElevated),
        contentAlignment = if (isOn) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White),
        )
    }
}

/**
 * Setzt den Kategorienamen zweistufig.
 *
 * Panels hängen Technisches an den Namen an – "NETFLIX MOVIES 4K 3840P
 * Dolby Vision" oder "UK| NEWS HD/RAW". Das ist im Zweifel nützlich, drängt
 * sich aber vor den eigentlichen Namen. Der Zusatz wird deshalb kleiner und
 * blasser gesetzt: Er bleibt lesbar, aber das Auge findet zuerst, wonach es
 * sucht.
 */
@Composable
private fun categoryLabel(name: String) = buildAnnotatedString {
    val match = TECHNICAL_SUFFIX.find(name)
    if (match == null) {
        append(name)
        return@buildAnnotatedString
    }
    append(name.substring(0, match.range.first).trimEnd())
    withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = TvOnSurfaceMuted)) {
        append("  " + match.value.trim())
    }
}

/**
 * Der technische Teil eines Kategorienamens.
 *
 * Erkannt wird ab dem ersten Auflösungs- oder Tonformat-Merkmal bis zum
 * Ende – alles davor ist der eigentliche Name. Bewusst schlicht gehalten:
 * Trifft die Erkennung nicht, steht der Name eben vollständig da, was
 * niemandem schadet.
 */
private val TECHNICAL_SUFFIX =
    Regex("""\b(4K|8K|UHD|FHD|HD/RAW|HEVC|RAW|\d{3,4}P|Dolby.*|Multi-?subs?)\b.*$""", RegexOption.IGNORE_CASE)
