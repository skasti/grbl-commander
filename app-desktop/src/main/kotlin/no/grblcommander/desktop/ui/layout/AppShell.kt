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
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.handleSoftKeyEvent

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
private val bottomBankHeight = 94.dp
private val rightBankWidth = 142.dp

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
        val keyUnit = with(LocalDensity.current) {
            val workspaceWidth = (constraints.maxWidth - rightBankWidth.roundToPx()).coerceAtLeast(0)
            val workspaceHeight = (constraints.maxHeight - headerHeight.roundToPx() - bottomBankHeight.roundToPx())
                .coerceAtLeast(0)
            workspaceKeyUnit(workspaceWidth, workspaceHeight)
        }
        CompositionLocalProvider(LocalKeyUnit provides keyUnit) {
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
                        BottomSoftkeys(stack, { liveStack.value() }, Modifier.fillMaxWidth().height(bottomBankHeight))
                    }
                    RightSoftkeys(stack, { liveStack.value() }, Modifier.width(rightBankWidth).fillMaxHeight())
                }
            }
        }
    }
}

