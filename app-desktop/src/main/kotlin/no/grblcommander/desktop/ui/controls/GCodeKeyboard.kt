package no.grblcommander.desktop.ui.controls

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
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

internal val keypadPanelMargin = 30.dp

/** Two independent clusters. Owners explicitly bind all actions, including context-dependent Enter. */
@Composable
fun GCodeKeyboard(
    onAction: (KeypadAction) -> Unit,
    modifier: Modifier = Modifier,
    letters: KeyClusterState = remember { KeyClusterState(GCodeKeymaps.letters) },
    numbers: KeyClusterState = remember { KeyClusterState(GCodeKeymaps.numbers) },
) {
    KeypadPanel(listOf(letters, numbers), onAction, modifier)
}

@Composable
internal fun KeypadPanel(
    clusters: List<KeyClusterState>,
    onAction: (KeypadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.BottomCenter) {
        val density = LocalDensity.current
        val minimum = with(density) { ceil(44.dp.toPx()).toInt() }
        val maximum = with(density) { floor(72.dp.toPx()).toInt() }
        val pageHeight = minimum
        val count = clusters.size
        fun gridsHeight(size: Int) = clusters.sumOf { it.layout.heightInPixels(size) }
        fun gridsWidth(size: Int) = clusters.maxOf { it.layout.widthInPixels(size) }
        val preferredMargin = with(density) { keypadPanelMargin.roundToPx() }
        val horizontalMargin = minOf(preferredMargin, (constraints.maxWidth - gridsWidth(minimum)).coerceAtLeast(0) / 2)
        val minimumHeight = gridsHeight(minimum) + count * pageHeight
        val verticalMargin = minOf(preferredMargin, (constraints.maxHeight - minimumHeight).coerceAtLeast(0) / 2)
        val availableWidth = constraints.maxWidth - 2 * horizontalMargin
        val availableHeight = constraints.maxHeight - 2 * verticalMargin
        // Fit the largest square using compact outer spacing, never changing keymap gaps.
        val keyPixels = (maximum downTo minimum).firstOrNull { size ->
            gridsWidth(size) <= availableWidth &&
                gridsHeight(size) + count * pageHeight <= availableHeight
        } ?: minimum
        val spareHeight = (availableHeight - gridsHeight(keyPixels) - count * pageHeight).coerceAtLeast(0)
        val pageSpacingPixels = minOf(with(density) { 8.dp.roundToPx() },
            spareHeight / count)
        val clusterSpacingPixels = minOf(with(density) { 16.dp.roundToPx() },
            (spareHeight - count * pageSpacingPixels) / (count - 1).coerceAtLeast(1))
        val keySize = with(density) { keyPixels.toDp() }
        val pageSpacing = with(density) { pageSpacingPixels.toDp() }
        val contentWidth = gridsWidth(keyPixels)
        val contentHeight = gridsHeight(keyPixels) + count * (pageHeight + pageSpacingPixels) + (count - 1) * clusterSpacingPixels
        val scrollVertically = contentHeight > availableHeight
        val scrollHorizontally = contentWidth > availableWidth
        // Separate scroll containers expose each axis correctly when a custom map or viewport cannot fit.
        val margin = with(density) {
            Modifier.padding(horizontal = horizontalMargin.toDp(), vertical = verticalMargin.toDp())
        }
        Box(margin.clipToBounds().verticalScroll(rememberScrollState(), enabled = scrollVertically)) {
            Column(
                Modifier.horizontalScroll(rememberScrollState(), enabled = scrollHorizontally),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                clusters.forEachIndexed { index, cluster ->
                    if (index > 0) Spacer(Modifier.height(with(density) { clusterSpacingPixels.toDp() }))
                    KeyCluster(cluster, keySize, onAction, pageSpacing = pageSpacing)
                }
            }
        }
    }
}
