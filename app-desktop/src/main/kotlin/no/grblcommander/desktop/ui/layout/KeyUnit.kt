package no.grblcommander.desktop.ui.layout

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.controls.GCodeKeymaps
import no.grblcommander.desktop.ui.controls.KeypadGroupLayout
import no.grblcommander.desktop.ui.controls.keypadClusterSpacing
import no.grblcommander.desktop.ui.controls.keypadPageHeightInPixels
import no.grblcommander.desktop.ui.controls.keypadPageSpacing
import kotlin.math.ceil
import kotlin.math.floor

/** Standard key width and height in the window, excluding gaps. */
val LocalKeyUnit = compositionLocalOf { 44.dp }

internal data class LayoutBudget(val widthFraction: Float, val heightFraction: Float) {
    fun within(width: Int, height: Int): IntSize =
        IntSize((width * widthFraction).toInt(), (height * heightFraction).toInt())
}

private val keyboardBudget = LayoutBudget(widthFraction = 0.65f, heightFraction = 0.75f)
private val referenceKeyboard = KeypadGroupLayout(listOf(GCodeKeymaps.qwerty, GCodeKeymaps.numbers))

internal fun Density.workspaceKeyUnit(workspaceWidth: Int, workspaceHeight: Int): Dp {
    val budget = keyboardBudget.within(workspaceWidth, workspaceHeight)
    val pageHeight = keypadPageHeightInPixels()
    val groupSpacing = referenceKeyboard.spacingInPixels(keypadPageSpacing.roundToPx(), keypadClusterSpacing.roundToPx())
    val minimum = ceil(44.dp.toPx()).toInt()
    val maximum = floor(72.dp.toPx()).toInt()
    val fitting = (maximum downTo minimum).firstOrNull { keyPixels ->
        referenceKeyboard.widthInPixels(keyPixels) <= budget.width &&
            referenceKeyboard.compactHeightInPixels(keyPixels, pageHeight) + groupSpacing <= budget.height
    }
    return (fitting ?: minimum).toDp()
}
