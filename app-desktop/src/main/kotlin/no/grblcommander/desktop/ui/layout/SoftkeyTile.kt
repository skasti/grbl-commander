package no.grblcommander.desktop.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.RightSoftKeySlot
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.SoftKeyDefinition
import no.grblcommander.desktop.ui.navigation.SoftKeySlot
import no.grblcommander.desktop.ui.navigation.activateSoftKey

@Composable
internal fun SoftkeyTile(
    slot: SoftKeySlot,
    stack: ScreenStack,
    currentStack: () -> ScreenStack,
    modifier: Modifier = Modifier.Companion,
) {
    val definition = when (slot) {
        is BottomSoftKeySlot -> stack.effectiveBottomKeys[slot]
        is RightSoftKeySlot -> stack.effectiveRightKeys[slot]
    } ?: SoftKeyDefinition.Blank
    // Recompose/dispose clickable when the visible screen's attachment lifetime
    // or effective definition changes. Ordinary enabled/active state does not
    // change these keys and is observed directly by Compose.
    key(stack, stack.entries.last(), definition) {
        // Snapshot reads occur in composition, including predicates retained by modifiers.
        val enabled = definition.enabled() && definition.action != null
        val active = definition.active()
        val foreground = if (enabled) ShellColors.text else ShellColors.muted
        Column(
            modifier = modifier
                .testTag("softkey-${slot.shortcut}")
                .semantics { selected = active }
                .border(1.dp, if (active) ShellColors.accent else ShellColors.outline, RoundedCornerShape(6.dp))
                .background(
                    if (active) ShellColors.header else ShellColors.key,
                    androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                )
                .clickable(enabled = enabled, role = Role.Button) {
                    activateSoftKey(currentStack, slot)
                }
                .padding(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Undefined and explicit Blank slots have no text or icon, including hints.
            if (definition.label.isNotEmpty() || definition.icon != null) {
                Text(slot.shortcut, color = ShellColors.muted, fontSize = 10.sp)
                Spacer(Modifier.height(7.dp))
                definition.icon?.let {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = foreground,
                        modifier = Modifier.height(20.dp)
                    )
                }
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