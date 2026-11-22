package no.grblcommander.desktop.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.navigation.RightSoftKeySlot
import no.grblcommander.desktop.ui.navigation.ScreenStack

internal val rightSoftkeyTopPadding = 9.dp

@Composable
internal fun RightSoftkeys(
    stack: ScreenStack,
    currentStack: () -> ScreenStack,
    tileSize: DpSize,
    gap: Dp,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.background(ShellColors.right).padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap, Alignment.Bottom),
    ) {
        RightSoftKeySlot.entries.forEach { slot ->
            SoftkeyTile(slot, stack, currentStack, Modifier.size(tileSize))
        }
    }
}
