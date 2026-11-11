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
import no.grblcommander.desktop.ui.softkeys.*
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

@Composable
private fun ShellHeader(
    state: ShellState,
    modifier: Modifier = Modifier,
    headerControls: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.background(ShellColors.header).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Image(
                painter = painterResource(BrandAssets.ICON),
                contentDescription = "GRBL Commander logo",
                modifier = Modifier.width(72.dp).height(48.dp),
                contentScale = ContentScale.Fit,
            )
            Column {
                Text("GRBL", color = ShellColors.text, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text("COMMANDER", color = ShellColors.accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.4.sp)
            }
        }
        Box(Modifier.width(1.dp).height(38.dp).background(ShellColors.outline))
        Column(Modifier.weight(1f)) {
            Text(state.title, color = ShellColors.text, fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
            Text(
                state.context,
                color = ShellColors.muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        headerControls()
        Box(
            Modifier
                .border(1.dp, ShellColors.outline, RoundedCornerShape(6.dp))
                .background(ShellColors.workspace, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(state.connectionStatus, color = ShellColors.muted, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun BottomSoftkeys(stack: ScreenStack, currentStack: () -> ScreenStack, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.background(ShellColors.bottom).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        BottomSoftKeySlot.entries.forEach { slot ->
            SoftkeyTile(slot, stack.softKeyBinding(slot), currentStack, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun RightSoftkeys(stack: ScreenStack, currentStack: () -> ScreenStack, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(ShellColors.right).padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RightSoftKeySlot.entries.forEach { slot ->
            SoftkeyTile(slot, stack.softKeyBinding(slot), currentStack, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
internal fun SoftkeyTile(
    slot: SoftKeySlot,
    binding: SoftKeyBinding?,
    currentStack: () -> ScreenStack,
    modifier: Modifier = Modifier,
) {
    // A replacement/removed binding disposes pending pointer gestures and focus.
    // Ordinary predicate changes keep the same binding identity and composition.
    key(binding) {
        val definition = binding?.definition ?: SoftKeyDefinition.Blank
        // Snapshot reads occur in composition, including predicates retained by modifiers.
        val enabled = definition.enabled() && definition.action != null
        val active = definition.active()
        val foreground = if (enabled) ShellColors.text else ShellColors.muted
        Column(
            modifier = modifier
                .testTag("softkey-${slot.shortcut}")
                .semantics { selected = active }
                .border(1.dp, if (active) ShellColors.accent else ShellColors.outline, RoundedCornerShape(6.dp))
                .background(if (active) ShellColors.header else ShellColors.key, RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, role = Role.Button) {
                    activateSoftKey(currentStack, slot, binding)
                }
                .padding(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Undefined and explicit Blank slots have no text or icon, including hints.
            if (definition.label.isNotEmpty() || definition.icon != null) {
                Text(slot.shortcut, color = ShellColors.muted, fontSize = 10.sp)
                Spacer(Modifier.height(7.dp))
                definition.icon?.let { Icon(it, contentDescription = null, tint = foreground, modifier = Modifier.height(20.dp)) }
                Text(
                    definition.label,
                    color = foreground,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
