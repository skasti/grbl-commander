package no.grblcommander.desktop.ui.components.controls

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import no.grblcommander.desktop.ui.components.controls.editor.GCodeEditor
import no.grblcommander.desktop.ui.components.controls.editor.Render
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.LocalGridUnit
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.runAtScreenshotSizes

@OptIn(ExperimentalTestApi::class)
class DigitalReadoutTest {
    @Test fun `live readout fits six axes and telemetry in the MDI footprint`() = runAtScreenshotSizes { screenshot ->
        setContent { ReadoutFixture(sampleReadout) }
        val workspace = onNodeWithTag("readout-workspace").fetchSemanticsNode().boundsInRoot
        val panel = onNodeWithTag("readout-panel").fetchSemanticsNode().boundsInRoot
        assertEquals(workspace.height * 0.40f, panel.height, absoluteTolerance = 1f)
        assertEquals(panel.height * (4f / 3f), panel.width, absoluteTolerance = 1.5f)
        listOf(
            "DRO", "Live", "WCS G54", "Units mm", "Axis", "Work", "Machine",
            "X", "Y", "Z", "A °", "B °", "C °",
            "20.125", "120.125", "-15.000", "85.000", "5.125", "-42.000",
            "270.000", "79.050", "0.000", "42.500", "-180.000", "-225.000",
            "Feed 480.5 mm/min", "Spindle 12000 rpm", "Overrides",
            "Feed 95%", "Rapid 50%", "Spindle 110%",
        ).forEach { assertVisibleText(it) }
        assertTrue(onNodeWithText("C °").fetchSemanticsNode().boundsInRoot.bottom <
            onNodeWithText("Feed 480.5 mm/min").fetchSemanticsNode().boundsInRoot.top)
        assertEquals(
            onNodeWithText("20.125").fetchSemanticsNode().boundsInRoot.right,
            onNodeWithText("-15.000").fetchSemanticsNode().boundsInRoot.right,
        )
        screenshot("dro-live")
    }

    @Test fun `stale readings are marked and gaps never become zero`() = runAtScreenshotSizes { screenshot ->
        val stale = sampleReadout.copy(
            availability = ReadoutAvailability.Stale,
            units = ReadoutUnit.Inches,
            workCoordinateSystem = "G59",
            axes = listOf(
                ReadoutAxis("X", -0.00001, 1.23456),
                ReadoutAxis("Y", null, -0.5),
                ReadoutAxis("Z", 2.0, Double.NaN),
            ),
            feedRate = 12.5,
            spindleRpm = null,
            rapidOverridePercent = null,
        )
        setContent { ReadoutFixture(stale) }
        listOf("Stale", "WCS G59", "Units in", "0.0000", "1.2346", "-0.5000", "2.0000",
            "Feed 12.5 in/min", "Spindle —", "Rapid —").forEach { assertVisibleText(it) }
        onAllNodesWithText("—").assertCountEquals(2)
        screenshot("dro-stale")
    }

    @Test fun `disconnected and unavailable states hide earlier readings`() = runAtScreenshotSizes { screenshot ->
        val state = mutableStateOf(sampleReadout.copy(availability = ReadoutAvailability.Disconnected))
        setContent { ReadoutFixture(state.value) }
        listOf("Disconnected", "WCS —", "Units mm", "X", "C °", "Feed —", "Rapid —").forEach {
            // Both the feed reading and its override use a placeholder.
            onAllNodes(readoutText(it)).onFirst().assertIsDisplayed()
        }
        onAllNodesWithText("—").assertCountEquals(12)
        onNodeWithText("20.125").assertDoesNotExist()
        onNodeWithText("Spindle 12000 rpm").assertDoesNotExist()
        screenshot("dro-disconnected")

        runOnIdle {
            state.value = DigitalReadoutState(emptyList(), ReadoutAvailability.Unavailable)
        }
        listOf("Unavailable", "Units —", "No axes available").forEach { assertVisibleText(it) }
        onNodeWithText("Disconnected").assertDoesNotExist()
        onNode(readoutText("X")).assertDoesNotExist()
        screenshot("dro-unavailable")
    }

    @Test fun `axes scroll independently in a shorter host`() = runAtScreenshotSizes(capture = false) { _ ->
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DigitalReadout(sampleReadout, Modifier.size(300.dp, 160.dp).testTag("readout-panel"))
            }
        }
        onNodeWithText("C °").assertIsNotDisplayed().performScrollTo().assertIsDisplayed()
        assertVisibleText("Work")
        assertVisibleText("Feed 480.5 mm/min")
    }

    private fun readoutText(value: String) = hasText(value) and hasAnyAncestor(hasTestTag("readout-panel"))

    private fun ComposeUiTest.assertVisibleText(value: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = onNode(readoutText(value)).assertIsDisplayed()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue(!layout.isLineEllipsized(0), "$value is ellipsized")
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertEquals(layout.size.height.toFloat(), bounds.height, absoluteTolerance = 1f, message = value)
    }
}

private val sampleReadout = DigitalReadoutState(
    axes = listOf(
        ReadoutAxis("X", 20.125, 120.125),
        ReadoutAxis("Y", -15.0, 85.0),
        ReadoutAxis("Z", 5.125, -42.0),
        ReadoutAxis("A", 270.0, 79.05, ReadoutUnit.Degrees),
        ReadoutAxis("B", 0.0, 42.5, ReadoutUnit.Degrees),
        ReadoutAxis("C", -180.0, -225.0, ReadoutUnit.Degrees),
    ),
    availability = ReadoutAvailability.Live,
    workCoordinateSystem = "G54",
    units = ReadoutUnit.Millimetres,
    feedRate = 480.5,
    spindleRpm = 12000,
    feedOverridePercent = 95,
    rapidOverridePercent = 50,
    spindleOverridePercent = 110,
)

@Composable
private fun ReadoutFixture(state: DigitalReadoutState) {
    val editor = remember { GCodeEditor(listOf("G90", "G0 X0 Y0", "G1 X20 F480", "G1 Y15", "M2")) }
    val stack = remember {
        ScreenStack(object : Screen<Unit>(Unit) {
            override val bottomKeys = editor.bottomKeys
            override val rightKeys = editor.rightKeys

            @Composable override fun Content() = Unit
        })
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        AppShell(ShellState("MDI", "", ""), { stack }) {
            Surface(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize().testTag("readout-workspace")) {
                    val panelHeight = maxHeight * 0.40f
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().padding(end = 16.dp)) {
                            Text("MDI buffer", style = MaterialTheme.typography.titleMedium)
                            editor.Render(Modifier.weight(1f).fillMaxWidth())
                        }
                        Column(
                            Modifier.fillMaxHeight(),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            DigitalReadout(
                                state,
                                Modifier.size(panelHeight * (4f / 3f), panelHeight).testTag("readout-panel"),
                            )
                            val letters = remember { KeyClusterState(Keymaps.code) }
                            val numbers = remember { KeyClusterState(Keymaps.numPlus) }
                            Row(horizontalArrangement = Arrangement.spacedBy(LocalGridUnit.current)) {
                                KeyCluster(letters, editor::onKeypadAction)
                                KeyCluster(numbers, editor::onKeypadAction)
                            }
                        }
                    }
                }
            }
        }
    }
}
