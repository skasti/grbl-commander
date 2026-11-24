package no.grblcommander.desktop.ui.components.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import no.grblcommander.desktop.ui.layout.LocalGridUnit
import no.grblcommander.desktop.ui.layout.ShellColors
import no.grblcommander.desktop.ui.layout.softkeyFace

data class DigitalReadoutState(
    val axes: List<ReadoutAxis>,
    val availability: ReadoutAvailability,
    val availableWorkCoordinateSystems: List<String> = emptyList(),
    val activeWorkCoordinateSystem: String? = null,
    val inchCoordinates: Boolean = false,
)

data class ReadoutAxis(val label: String, val work: Double?, val machine: Double?)

enum class ReadoutAvailability {
    Live, Stale, Disconnected, Unavailable,
}

private const val missingReading = "—"

/** Presents caller-supplied observations in a panel sized and placed by its host. */
@Composable
fun DigitalReadout(state: DigitalReadoutState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val showReadings = state.availability == ReadoutAvailability.Live ||
        state.availability == ReadoutAvailability.Stale
    val workColor = if (state.availability == ReadoutAvailability.Live) ShellColors.text else ShellColors.muted
    val decimals = if (state.inchCoordinates) 4 else 3
    BoxWithConstraints(
        modifier.background(ShellColors.frame, shape)
            .border(1.dp, ShellColors.outline, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        val panelWidth = if (constraints.hasBoundedWidth) maxWidth else 0.dp
        val columns = if (panelWidth >= 450.dp) 2 else 1
        val spacious = panelWidth >= 600.dp
        val rowHeight = if (spacious) 54.dp else 43.dp
        val workSize = when {
            spacious -> 28.sp
            state.inchCoordinates -> 18.sp
            else -> 20.sp
        }
        val machineSize = if (spacious) 18.sp else 13.sp
        Column(Modifier.fillMaxWidth()) {
            if (state.availableWorkCoordinateSystems.isNotEmpty()) {
                CoordinateSystems(
                    state.availableWorkCoordinateSystems,
                    state.activeWorkCoordinateSystem.takeIf { showReadings },
                    panelWidth,
                )
                Spacer(Modifier.height(8.dp))
            }
            ReadoutStatus(state.availability)
            if (state.axes.isEmpty()) {
                Text(
                    "No axes available",
                    color = ShellColors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    state.axes.chunked(columns).forEach { row ->
                        HorizontalDivider(color = ShellColors.outline)
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                            row.forEachIndexed { index, axis ->
                                if (index > 0) {
                                    VerticalDivider(Modifier.fillMaxHeight(), color = ShellColors.outline)
                                }
                                AxisCoordinates(
                                    axis,
                                    work = formatReading(axis.work.takeIf { showReadings }, decimals),
                                    machine = "(${formatReading(axis.machine.takeIf { showReadings }, decimals)})",
                                    workColor, workSize, machineSize,
                                    Modifier.weight(1f).heightIn(min = rowHeight),
                                )
                            }
                            if (row.size < columns) {
                                VerticalDivider(Modifier.fillMaxHeight(), color = ShellColors.outline)
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoordinateSystems(systems: List<String>, activeSystem: String?, width: Dp) {
    val size = LocalGridUnit.current
    val gap = ((width - size * systems.size) / (systems.size - 1).coerceAtLeast(1))
        .coerceIn(2.dp, size * .8f)
    Row(
        Modifier.horizontalScroll(rememberScrollState()).widthIn(min = width),
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
    ) {
        systems.forEach { system ->
            val active = system == activeSystem
            Box(
                Modifier.size(size)
                    .softkeyFace(active)
                    .semantics(mergeDescendants = true) { selected = active },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    system,
                    color = if (active) ShellColors.text else ShellColors.muted,
                    fontSize = (size.value * .35f).sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReadoutStatus(availability: ReadoutAvailability) {
    val (label, color) = when (availability) {
        ReadoutAvailability.Live -> return
        ReadoutAvailability.Stale -> "Stale" to MaterialTheme.colorScheme.tertiary
        ReadoutAvailability.Disconnected -> "Disconnected" to MaterialTheme.colorScheme.error
        ReadoutAvailability.Unavailable -> "Unavailable" to ShellColors.muted
    }
    Text(
        label,
        Modifier.padding(bottom = 4.dp),
        color = color,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun AxisCoordinates(
    axis: ReadoutAxis,
    work: String,
    machine: String,
    workColor: Color,
    workSize: TextUnit,
    machineSize: TextUnit,
    modifier: Modifier,
) {
    Row(
        modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.defaultMinSize(minWidth = 27.dp, minHeight = 27.dp)
                .background(ShellColors.key, RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(axis.label, color = ShellColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        CoordinateText(work, workColor, workSize, Modifier.weight(1.25f), FontWeight.Medium)
        CoordinateText(machine, ShellColors.muted, machineSize, Modifier.weight(1f), FontWeight.Normal)
    }
}

@Composable
private fun CoordinateText(text: String, color: Color, size: TextUnit, modifier: Modifier, weight: FontWeight) {
    Text(
        text,
        modifier,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = weight,
        fontSize = size,
        letterSpacing = 0.sp,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun formatReading(value: Double?, decimals: Int): String =
    value?.takeIf { it.isFinite() }
        ?.let { BigDecimal.valueOf(it).setScale(decimals, RoundingMode.HALF_UP).toPlainString() }
        ?: missingReading
