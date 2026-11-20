package no.grblcommander.desktop.ui.components.controls

import kotlin.math.roundToInt

/** Immutable configuration snapshot. Missing slots stay empty on every page. */
class KeypadLayout(
    val name: String,
    val rows: Int = 4,
    val columns: Int = 4,
    pages: List<Map<Int, KeypadKey>>,
    val spacing: Float = .30f,
    gapsBeforeColumns: Map<Int, Float> = emptyMap(),
) {
    val pages = pages.map { it.toMap() }
    /** Total gap before a column, in key widths (overrides ordinary spacing). */
    val gapsBeforeColumns = gapsBeforeColumns.toMap()

    init {
        require(rows in 1..32 && columns in 1..32)
        require(this.pages.isNotEmpty())
        require(spacing.isFinite() && spacing >= 0f)
        require(this.gapsBeforeColumns.all { (column, gap) -> column in 1 until columns && gap.isFinite() && gap >= 0f })
        require(this.pages.all { page -> page.keys.all { it in 0 until rows * columns } })
        for (page in this.pages) {
            for ((slot, key) in page) {
                require(key.columnSpan in 1..(columns - slot % columns)) { "Key span crosses a row boundary" }
                require((slot + 1 until slot + key.columnSpan).none { it in page }) { "Key span overlaps another key" }
            }
        }
    }

    // Compose rounds every key and spacer separately during measurement.
    internal fun widthInPixels(keyPixels: Int): Int = spanWidthInPixels(keyPixels, 0, columns)

    internal fun spanWidthInPixels(keyPixels: Int, column: Int, span: Int): Int =
        span * keyPixels + (column + 1 until column + span).sumOf { (keyPixels * gapBefore(it)).roundToInt() }
    internal fun heightInPixels(keyPixels: Int): Int = rows * keyPixels +
        (rows - 1) * (keyPixels * spacing).roundToInt()

    fun gapBefore(column: Int): Float = if (column == 0) 0f else gapsBeforeColumns[column] ?: spacing
    val widthInKeys: Float get() = columns + (1 until columns).sumOf { gapBefore(it).toDouble() }.toFloat()
    val heightInKeys: Float get() = rows + (rows - 1) * spacing
}
