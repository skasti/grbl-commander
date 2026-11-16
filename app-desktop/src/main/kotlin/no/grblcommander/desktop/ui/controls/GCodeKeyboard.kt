package no.grblcommander.desktop.ui.controls

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Two independent clusters. Owners explicitly bind all actions, including context-dependent Enter. */
@Composable
fun GCodeKeyboard(
    onAction: (KeypadAction) -> Unit,
    modifier: Modifier = Modifier,
    letters: KeyClusterState = remember { KeyClusterState(GCodeKeymaps.letters) },
    numbers: KeyClusterState = remember { KeyClusterState(GCodeKeymaps.numbers) },
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val widthFactor = maxOf(letters.layout.widthInKeys, numbers.layout.widthInKeys)
        val heightFactor = letters.layout.heightInKeys + numbers.layout.heightInKeys + .8f
        val size = minOf(60.dp, maxWidth / widthFactor,
            if (constraints.hasBoundedHeight) ((maxHeight - 104.dp).coerceAtLeast(0.dp) / heightFactor) else 60.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KeyCluster(letters, size, onAction)
            Spacer(Modifier.height(size * .8f))
            KeyCluster(numbers, size, onAction)
        }
    }
}
