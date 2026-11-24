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
import androidx.compose.ui.platform.LocalDensity
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
    @Test fun `live readout fits square WCS tiles and six axes above both keypads`() = runAtScreenshotSizes { screenshot ->
        setContent { ReadoutFixture(sampleReadout) }
        val panel = onNodeWithTag("readout-panel").fetchSemanticsNode().boundsInRoot
        val code = onNodeWithTag("keypad-CODE-0").fetchSemanticsNode().boundsInRoot
        val number = onNodeWithTag("keypad-NUM+-3").fetchSemanticsNode().boundsInRoot
        assertEquals(code.left, panel.left, absoluteTolerance = 1f)
        assertEquals(number.right, panel.right, absoluteTolerance = 1f)
        assertTrue(panel.bottom < code.top)

        val tiles = sampleReadout.availableWorkCoordinateSystems.map { system ->
            onNode(readoutText(system)).assertIsDisplayed().assertHasNoClickAction().apply {
                if (system == "G54") assertIsSelected() else assertIsNotSelected()
            }.fetchSemanticsNode().boundsInRoot
        }
        tiles.forEach {
            assertEquals(code.width, it.width)
            assertEquals(it.width, it.height)
            assertEquals(tiles.first().top, it.top)
        }
        assertEquals(tiles.size, tiles.distinctBy { it.left }.size)
        listOf(
            "X", "Y", "Z", "A", "B", "C",
            "20.125", "(120.125)", "-15.000", "(85.000)", "5.125", "(-42.000)",
            "270.000", "(79.050)", "0.000", "(42.500)", "-180.000", "(-225.000)",
        ).forEach { assertVisibleText(it) }
        listOf("DRO", "Live", "Units mm", "Overrides").forEach { onNode(readoutText(it)).assertDoesNotExist() }
        val axes = listOf("X", "Y", "Z", "A", "B", "C").map { axis ->
            onNode(readoutText(axis)).fetchSemanticsNode().boundsInRoot
        }
        axes.chunked(2).forEach { (left, right) ->
            assertEquals(left.top, right.top)
            assertTrue(left.left < right.left)
        }
        assertTrue(axes[0].top < axes[2].top && axes[2].top < axes[4].top)
        assertEquals(
            onNode(readoutText("20.125")).fetchSemanticsNode().boundsInRoot.right,
            onNode(readoutText("5.125")).fetchSemanticsNode().boundsInRoot.right,
        )
        screenshot("dro-live")
    }

    @Test fun `stale readings are marked and gaps never become zero`() = runAtScreenshotSizes { screenshot ->
        val stale = sampleReadout.copy(
            availability = ReadoutAvailability.Stale,
            inchCoordinates = true,
            activeWorkCoordinateSystem = "G59",
            axes = listOf(
                ReadoutAxis("X", -0.00001, 1.23456),
                ReadoutAxis("Y", null, -0.5),
                ReadoutAxis("Z", 2.0, Double.NaN),
            ),
        )
        setContent { ReadoutFixture(stale) }
        onNode(readoutText("G59")).assertIsSelected()
        onNode(readoutText("G54")).assertIsNotSelected()
        listOf("Stale", "0.0000", "(1.2346)", "(-0.5000)", "2.0000", "—", "(—)")
            .forEach { assertVisibleText(it) }
        screenshot("dro-stale")
    }

    @Test fun `disconnected and unavailable states hide earlier readings`() = runAtScreenshotSizes { screenshot ->
        val state = mutableStateOf(sampleReadout.copy(availability = ReadoutAvailability.Disconnected))
        setContent { ReadoutFixture(state.value) }
        listOf("Disconnected", "X", "C").forEach { assertVisibleText(it) }
        onNode(readoutText("G54")).assertIsNotSelected()
        onAllNodes(readoutText("—")).assertCountEquals(6)
        onAllNodes(readoutText("(—)")).assertCountEquals(6)
        onNode(readoutText("20.125")).assertDoesNotExist()
        screenshot("dro-disconnected")

        runOnIdle {
            state.value = DigitalReadoutState(emptyList(), ReadoutAvailability.Unavailable)
        }
        listOf("Unavailable", "No axes available").forEach { assertVisibleText(it) }
        listOf("Disconnected", "G54", "X").forEach { onNode(readoutText(it)).assertDoesNotExist() }
        screenshot("dro-unavailable")
    }

    @Test fun `axes stack and scroll independently in a narrower host`() = runAtScreenshotSizes(capture = false) { _ ->
        val state = sampleReadout.copy(availableWorkCoordinateSystems = listOf("G55", "G58"), activeWorkCoordinateSystem = "G58")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DigitalReadout(state, Modifier.size(297.dp, 223.dp).testTag("readout-panel"))
            }
        }
        onNode(readoutText("G54")).assertDoesNotExist()
        onNode(readoutText("G58")).assertIsSelected().assertIsDisplayed()
        val x = onNode(readoutText("X")).fetchSemanticsNode().boundsInRoot
        val y = onNode(readoutText("Y")).fetchSemanticsNode().boundsInRoot
        assertEquals(x.left, y.left)
        assertTrue(x.bottom < y.top)
        onNode(readoutText("C")).assertIsNotDisplayed().performScrollTo().assertIsDisplayed()
        onNode(readoutText("G58")).assertIsDisplayed()
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
        ReadoutAxis("A", 270.0, 79.05),
        ReadoutAxis("B", 0.0, 42.5),
        ReadoutAxis("C", -180.0, -225.0),
    ),
    availability = ReadoutAvailability.Live,
    availableWorkCoordinateSystems = listOf("G54", "G55", "G56", "G57", "G58", "G59"),
    activeWorkCoordinateSystem = "G54",
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
                val gridUnit = LocalGridUnit.current
                val letters = remember { KeyClusterState(Keymaps.code) }
                val numbers = remember { KeyClusterState(Keymaps.numPlus) }
                val keypadWidth = with(LocalDensity.current) {
                    val keyPixels = gridUnit.roundToPx()
                    (letters.layout.widthInPixels(keyPixels) + numbers.layout.widthInPixels(keyPixels) + keyPixels).toDp()
                }
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text("MDI buffer", style = MaterialTheme.typography.titleMedium)
                        editor.Render(Modifier.weight(1f).fillMaxWidth())
                    }
                    Column(
                        Modifier.width(keypadWidth).fillMaxHeight(),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        DigitalReadout(
                            state,
                            Modifier.weight(1f, fill = false).padding(bottom = 12.dp)
                                .fillMaxWidth().testTag("readout-panel"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(gridUnit)) {
                            KeyCluster(letters, editor::onKeypadAction)
                            KeyCluster(numbers, editor::onKeypadAction)
                        }
                    }
                }
            }
        }
    }
}
