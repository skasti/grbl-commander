package no.grblcommander.desktop.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Emits intentions without taking focus from an input. Empty positions retain their geometry. */
@Composable
fun KeyCluster(
    state: KeyClusterState,
    keySize: Dp,
    onAction: (KeypadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = state.layout
    Column(modifier.width(keySize * layout.widthInKeys), horizontalAlignment = Alignment.CenterHorizontally) {
        repeat(layout.rows) { row ->
            if (row > 0) Spacer(Modifier.height(keySize * layout.spacing))
            Row {
                repeat(layout.columns) { column ->
                    if (column > 0) Spacer(Modifier.width(keySize * layout.gapBefore(column)))
                    val slot = row * layout.columns + column
                    val definition = state.keys[slot]
                    // A page change cancels a pointer gesture started on the preceding page.
                    key(state.pageIndex, definition) {
                        if (definition == null) {
                            Spacer(Modifier.size(keySize))
                        } else {
                            val shape = RoundedCornerShape(6.dp)
                            Box(
                                Modifier.size(keySize).testTag("keypad-${layout.name}-$slot")
                                    .clip(shape).background(MaterialTheme.colorScheme.secondaryContainer)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                                    .focusProperties { canFocus = false }
                                    .clickable(role = Role.Button) { onAction(definition.action) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(definition.label, color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontSize = (keySize.value * if (definition.label.length > 2) .23f else .38f).coerceIn(8f, 22f).sp,
                                    maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(44.dp).testTag("keypad-${layout.name}-next")
                .focusProperties { canFocus = false }
                .clickable(enabled = layout.pages.size > 1, role = Role.Button) { state.next() },
            contentAlignment = Alignment.Center,
        ) {
            Text("${layout.name} ${state.pageIndex + 1}/${layout.pages.size}" + if (layout.pages.size > 1) " ▸" else "",
                style = MaterialTheme.typography.labelLarge)
        }
    }
}
