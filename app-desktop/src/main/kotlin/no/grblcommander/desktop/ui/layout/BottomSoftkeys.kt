package no.grblcommander.desktop.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.ScreenStack

internal val bottomSoftkeyPadding = 8.dp

@Composable
internal fun BottomSoftkeys(
    stack: ScreenStack,
    currentStack: () -> ScreenStack,
    tileSize: DpSize,
    gap: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.background(ShellColors.bottom).padding(bottomSoftkeyPadding),
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
    ) {
        BottomSoftKeySlot.entries.forEach { slot ->
            SoftkeyTile(slot, stack, currentStack, Modifier.size(tileSize))
        }
    }
}
