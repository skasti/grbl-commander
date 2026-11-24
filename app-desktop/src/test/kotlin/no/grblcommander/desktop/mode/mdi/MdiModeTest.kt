package no.grblcommander.desktop.mode.mdi

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
import no.grblcommander.desktop.ui.runAtScreenshotSizes

@OptIn(ExperimentalTestApi::class)
class MdiModeTest {
    @Test fun `MDI workspace places its editor, DRO and keypad pages`() = runAtScreenshotSizes { screenshot ->
        ControllerFixture().use { fixture ->
            fixture.connect()
            setContent { CommanderApp(fixture.owner, fixture.settings) }
            onNode(hasText("MDI") and hasClickAction()).performClick()
            val input = onNodeWithTag("gcode-input").performClick().performTextInput("!")
            input.performKeyInput { pressKey(Key.Enter) }
            onNodeWithText("Use one ASCII line without embedded real-time commands").assertExists()
            onNodeWithTag("gcode-line-1").assertDoesNotExist()
            input.performTextReplacement("G1X100F500")
            onNodeWithTag("keypad-NUM+-15").assertIsDisplayed().performClick()
            onNodeWithTag("gcode-line-1").assertTextContains("G1 X100 F500")
            onNodeWithTag("gcode-input").performTextInput("M2")
            onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("M2")))
            val workspace = onNodeWithTag("mdi-workspace").fetchSemanticsNode().boundsInRoot
            val dro = onNodeWithTag("mdi-dro").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val label = onNodeWithText("DRO", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val firstLetter = onNodeWithTag("keypad-CODE-0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val lastLetter = onNodeWithTag("keypad-CODE-3").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val firstNumber = onNodeWithTag("keypad-NUM+-0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val lastNumber = onNodeWithTag("keypad-NUM+-3").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val buffer = onNodeWithTag("gcode-buffer").fetchSemanticsNode().boundsInRoot
            assertEquals(workspace.height * 0.40f, dro.height, absoluteTolerance = 1f)
            assertTrue((dro.center - label.center).getDistance() <= 1f)
            assertEquals(lastNumber.right, dro.right)
            assertEquals(dro.height * (4f / 3f), dro.width, absoluteTolerance = 1.5f)
            assertEquals(firstLetter.top, firstNumber.top)
            assertTrue(buffer.right < firstLetter.left)
            assertEquals(firstLetter.width, firstNumber.left - lastLetter.right)
            assertTrue(dro.bottom < firstNumber.top)
            screenshot("mdi")
            onNodeWithTag("keypad-CODE-next").performClick()
            onNodeWithTag("keypad-NUM+-next").assertIsDisplayed().performClick()
            onNodeWithText("CODE 2/2 ▸").assertExists()
            onNodeWithText("NUM+ 2/2 ▸").assertExists()
            onNodeWithTag("gcode-input").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("softkey-F5").performClick()
            onNodeWithTag("gcode-line-2").assertIsSelected()
            onNodeWithTag("gcode-input").performTextReplacement("M30")
            screenshot("mdi-page-2")
            onNodeWithTag("keypad-NUM+-15").performClick()
            onNodeWithTag("gcode-line-2").assertTextContains("M30").assertIsNotSelected()
            onNodeWithTag("gcode-line-3").assertDoesNotExist()
            onNodeWithText("New line").assertExists()
            onNodeWithTag("gcode-input").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
            )
        }
    }

    @Test fun `Cycle Start tracks execution separately from editing and resumes without replay`() = runAtScreenshotSizes { screenshot ->
        ControllerFixture().use { fixture ->
            openMdi(fixture)
            val start = onNodeWithTag("softkey-F1")
            start.assertIsNotEnabled()
            val program = listOf(
                "N10 G90",
                "N20 G0 X0 Y0",
                "N30G1X20F120",
                "N40 G1 Y15",
                "N50 G1 X0",
                "N60G1Y0",
                "N70 G0 Z5",
                "N80 M2",
            )
            program.forEach { saveLine(it) }
            assertTrue(fixture.transport.drainWrites().all { it == "?" })
            onNodeWithTag("gcode-line-6").performClick().assertIsSelected()

            start.assertIsEnabled().performClick()
            fixture.report("<Run>")
            for (line in program.take(4)) {
                assertEquals("$line\n", fixture.nextCommand())
                fixture.receive("ok")
            }
            assertEquals("${program[4]}\n", fixture.nextCommand())
            val operation = checkNotNull(fixture.owner.state.value.operation)
            waitUntil(timeoutMillis = 5_000) {
                operation.state.value.lastSentLineNumber == 5 && operation.state.value.reply == BufferState.Reply.Pending
            }
            fixture.report("<Run|Ln:30>")
            val sent = hasTestTag("gcode-sent-line") and hasAnyAncestor(hasTestTag("gcode-line-5"))
            val executing = hasTestTag("gcode-executing-line") and hasAnyAncestor(hasTestTag("gcode-line-3"))
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(sent and hasText("↳"), useUnmergedTree = true).fetchSemanticsNodes().size == 1 &&
                    onAllNodes(executing and hasText("•"), useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            onNodeWithTag("gcode-line-6").assertIsSelected()
            onNodeWithTag("gcode-input").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(program[5])),
            )
            onNodeWithTag("keypad-NUM+-8").performClick()
            onNodeWithTag("keypad-NUM+-15").performClick()
            onNodeWithTag("gcode-line-6").assertTextContains("N60 G1 Y0")
            screenshot("mdi-running")

            val formatKey = onNodeWithTag("softkey-R5")
            val executingKey = onNodeWithTag("softkey-R6")
            val sentKey = onNodeWithTag("softkey-R7")
            formatKey.performClick()
            executingKey.performClick()
            sentKey.performClick()
            onNodeWithText("Executing line 3", substring = true).assertExists()
            screenshot("mdi-running-display-off")
            formatKey.performClick()
            executingKey.performClick()
            sentKey.performClick()

            fixture.report("<Run|Ln:50>")
            val executingOnSentRow = hasTestTag("gcode-executing-line") and hasAnyAncestor(hasTestTag("gcode-line-5"))
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(executingOnSentRow, useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            onNode(executingOnSentRow, useUnmergedTree = true).assertTextEquals("•")
            onNode(sent, useUnmergedTree = true).assertDoesNotExist()
            screenshot("mdi-running-markers-overlap")

            onNodeWithTag("softkey-F2").performClick()
            assertEquals("!", fixture.nextCommand())
            fixture.report("<Hold:1|Ln:50>")
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
            for (line in program.drop(5)) {
                assertEquals("$line\n", fixture.nextCommand())
                fixture.receive("ok")
            }
            waitUntil(timeoutMillis = 5_000) {
                operation.state.value.phase == Operation.Phase.RUNNING &&
                    operation.state.value.lastSentLineNumber == program.size &&
                    operation.state.value.reply == BufferState.Reply.Ok
            }
            onNodeWithText("Active line unknown", substring = true).assertExists()
            onNodeWithTag("gcode-executing-line", useUnmergedTree = true).assertDoesNotExist()
            start.assertIsNotEnabled()
            fixture.report("<Idle>")
            waitUntilExactlyOneExists(hasText("Completed"), timeoutMillis = 5_000)
            val finalSent = hasTestTag("gcode-sent-line") and hasAnyAncestor(hasTestTag("gcode-line-8"))
            onNode(finalSent, useUnmergedTree = true).assertTextEquals("↳")
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
            waitUntilExactlyOneExists(hasTestTag("softkey-F1") and isNotEnabled(), timeoutMillis = 5_000)
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
        val input = onNodeWithTag("gcode-input")
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
