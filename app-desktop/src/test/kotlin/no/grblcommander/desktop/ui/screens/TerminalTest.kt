package no.grblcommander.desktop.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.*
import no.grblcommander.configuration.ControllerEndpointPreferences
import no.grblcommander.core.connection.*
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.*
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.CommunicationEvent
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class TerminalTest {
    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = ConnectionTestTransport()
        val owner = ControllerConnection(scope, { _, child -> GrblMachineSession(transport, child) })
        val node = Preferences.userRoot().node("no/grblcommander/tests/${UUID.randomUUID()}")
        val terminal = Terminal(ControllerSettings(owner, ControllerEndpointPreferences(node)))
        val util = ScreenStack(terminal)
        val other = ScreenStack(MdiMode())
        var active by mutableStateOf(util)
        fun connect() = runBlocking { owner.connect(ControllerEndpoint.Tcp("localhost", 4321)) }
        override fun close() {
            owner.close(); runBlocking { owner.awaitClosed() }; scope.cancel()
            node.removeNode(); Preferences.userRoot().flush()
        }
    }
    private fun ComposeUiTest.host(f: Fixture) {
        setContent {
            MaterialTheme { AppShell(ShellState.startup(), { f.active }) { ScreenStackHost(listOf(f.util, f.other), f.active) } }
        }
    }
    private fun SemanticsNodeInteraction.assertPromptText(text: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))

    private fun ComposeUiTest.enter() = onNodeWithTag("terminal-prompt").performKeyInput { pressKey(Key.Enter) }

    @Test fun `Enter submits once without echo duplication and incoming output preserves draft caret`() = runComposeUiTest {
        Fixture().use { f ->
            f.connect(); host(f)
            onNodeWithTag("terminal-prompt").performClick().performTextInput("\$I")
            enter()
            assertEquals("\$I\n", runBlocking { withTimeout(5_000) { f.transport.nextNonStatusWrite() } })
            enter() // An additional Enter while awaiting ok cannot enqueue another line.
            runOnIdle { f.terminal.prompt = TextFieldValue("G0 X12", TextRange(3)) }
            runBlocking { f.transport.emit("[EXT:unfamiliar]\nok\n") }
            waitUntil(timeoutMillis = 5_000) { f.owner.history.value.any { it.event.text == "[EXT:unfamiliar]" } }
            runOnIdle {
                assertEquals(TextFieldValue("G0 X12", TextRange(3)), f.terminal.prompt)
                assertEquals(1, f.owner.history.value.count { it.event.category == CommunicationEvent.Category.COMMAND })
            }
            onNodeWithTag("softkey-F8").performClick()
            onNodeWithTag("softkey-F1").performClick()
            onNodeWithTag("terminal-prompt").assertPromptText("\$I")
            onNodeWithTag("softkey-F2").performClick()
            runOnIdle { assertEquals(TextFieldValue("G0 X12", TextRange(3)), f.terminal.prompt) }
            onNodeWithText("Previous command").assertDoesNotExist()
            onNodeWithText("Next command").assertDoesNotExist()
            onNodeWithTag("softkey-F8").performClick()
            onNodeWithText("⏸\nFeed Hold (!)").assertExists()
            onNodeWithText("Send").assertDoesNotExist()
        }
    }

    @Test fun `disconnected multiline and unsynchronized admission preserve input without TX`() = runComposeUiTest {
        Fixture().use { f ->
            host(f)
            onNodeWithTag("terminal-prompt").performClick().performTextInput("G0 X1")
            enter()
            runOnIdle { assertNotNull(f.terminal.localError); assertEquals("G0 X1", f.terminal.prompt.text) }
            f.connect()
            runOnIdle { f.terminal.prompt = TextFieldValue("G0 X1\nG0 X2") }
            onNodeWithTag("terminal-prompt").performImeAction()
            runOnIdle { assertContains(f.terminal.localError.orEmpty(), "ASCII"); assertContains(f.terminal.prompt.text, "\n") }
            runBlocking { f.transport.emit("ok\n") } // Unowned acknowledgment invalidates correlation.
            waitUntil(timeoutMillis = 5_000) { !f.owner.state.value.snapshot.normalCommandsSynchronized }
            runOnIdle { f.terminal.prompt = TextFieldValue("\$I") }
            onNodeWithTag("terminal-prompt").performImeAction()
            runOnIdle { assertEquals("\$I", f.terminal.prompt.text); assertNotNull(f.terminal.localError) }
            assertTrue(f.owner.history.value.none { it.event.category == CommunicationEvent.Category.COMMAND })
        }
    }

    @Test fun `bottom Filters restores the selected page and R7 navigation preserves menu and input`() = runComposeUiTest {
        Fixture().use { f ->
            f.connect(); host(f)
            onNodeWithTag("terminal-prompt").performClick().performTextInput("unsent")
            onNodeWithTag("softkey-F4").assertIsSelected().performClick().assertIsNotSelected()
            onNodeWithTag("softkey-F8").performClick() // Utilities page.
            runOnIdle {
                assertNotNull(f.util.softKeyDefinition(BottomSoftKeySlot.F7)!!.icon)
                assertEquals("Filters", f.util.softKeyDefinition(BottomSoftKeySlot.F7)!!.label)
                assertNull(f.util.softKeyDefinition(BottomSoftKeySlot.F5))
                assertEquals(setOf(RightSoftKeySlot.R7), f.util.effectiveRightKeys.keys)
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
            runOnIdle { f.active = f.other }
            onNodeWithTag("terminal-prompt").assertDoesNotExist()
            onNodeWithText("Unsent text").performClick().performTextInput("G0 X999")
            onNodeWithText("Unsent text").performKeyInput { pressKey(Key.Enter) }
            assertTrue(f.owner.history.value.none { it.event.category == CommunicationEvent.Category.COMMAND })
            runBlocking { repeat(300) { f.transport.emit("[EXT:$it]\n") } }
            waitUntil(timeoutMillis = 5_000) { f.owner.history.value.any { it.event.text == "[EXT:299]" } }
            runOnIdle { f.active = f.util }
            onNodeWithTag("terminal-prompt").assertPromptText("unsent")
            onNodeWithTag("softkey-F1").assertIsSelected() // Filter bank survived the mode switch.
            onNodeWithTag("softkey-R7").performClick()
            runOnIdle {
                assertIs<ControllerConnectionSettings>(f.util.top)
                assertEquals(setOf(RightSoftKeySlot.R7), f.util.effectiveRightKeys.keys)
                assertEquals("⌨\nTerminal", f.util.softKeyDefinition(RightSoftKeySlot.R7)!!.label)
                assertNull(f.util.softKeyDefinition(BottomSoftKeySlot.F6))
                assertNull(f.util.softKeyDefinition(BottomSoftKeySlot.F7))
            }
            onNodeWithTag("softkey-R7").performClick()
            onNodeWithTag("terminal-prompt").assertPromptText("unsent")
            onNodeWithTag("softkey-F1").assertIsSelected() // Settings restores the filter menu too.
            onNodeWithTag("softkey-F7").performClick() // Back to the same utilities page.
            onNodeWithText("Previous\ncommand").assertExists()
            onNodeWithText("Filters").assertExists()
            onNodeWithText("🔄\nShow all").assertDoesNotExist()
            onNodeWithTag("softkey-F4").performClick()
            runOnIdle { assertTrue(f.owner.history.value.isNotEmpty()); assertEquals("unsent", f.terminal.prompt.text) }
            onNodeWithTag("softkey-F8").performClick() // Wrap to realtime page.
            onNodeWithTag("softkey-F4").assertIsNotSelected()
            onNodeWithText("⌨\nTerminal").assertDoesNotExist()
        }
    }

    @Test fun `realtime controls use correct shared path without a status key and Reset requires confirmation`() = runComposeUiTest {
        Fixture().use { f ->
            host(f)
            onNodeWithTag("softkey-F1").assertIsNotEnabled()
            onNodeWithTag("softkey-F2").assertIsNotEnabled()
            onNodeWithText("❔\nStatus (?)").assertDoesNotExist()
            f.connect()
            waitUntil(timeoutMillis = 5_000) { f.util.softKeyDefinition(BottomSoftKeySlot.F1)!!.enabled() }
            f.transport.drainWrites()
            onNodeWithTag("softkey-F1").performClick()
            assertEquals("!", runBlocking { withTimeout(5_000) { f.transport.nextNonStatusWrite() } })
            onNodeWithTag("softkey-F2").assertIsNotEnabled()
            onNodeWithTag("softkey-F3").performClick()
            onNodeWithText("Reset controller?").assertExists()
            onNodeWithTag("softkey-F8").performClick()
            assertTrue(f.transport.drainWrites().all { it == "?" })
            onNodeWithTag("softkey-F3").performClick()
            onNodeWithTag("softkey-F1").performClick()
            assertEquals("\u0018", runBlocking { withTimeout(5_000) { f.transport.nextNonStatusWrite() } })
            waitUntil(timeoutMillis = 5_000) { !f.owner.state.value.snapshot.resetInProgress }
            runOnIdle { assertSame(f.terminal, f.util.top) }
        }
    }

    @Test fun `manual scrollback is preserved during incoming traffic and Follow explicitly jumps to latest`() = runComposeUiTest {
        Fixture().use { f ->
            f.connect(); host(f)
            runBlocking { repeat(100) { f.transport.emit("[LINE:$it]\n") } }
            waitUntil(timeoutMillis = 5_000) { f.owner.history.value.any { it.event.text == "[LINE:99]" } }
            onNodeWithTag("terminal-log").performTouchInput { swipeDown() }
            onNodeWithTag("softkey-F4").assertIsNotSelected()
            runBlocking { f.transport.emit("[LINE:latest]\n") }
            waitUntil(timeoutMillis = 5_000) { f.owner.history.value.any { it.event.text == "[LINE:latest]" } }
            onNodeWithTag("softkey-F4").assertIsNotSelected().performClick()
            onNodeWithTag("softkey-F4").assertIsSelected()
            onNodeWithText("[LINE:latest]", substring = true).assertExists()
        }
    }
    @Test fun `prompt marker and editable text share the same vertical position and touch target`() = runComposeUiTest {
        Fixture().use { f ->
            host(f)
            onNodeWithTag("terminal-prompt-marker", useUnmergedTree = true).performClick()
            onNodeWithTag("terminal-prompt").performTextInput("G0 X100")
            val marker = onNodeWithTag("terminal-prompt-marker", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val input = onNodeWithTag("terminal-input-line", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(kotlin.math.abs(marker.center.y - input.center.y) < 1f, "Prompt marker and input must align: $marker / $input")
            val markerLayout = mutableListOf<TextLayoutResult>()
            val inputLayout = mutableListOf<TextLayoutResult>()
            onNodeWithTag("terminal-prompt-marker", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(markerLayout) }
            onNodeWithTag("terminal-prompt").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(inputLayout) }
            val markerBaseline = marker.top + markerLayout.single().firstBaseline
            val inputBaseline = input.top + inputLayout.single().firstBaseline
            assertTrue(kotlin.math.abs(markerBaseline - inputBaseline) < 1f, "Prompt text baselines must match: $markerBaseline / $inputBaseline")
            onNodeWithTag("terminal-prompt").assertPromptText("G0 X100")
        }
    }

}
