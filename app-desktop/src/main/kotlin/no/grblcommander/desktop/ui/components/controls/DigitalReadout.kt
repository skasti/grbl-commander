package no.grblcommander.desktop.ui.components.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import no.grblcommander.desktop.ui.layout.ShellColors

data class DigitalReadoutState(
    val axes: List<ReadoutAxis>,
    val availability: ReadoutAvailability,
    val workCoordinateSystem: String? = null,
    val units: ReadoutUnit? = null,
    val feedRate: Double? = null,
    val spindleRpm: Int? = null,
    val feedOverridePercent: Int? = null,
    val rapidOverridePercent: Int? = null,
    val spindleOverridePercent: Int? = null,
)

data class ReadoutAxis(
    val label: String,
    val work: Double?,
    val machine: Double?,
    val unit: ReadoutUnit? = null,
)

enum class ReadoutAvailability(internal val label: String) {
    Live("Live"),
    Stale("Stale"),
    Disconnected("Disconnected"),
    Unavailable("Unavailable"),
}

enum class ReadoutUnit(internal val symbol: String) {
    Millimetres("mm"),
    Inches("in"),
    Degrees("°"),
}

private const val missingReading = "—"

/** Presents observations supplied by the caller. The modifier controls panel placement and size. */
@Composable
fun DigitalReadout(state: DigitalReadoutState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val availabilityColor = when (state.availability) {
        ReadoutAvailability.Live -> ShellColors.accent
        ReadoutAvailability.Stale -> MaterialTheme.colorScheme.tertiary
        ReadoutAvailability.Disconnected -> MaterialTheme.colorScheme.error
        ReadoutAvailability.Unavailable -> ShellColors.muted
    }
    val showReadings = state.availability == ReadoutAvailability.Live ||
        state.availability == ReadoutAvailability.Stale
    val hasInchCoordinates = state.units == ReadoutUnit.Inches || state.axes.any { it.unit == ReadoutUnit.Inches }
    val coordinateDecimals = if (hasInchCoordinates) 4 else 3
    val workColor = if (state.availability == ReadoutAvailability.Live) ShellColors.text else ShellColors.muted
    BoxWithConstraints(
        modifier.background(ShellColors.frame, shape).border(1.dp, ShellColors.outline, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        val spacious = maxWidth >= 360.dp && (!constraints.hasBoundedHeight || maxHeight >= 245.dp)
        val rowHeight = if (spacious) 28.dp else 19.dp
        val workSize = if (spacious) 22.sp else 15.sp
        val machineSize = if (spacious) 16.sp else 13.sp
        Column(verticalArrangement = Arrangement.spacedBy(if (spacious) 3.dp else 1.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("DRO", color = ShellColors.text, style = MaterialTheme.typography.labelLarge)
                Text(
                    "WCS ${state.workCoordinateSystem.takeIf { showReadings } ?: missingReading}",
                    modifier = Modifier.background(ShellColors.key, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    color = if (state.availability == ReadoutAvailability.Live) ShellColors.accent else workColor,
                    style = MaterialTheme.typography.labelLarge,
                )
                ReadoutLabel("Units ${state.units?.symbol ?: missingReading}")
                Text(state.availability.label, color = availabilityColor, style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider(color = ShellColors.outline)
            ReadoutColumns {
                ReadoutLabel("Axis", Modifier.weight(1f))
                ReadoutLabel("Work", Modifier.weight(2f), TextAlign.End)
                ReadoutLabel("Machine", Modifier.weight(2f), TextAlign.End)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (state.axes.isEmpty()) ReadoutLabel("No axes available")
                state.axes.forEach { axis ->
                    val axisLabel = if (axis.unit != null && axis.unit != state.units) {
                        "${axis.label} ${axis.unit.symbol}"
                    } else {
                        axis.label
                    }
                    ReadoutColumns(Modifier.heightIn(min = rowHeight)) {
                        ReadoutLabel(axisLabel, Modifier.weight(1f))
                        CoordinateText(
                            formatReading(axis.work.takeIf { showReadings }, coordinateDecimals),
                            workColor, workSize, Modifier.weight(2f),
                        )
                        CoordinateText(
                            formatReading(axis.machine.takeIf { showReadings }, coordinateDecimals),
                            ShellColors.muted, machineSize, Modifier.weight(2f),
                        )
                    }
                }
            }
            HorizontalDivider(color = ShellColors.outline)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val feed = formatReading(state.feedRate.takeIf { showReadings }, 1)
                val feedUnit = state.units?.let { "${it.symbol}/min" }
                val feedReading = if (feedUnit == null || feed == missingReading) feed else "$feed $feedUnit"
                val spindle = state.spindleRpm.takeIf { showReadings }?.let { "$it rpm" } ?: missingReading
                ReadoutLabel("Feed $feedReading")
                ReadoutLabel("Spindle $spindle")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ReadoutLabel("Overrides")
                ReadoutLabel("Feed ${formatOverride(state.feedOverridePercent.takeIf { showReadings })}")
                ReadoutLabel("Rapid ${formatOverride(state.rapidOverridePercent.takeIf { showReadings })}")
                ReadoutLabel("Spindle ${formatOverride(state.spindleOverridePercent.takeIf { showReadings })}")
            }
        }
    }
}

@Composable
private fun CoordinateText(text: String, color: Color, size: TextUnit, modifier: Modifier) {
    Text(
        text,
        modifier,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = size,
        lineHeight = size,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ReadoutColumns(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun ReadoutLabel(text: String, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Start) {
    Text(
        text,
        modifier,
        color = ShellColors.muted,
        style = MaterialTheme.typography.labelMedium,
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun formatReading(value: Double?, decimals: Int): String =
    value?.takeIf { it.isFinite() }
        ?.let { BigDecimal.valueOf(it).setScale(decimals, RoundingMode.HALF_UP).toPlainString() }
        ?: missingReading

private fun formatOverride(percent: Int?): String = percent?.let { "$it%" } ?: missingReading
