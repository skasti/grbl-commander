package no.grblcommander.desktop.ui.components.controls

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.layout.LocalGridUnit
import kotlin.math.ceil

internal val keypadPanelMargin = 30.dp

/** Two independent clusters. Owners explicitly bind all actions, including context-dependent Enter. */
@Composable
fun GCodeKeyboard(
    onAction: (KeypadAction) -> Unit,
    modifier: Modifier = Modifier,
    letters: KeyClusterState = remember { KeyClusterState(Keymaps.code) },
    numbers: KeyClusterState = remember { KeyClusterState(Keymaps.numPlus) },
    keySize: Dp = LocalGridUnit.current,
) {
    KeypadPanel(listOf(letters, numbers), onAction, modifier, keySize)
}

@Composable
internal fun KeypadPanel(
    clusters: List<KeyClusterState>,
    onAction: (KeypadAction) -> Unit,
    modifier: Modifier = Modifier,
    keySize: Dp = LocalGridUnit.current,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.BottomEnd) {
        val density = LocalDensity.current
        val keyPixels = with(density) { keySize.roundToPx() }
        val pageHeight = with(density) { ceil(44.dp.toPx()).toInt() }
        val pageControlCount = clusters.count { it.layout.pages.size > 1 }
        val pageControlsHeight = pageControlCount * pageHeight
        val clusterGaps = clusters.size - 1
        val contentWidth = clusters.maxOf { it.layout.widthInPixels(keyPixels) }
        val gridsHeight = clusters.sumOf { it.layout.heightInPixels(keyPixels) }
        val compactHeight = gridsHeight + pageControlsHeight
        val preferredMargin = with(density) { keypadPanelMargin.roundToPx() }
        val horizontalMargin = minOf(preferredMargin, (constraints.maxWidth - contentWidth).coerceAtLeast(0) / 2)
        val verticalMargin = minOf(preferredMargin, (constraints.maxHeight - compactHeight).coerceAtLeast(0) / 2)
        val availableWidth = constraints.maxWidth - 2 * horizontalMargin
        val availableHeight = constraints.maxHeight - 2 * verticalMargin
        val spareHeight = (availableHeight - compactHeight).coerceAtLeast(0)
        val pageSpacingPixels = if (pageControlCount == 0) 0 else
            minOf(with(density) { 8.dp.roundToPx() }, spareHeight / pageControlCount)
        val clusterSpacingPixels = minOf(with(density) { 16.dp.roundToPx() },
            (spareHeight - pageControlCount * pageSpacingPixels) / clusterGaps.coerceAtLeast(1))
        val pageSpacing = with(density) { pageSpacingPixels.toDp() }
        val contentHeight = compactHeight + pageControlCount * pageSpacingPixels + clusterGaps * clusterSpacingPixels
        val scrollVertically = contentHeight > availableHeight
        val scrollHorizontally = contentWidth > availableWidth
        // Separate scroll containers expose each axis correctly when a custom map or viewport cannot fit.
        val margin = with(density) {
            Modifier.padding(horizontal = horizontalMargin.toDp(), vertical = verticalMargin.toDp())
        }
        Box(margin.clipToBounds().verticalScroll(rememberScrollState(), enabled = scrollVertically)) {
            Column(
                Modifier.horizontalScroll(rememberScrollState(), enabled = scrollHorizontally),
                horizontalAlignment = Alignment.End,
            ) {
                clusters.forEachIndexed { index, cluster ->
                    if (index > 0) Spacer(Modifier.height(with(density) { clusterSpacingPixels.toDp() }))
                    KeyCluster(cluster, onAction, keySize = keySize, pageSpacing = pageSpacing)
                }
            }
        }
    }
}
