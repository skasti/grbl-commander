package no.grblcommander.desktop.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import kotlinx.coroutines.*
import no.grblcommander.configuration.ControllerEndpointPreferences
import no.grblcommander.core.connection.ControllerConnection
import no.grblcommander.core.connection.ControllerSettings
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.*
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*
import kotlin.math.ceil
import kotlin.math.roundToInt

@OptIn(ExperimentalTestApi::class)
class KeyboardLayoutTest {
    @Test fun `terminal and MDI keyboard stay inside workspace at 1024 by 768`() = verifyLayout(1024f, 768f)
    @Test fun `terminal and MDI keyboard stay inside workspace at 1024 by 720`() = verifyLayout(1024f, 720f)
    @Test fun `terminal and MDI keyboard stay inside workspace at 1280 by 720`() = verifyLayout(1280f, 720f)
    @Test fun `terminal and MDI keyboard stay inside workspace at 1680 by 1050`() = verifyLayout(1680f, 1050f)
    @Test fun `terminal and MDI keyboard stay inside workspace at 1920 by 1080`() = verifyLayout(1920f, 1080f)
    @Test fun `terminal and MDI keyboard stay inside workspace at 2560 by 1440`() = verifyLayout(2560f, 1440f)

    @Test fun `minimum logical viewport preserves 44 dp keys at fractional display density`() = verifyLayout(1024f, 720f, 1.25f)

    @Test fun `minimum logical viewport preserves minimum targets at 110 percent density`() = verifyLayout(1024f, 720f, 1.1f)

    private fun verifyLayout(width: Float, height: Float, scale: Float = 1f) = runSkikoComposeUiTest(
        size = Size(width * scale, height * scale), density = androidx.compose.ui.unit.Density(scale),
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = ControllerConnection(scope)
        val node = Preferences.userRoot().node("no/grblcommander/tests/${UUID.randomUUID()}")
        try {
            val terminal = ScreenStack(Terminal(ControllerSettings(owner, ControllerEndpointPreferences(node))))
            val mdi = ScreenStack(MdiMode())
            var active by androidx.compose.runtime.mutableStateOf(terminal)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) { AppShell(ShellState.startup(), { active }) { ScreenStackHost(listOf(terminal, mdi), active) } }
            }
            fun SemanticsNodeInteraction.bounds(): androidx.compose.ui.geometry.Rect {
                val rect = fetchSemanticsNode().boundsInRoot
                return androidx.compose.ui.geometry.Rect(rect.left / scale, rect.top / scale, rect.right / scale, rect.bottom / scale)
            }
            fun check(input: SemanticsNodeInteraction) {
                val bottom = onNodeWithTag("softkey-F1").bounds()
                val right = onNodeWithTag("softkey-R1").bounds()
                val field = input.bounds()
                val lettersFooter = onNodeWithTag("keypad-ABC-next").bounds()
                val footer = onNodeWithTag("keypad-123-next").bounds()
                val first = onNodeWithTag("keypad-ABC-0").bounds()
                val numbersFirst = onNodeWithTag("keypad-123-0").bounds()
                assertTrue(field.height >= 47.99f, "Input remains a usable touch target: $field")
                assertTrue(first.left >= field.right, "Keyboard must stay to the right of the input")
                assertTrue(numbersFirst.top >= lettersFooter.bottom, "Numbers must be below letters")
                val minimumKey = ceil(44f * scale).toInt()
                val minimumGrid = 4 * minimumKey + 3 * (.30f * minimumKey).roundToInt()
                val minimumGroup = (2 * minimumGrid + 2 * minimumKey) / scale
                val margin = ((height - 188f - minimumGroup) / 2).coerceIn(0f, 30f)
                // The screen and bottom tile each contribute an additional 8 dp.
                assertEquals(16f + margin, bottom.top - footer.bottom, 2f, "Combined panel keeps its adaptive bottom margin")
                if (height >= 1050f) {
                    assertEquals(72f, first.width, .1f, "Larger viewports reach the capped size")
                    assertTrue(first.top - 78f > 8f, "Excess height stays above the combined panel")
                    assertTrue(numbersFirst.top - lettersFooter.bottom <= 16.1f, "Excess height must not separate clusters")
                }
                for (cluster in listOf("ABC", "123")) {
                    for (slot in 0 until 16) {
                        val bounds = onNodeWithTag("keypad-$cluster-$slot").assertHasClickAction().bounds()
                        assertTrue(bounds.width in 43.99f..72.01f, "$cluster/$slot width: $bounds")
                        assertTrue(bounds.height in 43.99f..72.01f, "$cluster/$slot height: $bounds")
                        assertEquals(bounds.width, bounds.height, .1f, "Every key stays square")
                        assertEquals(first.width, bounds.width, .1f, "Both clusters share a key size")
                        assertTrue(bounds.top >= 78f && bounds.bottom <= bottom.top - 8f, "Key must stay between header and softkeys")
                        assertTrue(bounds.left >= field.right && bounds.right <= right.left - 39f, "Key must stay beside input, inside workspace")
                        if (slot % 4 > 0) {
                            val previous = onNodeWithTag("keypad-$cluster-${slot - 1}").bounds()
                            val factor = if (cluster == "123" && slot % 4 == 3) .6f else .3f
                            assertEquals(bounds.width * factor, bounds.left - previous.right, .6f, "Configured column spacing")
                        }
                        if (slot >= 4) {
                            val above = onNodeWithTag("keypad-$cluster-${slot - 4}").bounds()
                            assertEquals(bounds.height * .3f, bounds.top - above.bottom, .6f, "Configured row spacing")
                        }
                    }
                }
            }
            val prompt = onNodeWithTag("terminal-prompt")
            check(prompt)
            val letterBounds = onNodeWithTag("keypad-ABC-0").bounds()
            val numberBounds = onNodeWithTag("keypad-123-0").bounds()
            prompt.performClick()
            // The full-sized key is actionable at its edge, away from the label.
            onNodeWithTag("keypad-ABC-2").performTouchInput { click(androidx.compose.ui.geometry.Offset(1f, center.y)) }
            prompt.assertIsFocused().assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("G")))
            onNodeWithTag("keypad-ABC-next").performClick()
            onNodeWithText("ABC 2/2 ▸").assertExists()
            onNodeWithText("123 1/2 ▸").assertExists()
            assertEquals(letterBounds, onNodeWithTag("keypad-ABC-0").bounds())
            assertEquals(numberBounds, onNodeWithTag("keypad-123-0").bounds())
            onNodeWithTag("keypad-123-next").performClick()
            onNodeWithText("ABC 2/2 ▸").assertExists()
            onNodeWithText("123 2/2 ▸").assertExists()
            onNodeWithTag("keypad-ABC-next").performClick()
            onNodeWithTag("keypad-123-next").performClick()
            check(prompt)
            runOnIdle { active = mdi }
            check(onNodeWithText("Unsent text"))
        } finally {
            owner.close(); runBlocking { owner.awaitClosed() }; scope.cancel()
            node.removeNode(); Preferences.userRoot().flush()
        }
    }
}
