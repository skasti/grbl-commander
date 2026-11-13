package no.grblcommander.desktop.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.ScreenStack

@Composable
internal fun BottomSoftkeys(stack: ScreenStack, currentStack: () -> ScreenStack, modifier: Modifier = Modifier.Companion) {
    Row(
        modifier = modifier.background(ShellColors.bottom).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        BottomSoftKeySlot.entries.forEach { slot ->
            SoftkeyTile(slot, stack, currentStack, Modifier.weight(1f).fillMaxHeight())
        }
    }
}