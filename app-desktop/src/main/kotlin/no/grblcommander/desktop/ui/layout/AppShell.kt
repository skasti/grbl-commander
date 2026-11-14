package no.grblcommander.desktop.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
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

@Composable
fun AppShell(
    state: ShellState,
    currentStack: () -> ScreenStack,
    headerControls: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val liveStack = rememberUpdatedState(currentStack)
    val stack = currentStack()
    Column(Modifier.fillMaxSize().background(ShellColors.frame)
        .onPreviewKeyEvent { handleSoftKeyEvent(it, liveStack.value) }) {
        ShellHeader(state, Modifier.fillMaxWidth().height(78.dp), headerControls)

        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().background(ShellColors.workspace),
                    contentAlignment = Alignment.Center,
                ) {
                    content()
                }
                BottomSoftkeys(stack, { liveStack.value() }, Modifier.fillMaxWidth().height(94.dp))
            }
            RightSoftkeys(stack, { liveStack.value() }, Modifier.width(142.dp).fillMaxHeight())
        }
    }
}

