package no.grblcommander.desktop.ui.components.controls.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlin.test.*
import no.grblcommander.desktop.ui.components.controls.KeyCluster
import no.grblcommander.desktop.ui.components.controls.KeyClusterState
import no.grblcommander.desktop.ui.components.controls.KeypadAction
import no.grblcommander.desktop.ui.components.controls.Keymaps
import no.grblcommander.desktop.ui.layout.LocalGridUnit
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.RightSoftKeySlot
import no.grblcommander.desktop.ui.navigation.SoftKeyDefinition

@OptIn(ExperimentalTestApi::class)
class GCodeEditorTest {
    private fun editorTest(
        initialSource: List<String> = emptyList(),
        decorations: () -> GCodeLineDecorations = { GCodeLineDecorations() },
        block: ComposeUiTest.(GCodeEditorState) -> Unit,
    ) = runComposeUiTest {
        val editor = GCodeEditorState(initialSource) { if (it == "!") "Invalid line" else null }
        setContent {
            MaterialTheme {
                Row(Modifier.requiredSize(880.dp, 440.dp)) {
                    GCodeEditor(editor, Modifier.weight(1f).fillMaxHeight(), decorations())
                    Column(Modifier.width(500.dp)) {
                        OutlinedTextField("", {}, Modifier.testTag("other-input"))
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
        block(editor)
    }

    @Test fun `the keypad delivers the first mouse letter without prior hover`() = editorTest { _ ->
        onNodeWithTag("keypad-CODE-2").performMouseInput { click() }
        onNodeWithTag("gcode-input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("G")),
        )
    }

    @Test fun `invalid replacement preserves the saved row and draft`() = editorTest { editor ->
        val input = onNodeWithTag("gcode-input")
        assertFalse(editor.softKeys.previousLine.enabled())
        assertFalse(editor.softKeys.nextLine.enabled())
        input.performTextInput("N20 G0 X1")
        input.performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("gcode-line-1").performClick().assertIsSelected()
        assertFalse(editor.softKeys.previousLine.enabled())
        input.performTextReplacement("!")
        onNodeWithTag("keypad-NUM+-15").performClick()
        onNodeWithTag("gcode-line-1").assertTextContains("N20 G0 X1")
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("!")))
        onNodeWithText("Invalid line").assertExists()
        runOnIdle { editor.softKeys.nextLine.action?.invoke() }
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        runOnIdle { editor.softKeys.previousLine.action?.invoke() }
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("N20 G0 X1")))
    }

