package no.grblcommander.desktop.mode.utils.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.*
import kotlinx.coroutines.*
import no.grblcommander.desktop.ui.CommanderApp
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.*
import no.grblcommander.desktop.ui.runAtScreenshotSizes
import no.grblcommander.machine.session.CommunicationEvent

@OptIn(ExperimentalTestApi::class)
class TerminalTest {
    private fun ComposeUiTest.host(f: Fixture) {
        setContent {
            MaterialTheme { AppShell(ShellState.startup(), { f.util }) { ScreenStackHost(f.util) } }
        }
    }
    private fun ComposeUiTest.hostApplication(f: Fixture) {
        setContent { CommanderApp(f.owner, f.settings) }
        onNodeWithContentDescription("Util").performClick().assertIsSelected()
        // Leave wall-clock timestamps out of the captured traffic rows.
        onNodeWithTag("softkey-F7").performClick()
        onNodeWithTag("softkey-F6").performClick()
        onNodeWithTag("softkey-F7").performClick()
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
            waitUntil(timeoutMillis = 5_000) { f.owner.diagnosticSnapshot().any { it.event.text == "[EXT:unfamiliar]" } }
            runOnIdle {
                assertEquals(TextFieldValue("G0 X12", TextRange(3)), f.terminal.prompt)
                assertEquals(1, f.owner.diagnosticSnapshot().count { it.event.category == CommunicationEvent.Category.COMMAND })
            }
            onNodeWithTag("softkey-F3").performClick()
            onNodeWithTag("terminal-prompt").assertPromptText("\$I")
            onNodeWithTag("softkey-F4").performClick()
            runOnIdle { assertEquals(TextFieldValue("G0 X12", TextRange(3)), f.terminal.prompt) }
            onNodeWithText("⬆️\nPrev Cmd").assertExists()
            onNodeWithText("⬇️\nNext Cmd").assertExists()
            onNodeWithText("⏸️\nFeed Hold (!)").assertExists()
            onNodeWithText("Send").assertDoesNotExist()
        }
    }

    @Test fun `touch Enter shares physical and IME admission`() = runAtScreenshotSizes { screenshot ->
        Fixture().use { f ->
            f.connect(); hostApplication(f)
            onNodeWithText("CODE 1/2 ▸").assertExists()
            onNodeWithText("NUM+ 1/2 ▸").assertExists()
            screenshot("terminal")
            for (label in listOf("Cycle Start (~)", "Feed Hold (!)")) {
                val layouts = mutableListOf<TextLayoutResult>()
                onNodeWithText(label, substring = true, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized), "$label is not ellipsized")
                assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            }
            onNodeWithTag("terminal-prompt").performClick().performTextInput("\$I")
            onNodeWithTag("keypad-CODE-next").performClick()
            onNodeWithTag("keypad-NUM+-next").assertIsDisplayed().performClick()
            onNodeWithTag("terminal-prompt").assertIsFocused()
            onNodeWithText("CODE 2/2 ▸").assertExists()
            onNodeWithText("NUM+ 2/2 ▸").assertExists()
            screenshot("terminal-page-2")
            onNodeWithTag("keypad-NUM+-15").assertIsDisplayed().performClick()
            assertEquals("\$I\n", runBlocking { withTimeout(5_000) { f.transport.nextNonStatusWrite() } })
            enter()
            onNodeWithTag("terminal-prompt").performImeAction()
            onNodeWithTag("keypad-NUM+-15").assertIsDisplayed().performClick()
            runOnIdle { assertEquals(1, f.owner.diagnosticSnapshot().count { it.event.category == CommunicationEvent.Category.COMMAND }) }
        }
    }

    @Test fun `acknowledgment during the first letter gesture does not cancel insertion`() = runComposeUiTest {
        Fixture().use { f ->
            f.connect(); host(f)
            onNodeWithTag("terminal-prompt").performClick().performTextInput("G1X100F500")
            enter()
            assertEquals("G1X100F500\n", runBlocking { withTimeout(5_000) { f.transport.nextNonStatusWrite() } })
            onNodeWithTag("keypad-CODE-2").performTouchInput { down(center) }
            runBlocking { f.transport.emit("ok\n") }
            waitUntil(timeoutMillis = 5_000) { f.terminal.prompt.text.isEmpty() }
            onNodeWithTag("keypad-CODE-2").performTouchInput { up() }
            onNodeWithTag("terminal-prompt").assertPromptText("G")
        }
    }

    @Test fun `new keypad and keyboard edits survive asynchronous acknowledgment`() = runComposeUiTest {
        Fixture().use { f ->
            f.connect(); host(f)
            onNodeWithTag("terminal-prompt").performClick().performTextInput("G1X100F500")
            enter()
            assertEquals("G1X100F500\n", runBlocking { withTimeout(5_000) { f.transport.nextNonStatusWrite() } })
            onNodeWithTag("terminal-prompt").performTextClearance()
            onNodeWithTag("keypad-CODE-2").performTouchInput { click() }
            onNodeWithTag("terminal-prompt").performTextInput("X")
            runBlocking { f.transport.emit("ok\n") }
            waitUntil(timeoutMillis = 5_000) { !f.owner.state.value.manualCommandPending }
            onNodeWithTag("terminal-prompt").assertPromptText("GX")
        }
    }

    @Test fun `keypad-only mouse input works without first focusing the prompt`() = runComposeUiTest {
        Fixture().use { f ->
            f.connect(); host(f)
            // G1X100F500, entirely through the mouse-operated keypad, with no input focus request.
            listOf("CODE-2", "NUM+-8", "CODE-4", "NUM+-8", "NUM+-13", "NUM+-13", "CODE-12", "NUM+-5", "NUM+-13", "NUM+-13").forEach {
                onNodeWithTag("keypad-$it").performMouseInput { click() }
            }
            onNodeWithTag("terminal-prompt").assertPromptText("G1X100F500")
            onNodeWithTag("keypad-NUM+-15").performMouseInput { click() }
            assertEquals("G1X100F500\n", runBlocking { withTimeout(5_000) { f.transport.nextNonStatusWrite() } })
            f.transport.statusReport = "<Run|MPos:1,0,0>"
            runBlocking { f.transport.emit("ok\n" + f.transport.statusReport + "\n") }
            waitUntil(timeoutMillis = 5_000) { f.terminal.prompt.text.isEmpty() }
            f.transport.statusReport = "<Idle|MPos:100,0,0>"
            runBlocking { f.transport.emit(f.transport.statusReport + "\n") }
            waitUntil(timeoutMillis = 5_000) { f.owner.state.value.snapshot.status?.executionState == "Idle" }
            onNodeWithTag("keypad-CODE-2").performMouseInput { click() }
            onNodeWithTag("terminal-prompt").assertPromptText("G")
        }
    }

    @Test fun `first mouse letter after acknowledgment is inserted without hover`() = firstInputAfterCommand(motion = false, touch = false, mouse = true)
    @Test fun `first mouse letter after motion reaches Idle is inserted without hover`() = firstInputAfterCommand(motion = true, touch = false, mouse = true)

    @Test fun `first touch letter after acknowledgment is inserted once`() = firstInputAfterCommand(motion = false, touch = true)
    @Test fun `first touch letter after motion reaches Idle is inserted once`() = firstInputAfterCommand(motion = true, touch = true)
    @Test fun `first platform text input after acknowledgment is inserted once`() = firstInputAfterCommand(motion = false, touch = false)
    @Test fun `first platform text input after motion reaches Idle is inserted once`() = firstInputAfterCommand(motion = true, touch = false)

    private fun firstInputAfterCommand(motion: Boolean, touch: Boolean, mouse: Boolean = false) = runAtScreenshotSizes(capture = motion && mouse) { screenshot ->
        Fixture().use { f ->
            f.connect(); hostApplication(f)
            onNodeWithTag("terminal-prompt").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            onNodeWithTag("terminal-prompt").performTextInput("G1X100F500")
            enter()
            assertEquals("G1X100F500\n", runBlocking { withTimeout(5_000) { f.transport.nextNonStatusWrite() } })
            if (motion) {
                f.transport.statusReport = "<Run|MPos:1,0,0>"
                runBlocking { f.transport.emit(f.transport.statusReport + "\n") }
                waitUntil(timeoutMillis = 5_000) { f.owner.state.value.snapshot.status?.executionState == "Run" }
            }
            runBlocking { f.transport.emit("ok\n") }
            waitUntilExactlyOneExists(
                hasTestTag("terminal-prompt") and
                    SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
                timeoutMillis = 5_000,
            )
            if (motion && mouse) {
                waitUntilExactlyOneExists(hasText("RX ACKNOWLEDGMENT  ok"), timeoutMillis = 5_000)
                waitUntilExactlyOneExists(hasText("CONNECTED · TCP localhost:4321 · Run"), timeoutMillis = 5_000)
                screenshot("terminal-acknowledged")
            }
            if (motion) {
                f.transport.statusReport = "<Idle|MPos:100,0,0>"
                runBlocking { f.transport.emit(f.transport.statusReport + "\n") }
                waitUntil(timeoutMillis = 5_000) { f.owner.state.value.snapshot.status?.executionState == "Idle" }
            }
            if (mouse) onNodeWithTag("keypad-CODE-2").performMouseInput { click() }
            else if (touch) onNodeWithTag("keypad-CODE-2").performTouchInput { click() }
            else onNodeWithTag("terminal-prompt").performTextInput("G")
            val expected = "G"
            onNodeWithTag("terminal-prompt").assertPromptText(expected).assertIsFocused()
            onNodeWithTag("terminal-prompt").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1)),
            )
            if (motion && mouse) {
                waitUntilExactlyOneExists(hasText("CONNECTED · TCP localhost:4321 · Idle"), timeoutMillis = 5_000)
                screenshot("terminal-idle-first-letter")
            }
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
            onNodeWithText("Controller reset is required before sending commands.").assertExists()
            assertTrue(f.owner.diagnosticSnapshot().none { it.event.category == CommunicationEvent.Category.COMMAND })
        }
    }

    @Test fun `realtime controls use correct shared path without a status key and Reset requires confirmation`() = runComposeUiTest {
        Fixture().use { f ->
            host(f)
            onNodeWithTag("softkey-F1").assertIsNotEnabled()
            onNodeWithTag("softkey-F2").assertIsNotEnabled()
            onNodeWithText("❔\nStatus (?)").assertDoesNotExist()
            f.connect()
            waitUntil(timeoutMillis = 5_000) { f.util.softKeyDefinition(BottomSoftKeySlot.F2)!!.enabled() }
            f.transport.drainWrites()
            onNodeWithTag("softkey-F2").performClick()
            assertEquals("!", runBlocking { withTimeout(5_000) { f.transport.nextNonStatusWrite() } })
            onNodeWithTag("softkey-F1").assertIsEnabled().performClick()
            assertEquals("~", runBlocking { withTimeout(5_000) { f.transport.nextNonStatusWrite() } })
            onNodeWithTag("softkey-F8").performClick()
            onNodeWithTag("softkey-F1").performClick()
            onNodeWithText("Reset controller?").assertExists()
            onNodeWithTag("softkey-F8").performClick()
            assertTrue(f.transport.drainWrites().all { it == "?" })
            onNodeWithTag("softkey-F1").performClick()
            onNodeWithTag("softkey-F1").performClick()
            assertEquals("\u0018", runBlocking { withTimeout(5_000) { f.transport.nextNonStatusWrite() } })
            waitUntil(timeoutMillis = 5_000) { !f.owner.state.value.snapshot.resetInProgress }
            runOnIdle { assertSame(f.terminal, f.util.top) }
        }
    }

    @Test fun `Cycle Start is restored after acknowledged motion reaches completed feed hold`() = runComposeUiTest {
        Fixture().use { f ->
            f.connect(); host(f)
            onNodeWithText("▶️\nCycle Start (~)").assertExists()
            onNodeWithText("⏸️\nFeed Hold (!)").assertExists()
            onNodeWithText("⬆️\nPrev Cmd").assertExists()
            onNodeWithText("⬇️\nNext Cmd").assertExists()
            onNodeWithText("⏬\nFollow").assertExists()
            onNodeWithTag("softkey-F1").assertIsEnabled()
            onNodeWithTag("terminal-prompt").performTextInput("G1 X100 F100")
            onNodeWithTag("terminal-prompt").performKeyInput { pressKey(Key.Enter) }
            assertEquals("G1 X100 F100\n", runBlocking { withTimeout(5_000) { f.transport.nextNonStatusWrite() } })
            runBlocking { f.transport.emit("ok\n") }
            waitUntil(timeoutMillis = 5_000) { !f.owner.state.value.manualCommandPending }
            f.transport.statusReport = "<Run|MPos:1,0,0>"
            runBlocking { f.transport.emit(f.transport.statusReport + "\n") }
            waitUntil(timeoutMillis = 5_000) { f.owner.state.value.snapshot.status?.executionState == "Run" }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag("softkey-F1").assertIsNotEnabled() }.isSuccess
            }
            onNodeWithTag("softkey-F1").assertIsNotEnabled()
            onNodeWithTag("softkey-F2").performClick()
            assertEquals("!", runBlocking { withTimeout(5_000) { f.transport.nextNonStatusWrite() } })
            f.transport.statusReport = "<Hold:1|MPos:2,0,0>"
            runBlocking { f.transport.emit(f.transport.statusReport + "\n") }
            waitUntil(timeoutMillis = 5_000) { f.owner.state.value.snapshot.status?.executionState == "Hold:1" }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag("softkey-F1").assertIsNotEnabled() }.isSuccess
            }
            onNodeWithTag("softkey-F1").assertIsNotEnabled()
            f.transport.statusReport = "<Hold:0|MPos:3,0,0>"
            runBlocking { f.transport.emit(f.transport.statusReport + "\n") }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag("softkey-F1").assertIsEnabled() }.isSuccess
            }
            onNodeWithTag("softkey-F1").assertIsEnabled().performClick()
            assertEquals("~", runBlocking { withTimeout(5_000) { f.transport.nextNonStatusWrite() } })
            assertTrue(f.transport.drainWrites().all { it == "?" })
        }
    }

    @Test fun `manual scrollback is preserved during incoming traffic and Follow explicitly jumps to latest`() = runComposeUiTest {
        Fixture().use { f ->
            f.connect(); host(f)
            runBlocking { repeat(100) { f.transport.emit("[LINE:$it]\n") } }
            waitUntil(timeoutMillis = 5_000) { f.terminal.history.any { it.event.text == "[LINE:99]" } }
            onNodeWithText("[LINE:99]", substring = true).assertExists()
            onNodeWithTag("terminal-log").performTouchInput { swipeDown() }
            onNodeWithTag("softkey-R6").assertIsNotSelected()
            runOnIdle { assertEquals("", f.terminal.prompt.text) } // Scrolling must not edit the prompt.
            runBlocking { f.transport.emit("[LINE:latest]\n") }
            waitUntil(timeoutMillis = 5_000) { f.owner.diagnosticSnapshot().any { it.event.text == "[LINE:latest]" } }
            onNodeWithTag("softkey-R6").assertIsNotSelected().performClick()
            onNodeWithTag("softkey-R6").assertIsSelected()
            onNodeWithText("[LINE:latest]", substring = true).assertExists()
            onNodeWithTag("terminal-log").performTouchInput { swipeDown() }
            onNodeWithTag("softkey-R6").assertIsNotSelected()
            repeat(10) {
                if (!f.util.softKeyDefinition(RightSoftKeySlot.R6)!!.active())
                    onNodeWithTag("terminal-log").performTouchInput { swipeUp() }
            }
            onNodeWithTag("softkey-R6").assertIsSelected() // Returning to bottom restores Follow.
            runBlocking { f.transport.emit("[LINE:after-bottom]\n") }
            waitUntil(timeoutMillis = 5_000) { f.terminal.history.lastOrNull()?.event?.text == "[LINE:after-bottom]" }
            onNodeWithText("[LINE:after-bottom]", substring = true).assertExists()
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
