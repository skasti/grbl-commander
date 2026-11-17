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

/** Two independent clusters. Owners explicitly bind all actions, including context-dependent Enter. */
@Composable
fun GCodeKeyboard(
    onAction: (KeypadAction) -> Unit,
    modifier: Modifier = Modifier,
    letters: KeyClusterState = remember { KeyClusterState(GCodeKeymaps.letters) },
    numbers: KeyClusterState = remember { KeyClusterState(GCodeKeymaps.numbers) },
) {
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.BottomCenter) {
        val density = LocalDensity.current
        val minimum = with(density) { ceil(44.dp.toPx()).toInt() }
        val maximum = with(density) { floor(72.dp.toPx()).toInt() }
        val pageHeight = minimum
        fun gridsHeight(size: Int) = letters.layout.heightInPixels(size) + numbers.layout.heightInPixels(size)
        // Fit the largest square using compact outer spacing, never changing keymap gaps.
        val keyPixels = (maximum downTo minimum).firstOrNull { size ->
            maxOf(letters.layout.widthInPixels(size), numbers.layout.widthInPixels(size)) <= constraints.maxWidth &&
                gridsHeight(size) + 2 * pageHeight <= constraints.maxHeight
        } ?: minimum
        val spareHeight = (constraints.maxHeight - gridsHeight(keyPixels) - 2 * pageHeight).coerceAtLeast(0)
        val pageSpacingPixels = minOf(with(density) { 8.dp.roundToPx() },
            spareHeight / 2)
        val clusterSpacingPixels = minOf(with(density) { 16.dp.roundToPx() },
            (spareHeight - 2 * pageSpacingPixels).coerceAtLeast(0))
        val keySize = with(density) { keyPixels.toDp() }
        val pageSpacing = with(density) { pageSpacingPixels.toDp() }
        val contentWidth = maxOf(letters.layout.widthInPixels(keyPixels), numbers.layout.widthInPixels(keyPixels))
        val contentHeight = gridsHeight(keyPixels) + 2 * (pageHeight + pageSpacingPixels) + clusterSpacingPixels
        val scrollVertically = contentHeight > constraints.maxHeight
        val scrollHorizontally = contentWidth > constraints.maxWidth
        // Separate scroll containers expose each axis correctly when a custom map or viewport cannot fit.
        Box(Modifier.verticalScroll(rememberScrollState(), enabled = scrollVertically)) {
            Column(
                Modifier.horizontalScroll(rememberScrollState(), enabled = scrollHorizontally),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                KeyCluster(letters, keySize, onAction, pageSpacing = pageSpacing)
                Spacer(Modifier.height(with(density) { clusterSpacingPixels.toDp() }))
                KeyCluster(numbers, keySize, onAction, pageSpacing = pageSpacing)
            }
        }
    }
}