    @Test fun `browsing and deselecting return to the unfinished new line and caret`() = editorTest { editor ->
        val input = onNodeWithTag("gcode-input")
        for (line in listOf("G90", "M2")) {
            input.performTextInput(line)
            input.performKeyInput { pressKey(Key.Enter) }
        }
        input.performTextInput("G X1")
        input.performKeyInput { pressKey(Key.Home); pressKey(Key.DirectionRight) }
        runOnIdle { editor.softKeys.previousLine.action?.invoke() }
        runOnIdle { editor.softKeys.previousLine.action?.invoke() }
        runOnIdle { editor.softKeys.nextLine.action?.invoke() }
        runOnIdle { editor.softKeys.nextLine.action?.invoke() }
        input.performTextInput("1")
        onNodeWithTag("gcode-line-1").performClick()
        input.performTextReplacement("G91")
        input.performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("gcode-line-1").assertTextContains("G91").assertIsNotSelected()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("G1 X1")))
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2)))
        onNodeWithTag("gcode-line-2").performClick()
        onNodeWithTag("gcode-line-1").performClick().performClick()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("G1 X1")))
        input.performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("gcode-line-3").assertTextContains("G1 X1")
        onNodeWithTag("gcode-line-3").performClick().assertIsSelected()
        input.performTextReplacement("G0 X99")
        onNodeWithTag("gcode-buffer").performTouchInput { click(Offset(center.x, height - 8f)) }
        onNodeWithTag("gcode-line-3").assertTextContains("G1 X1").assertIsNotSelected()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun `read-only inspection blocks edits and supports explicit source replacement`() = editorTest { editor ->
        val original = listOf("G1X10", "", "%", "#1=12  ", "!")
        val input = onNodeWithTag("gcode-input")
        runOnIdle { editor.replaceSource(original) }
        input.performTextInput("unfinished")
        onNodeWithTag("gcode-line-1").performClick()
        input.performTextReplacement("unedited source")
        runOnIdle { editor.readOnly = true }

        input.assertIsFocused().assert(hasSetTextAction().not())
        input.performKeyInput { pressKey(Key.G); pressKey(Key.Enter) }
        onNodeWithTag("keypad-CODE-2").performClick()
        onNodeWithTag("keypad-NUM+-15").performClick()
        input.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("unedited source"),
        ))
        runOnIdle {
            // The same callback handles physical input and paste.
            editor.edit(TextFieldValue("paste"))
            editor.onKeypadAction(KeypadAction.Backspace)
            assertEquals("unedited source", editor.input.text)
            assertEquals(original, editor.source)
        }
        onNodeWithTag("gcode-line-3").performClick().assertIsSelected()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("%")))

        val replacement = mutableListOf("  (header) ", "#2 = 3")
        runOnIdle {
            editor.replaceSource(replacement)
            replacement.clear()
            assertEquals(listOf("  (header) ", "#2 = 3"), editor.source)
            assertNull(editor.selectedLine)
        }
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        runOnIdle { editor.readOnly = false }
        input.performTextInput("M2")
        input.performKeyInput { pressKey(Key.NumPadEnter) }
        input.performTextInput("G90")
        onNodeWithTag("keypad-NUM+-15").performClick()
        runOnIdle { assertEquals(listOf("  (header) ", "#2 = 3", "M2", "G90"), editor.source) }
    }

    @Test fun `formatting is display only and marker toggles share their actions`() {
        val lines = listOf("G1X10", "(keep   spaces)")
        val markers = mutableStateOf(GCodeLineDecorations(executingLine = 1, sentLine = 1))
        editorTest(lines, { markers.value }) { editor ->
            val executing = onNodeWithTag("gcode-executing-line", useUnmergedTree = true)
            val sent = onNodeWithTag("gcode-sent-line", useUnmergedTree = true)
            val row = onNodeWithTag("gcode-line-1")
            row.assertTextContains("G1 X10").performClick().assertIsSelected()
            onNodeWithTag("gcode-line-2").assertTextContains("(keep   spaces)")
            executing.assertTextEquals("•")
            sent.assertDoesNotExist()
            runOnIdle {
                editor.rightKeys.getValue(RightSoftKeySlot.R5).action?.invoke()
                editor.toggleExecutingLine()
                assertFalse(editor.softKeys.format.active())
                assertFalse(editor.softKeys.executing.active())
            }
            row.assertTextContains("G1X10")
            executing.assertDoesNotExist()
            sent.assertTextEquals("↳")
            runOnIdle {
                editor.softKeys.sent.action?.invoke()
                assertFalse(editor.softKeys.sent.active())
                assertEquals(lines, editor.source)
                editor.toggleExecutingLine()
                editor.readOnly = true
                markers.value = GCodeLineDecorations(executingLine = 2, sentLine = 1)
            }
            sent.assertDoesNotExist()
            onNode(
                hasTestTag("gcode-executing-line") and hasAnyAncestor(hasTestTag("gcode-line-2")),
                useUnmergedTree = true,
            ).assertTextEquals("•")
            row.assertIsSelected()
        }
    }

    @Test fun `navigation scrolls and touch input restores focus`() = editorTest(
        initialSource = List(40) { "X$it" },
    ) { editor ->
        onNodeWithTag("gcode-line-40").assertIsDisplayed()
        runOnIdle { editor.selectLine(1) }
        onNodeWithTag("gcode-line-1").assertIsDisplayed().assertIsSelected()
        val input = onNodeWithTag("gcode-input").assertIsFocused()
        onNodeWithTag("other-input").performClick().assertIsFocused()
        onNodeWithTag("keypad-CODE-2").performMouseInput { click() }
        input.assertIsFocused().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("X0G")),
        )
        runOnIdle { editor.selectLine(null) }
        onNodeWithTag("gcode-line-40").assertIsDisplayed()
    }

    @Test fun `typed default banks remain stable when a consumer composes its own bindings`() {
        val editor = GCodeEditorState()
        val previous = editor.softKeys.previousLine
        val next = editor.softKeys.nextLine
        assertEquals(setOf(BottomSoftKeySlot.F5, BottomSoftKeySlot.F6), editor.bottomKeys.keys)
        assertSame(previous, editor.bottomKeys.getValue(BottomSoftKeySlot.F5))
        assertSame(next, editor.bottomKeys.getValue(BottomSoftKeySlot.F6))
        assertEquals(mapOf(
            RightSoftKeySlot.R5 to editor.softKeys.format,
            RightSoftKeySlot.R6 to editor.softKeys.executing,
            RightSoftKeySlot.R7 to editor.softKeys.sent,
        ), editor.rightKeys)
        assertTrue(previous.repeatable && next.repeatable)
        assertTrue(editor.rightKeys.values.all { it.active() && !it.repeatable })

        val machineAction = SoftKeyDefinition("Cycle Start")
        val bindings = editor.bottomKeys + mapOf(
            BottomSoftKeySlot.F1 to machineAction,
            BottomSoftKeySlot.F5 to machineAction,
            BottomSoftKeySlot.F8 to previous,
        ) - BottomSoftKeySlot.F6
        assertEquals(setOf(BottomSoftKeySlot.F1, BottomSoftKeySlot.F5, BottomSoftKeySlot.F8), bindings.keys)
        assertSame(previous, editor.bottomKeys.getValue(BottomSoftKeySlot.F5))
        assertSame(next, editor.bottomKeys.getValue(BottomSoftKeySlot.F6))
        assertFalse(previous.enabled())
        assertFalse(next.enabled())
        editor.replaceSource(listOf("G90", "M2"))
        editor.readOnly = true
        assertTrue(previous.enabled())
        bindings.getValue(BottomSoftKeySlot.F8).action?.invoke()
        assertEquals(2, editor.selectedLine)
        editor.previousLine()
        assertEquals(1, editor.selectedLine)
        assertFalse(previous.enabled())
        assertTrue(next.enabled())
        next.action?.invoke()
        editor.nextLine()
        assertNull(editor.selectedLine)
        assertFalse(next.enabled())
    }
}
