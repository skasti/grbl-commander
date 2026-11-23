package no.grblcommander.desktop.mode.mdi

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.grblcommander.core.connection.BufferState
import no.grblcommander.core.connection.Operation
import no.grblcommander.desktop.mode.ControllerFixture
import no.grblcommander.desktop.ui.CommanderApp
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost
import no.grblcommander.desktop.ui.runAtScreenshotSizes

@OptIn(ExperimentalTestApi::class)
class MdiModeTest {
    private fun editorTest(block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        ControllerFixture().use { fixture ->
            val stack = ScreenStack(MdiMode(fixture.owner))
            setContent {
                MaterialTheme {
                    AppShell(ShellState.startup(), currentStack = { stack }) { ScreenStackHost(stack) }
                }
            }
            block()
        }
    }

    @Test fun `MDI accepts its first mouse letter without a prior hover or input focus`() = editorTest {
        onNodeWithTag("keypad-CODE-2").performMouseInput { click() }
        onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("G")),
        )
    }

    @Test fun `invalid replacement preserves the saved row and draft`() = editorTest {
        val input = onNodeWithTag("mdi-input")
        onNodeWithTag("softkey-F5").assertIsNotEnabled()
        onNodeWithTag("softkey-F6").assertIsNotEnabled()
        input.performTextInput("N20 G0 X1")
        input.performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("mdi-line-1").performClick().assertIsSelected()
        onNodeWithTag("softkey-F5").assertIsNotEnabled()
        input.performTextReplacement("!")
        onNodeWithTag("keypad-NUM+-15").performClick()
        onNodeWithTag("mdi-line-1").assertTextContains("N20 G0 X1")
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("!")))
        onNodeWithText("Use one ASCII line without embedded real-time commands").assertExists()
        onNodeWithTag("softkey-F6").performClick()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        onNodeWithTag("softkey-F5").performClick()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("N20 G0 X1")))
    }

    @Test fun `browsing and deselecting return to the unfinished new line and caret`() = editorTest {
        val input = onNodeWithTag("mdi-input")
        for (line in listOf("G90", "M2")) {
            input.performTextInput(line)
            input.performKeyInput { pressKey(Key.Enter) }
        }
        input.performTextInput("G X1")
        input.performKeyInput { pressKey(Key.Home); pressKey(Key.DirectionRight) }
        onNodeWithTag("softkey-F5").performClick()
        onNodeWithTag("softkey-F5").performClick()
        onNodeWithTag("softkey-F6").performClick()
        onNodeWithTag("softkey-F6").performClick()
        input.performTextInput("1")
        onNodeWithTag("mdi-line-2").performClick()
        onNodeWithTag("mdi-line-1").performClick().performClick()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("G1 X1")))
        input.performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("mdi-line-3").assertTextContains("G1 X1")
        onNodeWithTag("mdi-line-3").performClick().performClick()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun `physical and keypad Enter save to the local buffer`() = runAtScreenshotSizes { screenshot ->
        ControllerFixture().use { fixture ->
            fixture.connect()
            setContent { CommanderApp(fixture.owner, fixture.settings) }
            onNode(hasText("MDI") and hasClickAction()).performClick()
            onNodeWithTag("mdi-input").performClick().performTextInput("G0 X1")
            onNodeWithTag("keypad-NUM+-15").assertIsDisplayed().performClick()
            onNodeWithTag("mdi-input").performTextInput("M2")
            onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("M2")))
            screenshot("mdi")
            onNodeWithTag("keypad-CODE-next").performClick()
            onNodeWithTag("keypad-NUM+-next").assertIsDisplayed().performClick()
            onNodeWithText("CODE 2/2 ▸").assertExists()
            onNodeWithText("NUM+ 2/2 ▸").assertExists()
            onNodeWithTag("mdi-input").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("softkey-F5").performClick()
            onNodeWithTag("mdi-line-2").assertIsSelected()
            onNodeWithTag("mdi-input").performTextReplacement("M30")
            onNodeWithTag("keypad-NUM+-15").performClick()
            onNodeWithTag("mdi-line-2").assertTextContains("M30")
            onNodeWithTag("mdi-line-3").assertDoesNotExist()
            screenshot("mdi-page-2")
        }
    }

    @Test fun `Cycle Start tracks execution separately from editing and resumes without replay`() = runAtScreenshotSizes { screenshot ->
        ControllerFixture().use { fixture ->
            openMdi(fixture)
            val start = onNodeWithTag("softkey-F1")
            start.assertIsNotEnabled()
            saveLine("N70 G0 X1")
            saveLine("N90 G0 X2")
            assertTrue(fixture.transport.drainWrites().all { it == "?" })
            onNodeWithTag("mdi-line-1").performClick().assertIsSelected()

            start.assertIsEnabled().performClick()
            assertEquals("N70 G0 X1\n", fixture.nextCommand())
            fixture.report("<Run|Ln:70>")
            fixture.receive("ok")
            assertEquals("N90 G0 X2\n", fixture.nextCommand())
            val operation = checkNotNull(fixture.owner.state.value.operation)
            waitUntil(timeoutMillis = 5_000) {
                operation.state.value.lastSentLineNumber == 2 && operation.state.value.reply == BufferState.Reply.Pending
            }
            fixture.report("<Run|Ln:90>")
            waitUntil(timeoutMillis = 5_000) { operation.state.value.executingLineNumber == 2 }
            val executing = hasTestTag("mdi-executing-line") and hasAnyAncestor(hasTestTag("mdi-line-2"))
            onNode(executing, useUnmergedTree = true).assertTextEquals(">")
            onNodeWithTag("mdi-line-1").assertIsSelected()
            onNodeWithTag("mdi-input").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("N70 G0 X1")),
            )
            onNodeWithTag("keypad-NUM+-8").performClick()
            onNodeWithTag("keypad-NUM+-15").performClick()
            onNodeWithTag("mdi-line-1").assertTextContains("N70 G0 X1")
            screenshot("mdi-running")

            onNodeWithTag("softkey-F2").performClick()
            assertEquals("!", fixture.nextCommand())
            fixture.report("<Hold:1|Ln:90>")
            waitUntilExactlyOneExists(hasText("Held", substring = true), timeoutMillis = 5_000)
            start.assertIsNotEnabled()
            fixture.report("<Hold:0>")
            waitUntilExactlyOneExists(hasTestTag("softkey-F1") and isEnabled(), timeoutMillis = 5_000)
            onNodeWithText("Held · Active line unknown", substring = true).assertExists()
            screenshot("mdi-held")
            start.performClick()
            assertEquals("~", fixture.nextCommand())
            fixture.report("<Run>")
            fixture.receive("ok")
            waitUntil(timeoutMillis = 5_000) {
                operation.state.value.phase == Operation.Phase.RUNNING && operation.state.value.reply == BufferState.Reply.Ok
            }
            onNodeWithText("Active line unknown", substring = true).assertExists()
            onNodeWithTag("mdi-executing-line", useUnmergedTree = true).assertDoesNotExist()
            start.assertIsNotEnabled()
            fixture.report("<Idle>")
            waitUntilExactlyOneExists(hasText("Completed"), timeoutMillis = 5_000)
            assertTrue(fixture.transport.drainWrites().all { it == "?" })
        }
    }

    @Test fun `MDI shows rejection and requires confirmation before an interrupting reset`() = runAtScreenshotSizes { screenshot ->
        ControllerFixture().use { fixture ->
            openMdi(fixture)
            saveLine("N70 G0 X1")
            saveLine("N90 G0 X2")
            val start = onNodeWithTag("softkey-F1")
            start.performClick()
            assertEquals("N70 G0 X1\n", fixture.nextCommand())
            fixture.receive("error:20")
            waitUntilExactlyOneExists(hasText("Line 1 rejected (error:20)"), timeoutMillis = 5_000)
            screenshot("mdi-rejected")
            assertTrue(fixture.transport.drainWrites().all { it == "?" })

            fixture.report("<Idle>")
            waitUntilExactlyOneExists(hasTestTag("softkey-F1") and isEnabled(), timeoutMillis = 5_000)
            start.performClick()
            assertEquals("N70 G0 X1\n", fixture.nextCommand())
            val reset = onNodeWithTag("softkey-F3")
            reset.performClick()
            onNodeWithText("Reset controller?").assertExists()
            screenshot("mdi-reset-confirmation")
            onNodeWithTag("softkey-F8").performClick()
            assertTrue(fixture.transport.drainWrites().all { it == "?" })

            reset.performClick()
            onNodeWithTag("softkey-F1").performClick()
            assertEquals("\u0018", fixture.nextCommand())
            waitUntilExactlyOneExists(hasText("Interrupted · completion unknown", substring = true), timeoutMillis = 5_000)
            waitUntil(timeoutMillis = 5_000) {
                val state = fixture.owner.state.value
                !state.resetPending && !state.snapshot.resetInProgress && state.snapshot.normalCommandsSynchronized
            }
            assertTrue(fixture.transport.drainWrites().all { it == "?" })
        }
    }

    @Test fun `Idle with an unowned reply shows why Cycle Start is blocked`() = runAtScreenshotSizes { screenshot ->
        ControllerFixture().use { fixture ->
            openMdi(fixture)
            saveLine("G0 X1")
            val start = onNodeWithTag("softkey-F1")
            start.assertIsEnabled()
            fixture.receive("ok")
            waitUntilExactlyOneExists(hasText("Controller reset is required before sending commands."), timeoutMillis = 5_000)
            start.assertIsNotEnabled()
            assertEquals("Idle", fixture.owner.state.value.snapshot.status?.executionState)
            screenshot("mdi-start-blocked")
            assertTrue(fixture.transport.drainWrites().all { it == "?" })

            onNodeWithTag("softkey-F3").performClick()
            onNodeWithTag("softkey-F1").performClick()
            assertEquals("\u0018", fixture.nextCommand())
            waitUntilExactlyOneExists(hasTestTag("softkey-F1") and isEnabled(), timeoutMillis = 5_000)
            onNodeWithText("Controller reset is required before sending commands.").assertDoesNotExist()
        }
    }

    private fun ComposeUiTest.openMdi(fixture: ControllerFixture) {
        fixture.connect()
        setContent { CommanderApp(fixture.owner, fixture.settings) }
        onNode(hasText("MDI") and hasClickAction()).performClick()
    }

    private fun ComposeUiTest.saveLine(line: String) {
        val input = onNodeWithTag("mdi-input")
        input.performClick().performTextInput(line)
        input.performKeyInput { pressKey(Key.Enter) }
    }

    private fun ControllerFixture.nextCommand() = runBlocking {
        withTimeout(5_000) { transport.nextNonStatusWrite() }
    }

    private fun ControllerFixture.report(report: String) {
        transport.statusReport = report
        receive(report)
    }

    private fun ControllerFixture.receive(line: String) = runBlocking { transport.emit("$line\n") }

}
