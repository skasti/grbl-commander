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
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val widthFactor = letters.layout.widthInKeys + numbers.layout.widthInKeys + .8f
        val heightFactor = maxOf(letters.layout.heightInKeys, numbers.layout.heightInKeys)
        val size = minOf(60.dp, maxWidth / widthFactor,
            if (constraints.hasBoundedHeight) ((maxHeight - 52.dp).coerceAtLeast(0.dp) / heightFactor) else 60.dp)
        Row(verticalAlignment = Alignment.Top) {
            KeyCluster(letters, size, onAction)
            Spacer(Modifier.width(size * .8f))
            KeyCluster(numbers, size, onAction)
        }
    }
}
