package no.grblcommander.desktop.ui.controls

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Emits intentions without taking focus from an input. Empty positions retain their geometry. */
@Composable
fun KeyCluster(
    state: KeyClusterState,
    keySize: Dp,
    onAction: (KeypadAction) -> Unit,
    modifier: Modifier = Modifier,
    pageSpacing: Dp = 8.dp,
) {
    val layout = state.layout
    val density = LocalDensity.current
    val keyPixels = with(density) { keySize.roundToPx() }
    val width = with(density) { layout.widthInPixels(keyPixels).toDp() }
    val rowSpacing = with(density) { (keyPixels * layout.spacing).roundToInt().toDp() }
    val pageHeight = with(density) { ceil(44.dp.toPx()).toInt().toDp() }
    Column(modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        repeat(layout.rows) { row ->
            if (row > 0) Spacer(Modifier.height(rowSpacing))
            Row {
                repeat(layout.columns) { column ->
                    if (column > 0) Spacer(Modifier.width(with(density) { (keyPixels * layout.gapBefore(column)).roundToInt().toDp() }))
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
                                    // Attach indication/interaction handling before the first pointer event.
                                    // Lazy setup can lose a press that arrives without a prior hover.
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = LocalIndication.current,
                                        role = Role.Button,
                                    ) { onAction(definition.action) },
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
        Spacer(Modifier.height(pageSpacing))
        Box(
            Modifier.fillMaxWidth().height(pageHeight).testTag("keypad-${layout.name}-next")
                .focusProperties { canFocus = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    enabled = layout.pages.size > 1,
                    role = Role.Button,
                ) { state.next() },
            contentAlignment = Alignment.Center,
        ) {
            Text("${layout.name} ${state.pageIndex + 1}/${layout.pages.size}" + if (layout.pages.size > 1) " ▸" else "",
                style = MaterialTheme.typography.labelLarge)
        }
    }
}
