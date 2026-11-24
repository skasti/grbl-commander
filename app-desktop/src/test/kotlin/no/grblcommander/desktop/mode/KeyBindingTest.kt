package no.grblcommander.desktop.mode

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.*
import kotlinx.coroutines.*
import no.grblcommander.desktop.mode.mdi.MdiMode
import no.grblcommander.desktop.mode.utils.connection.ControllerConnectionSettings
import no.grblcommander.desktop.mode.utils.terminal.Fixture
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.*
import no.grblcommander.machine.session.CommunicationEvent

@OptIn(ExperimentalTestApi::class)
class KeyBindingTest {
    private fun SemanticsNodeInteraction.assertPromptText(text: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))

    @Test fun `only the active screen handles keys and mode switches preserve each editor`() = runComposeUiTest {
        Fixture().use { f ->
            f.connect()
            val mdi = ScreenStack(MdiMode(f.owner))
            var active by mutableStateOf(f.util)
            setContent {
                MaterialTheme {
                    AppShell(ShellState.startup(), { active }) { ScreenStackHost(listOf(f.util, mdi), active) }
                }
            }
            onNodeWithTag("terminal-prompt").performClick().performTextInput("unsent")
            onNodeWithTag("softkey-R6").assertIsSelected().performClick().assertIsNotSelected()
            onNodeWithTag("softkey-F8").performClick() // Utilities page.
            runOnIdle {
                assertNotNull(f.util.softKeyDefinition(BottomSoftKeySlot.F7)!!.icon)
                assertEquals("Filters", f.util.softKeyDefinition(BottomSoftKeySlot.F7)!!.label)
                assertNull(f.util.softKeyDefinition(BottomSoftKeySlot.F5))
                assertEquals(setOf(RightSoftKeySlot.R6, RightSoftKeySlot.R7), f.util.effectiveRightKeys.keys)
                assertEquals("⏬\nFollow", f.util.softKeyDefinition(RightSoftKeySlot.R6)!!.label)
                assertNull(f.util.softKeyDefinition(BottomSoftKeySlot.F4))
            }
            onNodeWithTag("softkey-F7").performClick()
            onNodeWithTag("softkey-F1").assertIsNotSelected().performClick().assertIsSelected()
            runOnIdle {
                assertNull(f.util.softKeyDefinition(BottomSoftKeySlot.F8))
                assertFalse(activateSoftKey({ f.util }, BottomSoftKeySlot.F8))
            }
            onNodeWithTag("softkey-F3").performClick() // OK filter, never Reset.
            onNodeWithText("Reset controller?").assertDoesNotExist()
            assertTrue(f.transport.drainWrites().all { it == "?" }) // No realtime action leaked.
            onNodeWithTag("keypad-CODE-next").performClick()
            onNodeWithTag("keypad-NUM+-next").performClick()
            runOnIdle { active = mdi }
            onNodeWithTag("terminal-prompt").assertDoesNotExist()
            onNodeWithTag("gcode-input").performClick().performTextInput("G0 X999")
            onNodeWithTag("gcode-input").performKeyInput { pressKey(Key.Enter) }
            assertTrue(f.owner.diagnosticSnapshot().none { it.event.category == CommunicationEvent.Category.COMMAND })
            runBlocking { repeat(300) { f.transport.emit("[EXT:$it]\n") } }
            waitUntil(timeoutMillis = 5_000) { f.owner.diagnosticSnapshot().any { it.event.text == "[EXT:299]" } }
            waitUntil(timeoutMillis = 5_000) { f.terminal.history.any { it.event.text == "[EXT:299]" } }
            onNodeWithTag("softkey-F5").performClick()
            onNodeWithTag("gcode-input").performTextReplacement("unfinished")
            runOnIdle { active = f.util }
            onNodeWithText("CODE 2/2 ▸").assertExists()
            onNodeWithText("NUM+ 2/2 ▸").assertExists()
            onNodeWithTag("terminal-prompt").assertPromptText("unsent")
            onNodeWithTag("softkey-F1").assertIsSelected() // Filter bank survived the mode switch.
            onNodeWithTag("softkey-R7").performClick()
            runOnIdle {
                assertIs<ControllerConnectionSettings>(f.util.top)
                assertEquals(setOf(RightSoftKeySlot.R7), f.util.effectiveRightKeys.keys)
                assertEquals("⌨\nTerminal", f.util.softKeyDefinition(RightSoftKeySlot.R7)!!.label)
                assertNotNull(f.util.softKeyDefinition(BottomSoftKeySlot.F6))
                assertEquals("Save", f.util.softKeyDefinition(BottomSoftKeySlot.F7)!!.label)
            }
            runBlocking { f.transport.emit("[COVERED:settings]\n") }
            waitUntil(timeoutMillis = 5_000) { f.terminal.history.any { it.event.text == "[COVERED:settings]" } }
            onNodeWithTag("softkey-R7").performClick()
            onNodeWithText("CODE 2/2 ▸").assertExists()
            onNodeWithText("NUM+ 2/2 ▸").assertExists()
            onNodeWithTag("terminal-prompt").assertPromptText("unsent")
            onNodeWithTag("softkey-F1").assertIsSelected() // Settings restores the filter menu too.
            onNodeWithTag("softkey-F7").performClick() // Back to the same utilities page.
            onNodeWithText("🔄\nSoft Reset").assertExists()
            onNodeWithText("Filters").assertExists()
            onNodeWithText("🔄\nShow all").assertDoesNotExist()
            onNodeWithTag("softkey-F3").performClick()
            runOnIdle { assertTrue(f.owner.diagnosticSnapshot().isNotEmpty()); assertEquals("unsent", f.terminal.prompt.text) }
            onNodeWithTag("softkey-F8").performClick() // Wrap to realtime page.
            onNodeWithTag("softkey-R6").assertIsNotSelected()
            onNodeWithText("⌨\nTerminal").assertDoesNotExist()
            runOnIdle { active = mdi }
            onNodeWithTag("gcode-line-1").assertIsSelected()
            onNodeWithTag("gcode-input").assertPromptText("unfinished")
        }
    }

}
