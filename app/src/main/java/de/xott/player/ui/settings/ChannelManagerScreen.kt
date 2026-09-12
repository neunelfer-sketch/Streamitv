package de.xott.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.xott.player.data.model.Category
import de.xott.player.data.model.ManagedChannel
import de.xott.player.ui.theme.TvAccent
import de.xott.player.ui.theme.TvBackground
import de.xott.player.ui.theme.TvOnSurfaceMuted
import de.xott.player.ui.theme.TvSpacing
import de.xott.player.ui.theme.TvSurfaceVariant

/**
 * Sender einer Kategorie ausblenden oder umsortieren.
 *
 * Bewusst ohne Drag & Drop: auf einer Fernbedienung lässt sich Ziehen kaum
 * zuverlässig steuern. Stattdessen bekommt jede Zeile zwei explizite
 * Tasten ("hoch"/"runter"), die die Reihenfolge sofort neu speichern.
 */
@Composable
fun ChannelManagerScreen(
    viewModel: ChannelManagerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBackground)
            .padding(horizontal = TvSpacing.overscanHorizontal, vertical = TvSpacing.overscanVertical),
    ) {
        Text("Sender verwalten", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(TvSpacing.small))
        Text(
            "Ausblenden oder mit ▲/▼ neu sortieren",
            style = MaterialTheme.typography.bodyMedium,
            color = TvOnSurfaceMuted,
        )
        Spacer(Modifier.height(TvSpacing.medium))

        Row(modifier = Modifier.fillMaxSize()) {
            CategoryList(
                categories = state.categories,
                selectedCategoryId = state.selectedCategoryId,
                onSelect = { viewModel.selectCategory(it.id) },
                modifier = Modifier.width(260.dp).fillMaxHeight(),
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = TvSpacing.large),
                contentPadding = PaddingValues(bottom = TvSpacing.large),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.channels, key = { it.streamId }) { channel ->
                    ManagedChannelRow(
                        channel = channel,
                        onMoveUp = { viewModel.moveUp(channel) },
                        onMoveDown = { viewModel.moveDown(channel) },
                        onToggleHidden = { viewModel.toggleHidden(channel) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryList(
    categories: List<Category>,
    selectedCategoryId: String?,
    onSelect: (Category) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(categories, key = { it.id }) { category ->
            Surface(
                onClick = { onSelect(category) },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = if (category.id == selectedCategoryId) TvSurfaceVariant else Color.Transparent,
                    focusedContainerColor = TvAccent,
                ),
            ) {
                Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    Text(category.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ManagedChannelRow(
    channel: ManagedChannel,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleHidden: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(TvSurfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = TvSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = channel.name,
            style = MaterialTheme.typography.titleMedium,
            color = if (channel.isHidden) TvOnSurfaceMuted else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (channel.isHidden) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        RowButton(icon = Icons.Default.KeyboardArrowUp, contentDescription = "Nach oben", onClick = onMoveUp)
        RowButton(icon = Icons.Default.KeyboardArrowDown, contentDescription = "Nach unten", onClick = onMoveDown)
        RowButton(
            icon = if (channel.isHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = if (channel.isHidden) "Einblenden" else "Ausblenden",
            onClick = onToggleHidden,
        )
    }
}

@Composable
private fun RowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = TvAccent,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.padding(8.dp).size(24.dp),
        )
    }
}
