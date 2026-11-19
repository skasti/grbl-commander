package no.grblcommander.desktop.ui.layout

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

/** Standard key width and height in the window, excluding gaps. */
val LocalKeyUnit = compositionLocalOf { 44.dp }

internal fun Density.windowKeyUnit(width: Dp, height: Dp): Dp {
    val scale = minOf(width / 1024.dp, height / 720.dp)
    val minimum = ceil(44.dp.toPx()).toInt()
    val maximum = floor(72.dp.toPx()).toInt()
    return (44.dp * scale.coerceIn(1f, 72f / 44f)).roundToPx()
        .coerceIn(minimum, maximum).toDp()
}
