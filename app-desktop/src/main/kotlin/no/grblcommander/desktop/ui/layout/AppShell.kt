package no.grblcommander.desktop.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.RightSoftKeySlot
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.handleSoftKeyEvent
import kotlin.math.roundToInt

internal object ShellColors {
    val frame = Color(0xFF0D1420)
    val header = Color(0xFF1A293C)
    val workspace = Color(0xFF0E1826)
    val bottom = Color(0xFF132032)
    val right = Color(0xFF142136)
    val key = Color(0xFF233449)
    val outline = Color(0xFF3F5368)
    val text = Color(0xFFE7EFF8)
    val muted = Color(0xFF94A7BC)
    val accent = Color(0xFF56BBEF)
}

private val headerHeight = 78.dp
private const val softkeyWidthUnits = 4
private const val softkeyHeightUnits = 3

@Composable
fun AppShell(
    state: ShellState,
    currentStack: () -> ScreenStack,
    headerControls: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val liveStack = rememberUpdatedState(currentStack)
    val stack = currentStack()
    val pressedKeys = remember { mutableSetOf<androidx.compose.ui.input.key.Key>() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val (softkeySize, softkeyGap) = with(density) {
            val bottomKeys = BottomSoftKeySlot.entries.size
            val rightKeys = RightSoftKeySlot.entries.size
            val availableWidth = constraints.maxWidth - 2 * bottomSoftkeyPadding.roundToPx() -
                2 * rightSoftkeyPadding.roundToPx()
            val availableHeight = constraints.maxHeight - headerHeight.roundToPx() -
                rightSoftkeyPadding.roundToPx() - bottomSoftkeyPadding.roundToPx()
            val widthUnits = (bottomKeys + 1) * softkeyWidthUnits
            val heightUnits = rightKeys * softkeyHeightUnits
            fun gapInPixels(unit: Int) = (unit * softkeyHeightUnits * standardKeyGapRatio).roundToInt()

            // Keep the exact 4:3 ratio and budget for each rounded gap.
            val maximumUnit = minOf(availableWidth / widthUnits, availableHeight / heightUnits)
            val unit = (maximumUnit downTo 1).firstOrNull { candidate ->
                val gap = gapInPixels(candidate)
                widthUnits * candidate + (bottomKeys - 1) * gap <= availableWidth &&
                    heightUnits * candidate + (rightKeys - 1) * gap <= availableHeight
            } ?: 0
            val size = DpSize((softkeyWidthUnits * unit).toDp(), (softkeyHeightUnits * unit).toDp())
            size to gapInPixels(unit).toDp()
        }
        val bottomBankHeight = with(density) {
            (softkeySize.height.roundToPx() + 2 * bottomSoftkeyPadding.roundToPx()).toDp()
        }
        val rightBankWidth = with(density) {
            (softkeySize.width.roundToPx() + 2 * rightSoftkeyPadding.roundToPx()).toDp()
        }
        val gridUnit = with(density) {
            val workspaceWidth = (constraints.maxWidth - rightBankWidth.roundToPx()).coerceAtLeast(0)
            val workspaceHeight = (constraints.maxHeight - headerHeight.roundToPx() - bottomBankHeight.roundToPx())
                .coerceAtLeast(0)
            workspaceGridUnit(workspaceWidth, workspaceHeight)
        }
        CompositionLocalProvider(LocalGridUnit provides gridUnit) {
            Column(Modifier.fillMaxSize().background(ShellColors.frame)
                .onPreviewKeyEvent { handleSoftKeyEvent(it, liveStack.value, pressedKeys) }) {
                ShellHeader(state, Modifier.fillMaxWidth().height(headerHeight), headerControls)

                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth().background(ShellColors.workspace),
                            contentAlignment = Alignment.Center,
                        ) {
                            content()
                        }
                        BottomSoftkeys(
                            stack, { liveStack.value() }, softkeySize, softkeyGap,
                            Modifier.fillMaxWidth().height(bottomBankHeight),
                        )
                    }
                    RightSoftkeys(
                        stack, { liveStack.value() }, softkeySize, softkeyGap,
                        Modifier.width(rightBankWidth).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

