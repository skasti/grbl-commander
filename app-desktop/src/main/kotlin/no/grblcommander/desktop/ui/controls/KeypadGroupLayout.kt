package no.grblcommander.desktop.ui.controls

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

internal val keypadPageSpacing = 8.dp
internal val keypadClusterSpacing = 16.dp

internal fun Density.keypadPageHeightInPixels(): Int = ceil(44.dp.toPx()).toInt()

internal class KeypadGroupLayout(private val layouts: List<KeypadLayout>) {
    val pageControlCount = layouts.count { it.pages.size > 1 }
    val clusterGaps = layouts.size - 1

    fun widthInPixels(keyPixels: Int): Int = layouts.maxOf { it.widthInPixels(keyPixels) }

    fun compactHeightInPixels(keyPixels: Int, pageHeight: Int): Int =
        layouts.sumOf { it.heightInPixels(keyPixels) } + pageControlCount * pageHeight

    fun spacingInPixels(pageSpacing: Int, clusterSpacing: Int): Int =
        pageControlCount * pageSpacing + clusterGaps * clusterSpacing
}
