package no.grblcommander.desktop.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.navigation.RightSoftKeySlot
import no.grblcommander.desktop.ui.navigation.ScreenStack

@Composable
internal fun RightSoftkeys(stack: ScreenStack, currentStack: () -> ScreenStack, modifier: Modifier = Modifier.Companion) {
    Column(
        modifier = modifier.background(ShellColors.right).padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RightSoftKeySlot.entries.forEach { slot ->
            SoftkeyTile(slot, stack, currentStack, Modifier.weight(1f).fillMaxWidth())
        }
    }
}