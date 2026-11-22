package no.grblcommander.desktop.ui.layout

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal const val standardKeyGapRatio = .30f

private val minimumGridUnit = 44.dp
private val maximumGridUnit = 72.dp

/** One base length for proportional workspace layouts, excluding gaps. */
val LocalGridUnit = compositionLocalOf { minimumGridUnit }

internal data class LayoutBudget(val widthFraction: Float, val heightFraction: Float) {
    fun within(width: Int, height: Int): IntSize =
        IntSize((width * widthFraction).toInt(), (height * heightFraction).toInt())
}

private data class GridExtent(val units: Int, val gapCount: Int, val gapInUnits: Float) {
    fun inPixels(unitPixels: Int): Int = units * unitPixels + gapCount * (unitPixels * gapInUnits).roundToInt()
}

private val gridBudget = LayoutBudget(widthFraction = 0.65f, heightFraction = 0.75f)

// Calibration from the standard QWERTY + NUM+ footprint, documented in docs/layout-grid-unit.md.
private val referenceWidth = GridExtent(units = 10, gapCount = 9, gapInUnits = standardKeyGapRatio)
private val referenceHeight = GridExtent(units = 7, gapCount = 5, gapInUnits = standardKeyGapRatio)

internal fun Density.workspaceGridUnit(workspaceWidth: Int, workspaceHeight: Int): Dp {
    val budget = gridBudget.within(workspaceWidth, workspaceHeight)
    // Two 44 dp touch targets, two 8 dp spaces and one 16 dp space, each rounded separately.
    val fixedHeight = 2 * ceil(44.dp.toPx()).toInt() + 2 * 8.dp.roundToPx() + 16.dp.roundToPx()
    val minimum = ceil(minimumGridUnit.toPx()).toInt()
    val maximum = floor(maximumGridUnit.toPx()).toInt()
    val fitting = (maximum downTo minimum).firstOrNull { unitPixels ->
        referenceWidth.inPixels(unitPixels) <= budget.width &&
            referenceHeight.inPixels(unitPixels) + fixedHeight <= budget.height
    }
    return (fitting ?: minimum).toDp()
}
