package no.grblcommander.desktop.ui.layout

import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.rememberUpdatedState
import no.grblcommander.desktop.ui.navigation.ScreenStack
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import no.grblcommander.desktop.ui.BrandAssets
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.RightSoftKeySlot
import no.grblcommander.desktop.ui.navigation.SoftKeyDefinition
import no.grblcommander.desktop.ui.navigation.SoftKeySlot
import no.grblcommander.desktop.ui.navigation.activateSoftKey
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

