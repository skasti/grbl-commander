package no.grblcommander.desktop.ui.components.controls.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.StringSelection
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
        clipboardText: String? = null,
        block: ComposeUiTest.(GCodeEditor) -> Unit,
    ) = runComposeUiTest {
        val editor = GCodeEditor(initialSource)
        val clipboard = object : Clipboard {
            override val nativeClipboard = java.awt.datatransfer.Clipboard("G-code editor test").apply {
                clipboardText?.let { setContents(StringSelection(it), null) }
            }
            override suspend fun getClipEntry() = nativeClipboard.getContents(null)?.let(::ClipEntry)
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                nativeClipboard.setContents(clipEntry?.nativeClipEntry as? java.awt.datatransfer.Transferable
                    ?: StringSelection(""), null)
            }
        }
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                MaterialTheme {
                    Row(Modifier.requiredSize(880.dp, 440.dp)) {
                        editor.Render(Modifier.weight(1f).fillMaxHeight(), decorations())
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
        onNodeWithText("Use one ASCII line without embedded real-time commands").assertExists()
        runOnIdle { editor.softKeys.nextLine.action?.invoke() }
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        runOnIdle { editor.softKeys.previousLine.action?.invoke() }
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("N20 G0 X1")))
    }

    @Test fun `a custom validator can replace or extend GRBL framing validation`() {
        val permissive = GCodeEditor(validate = { null })
        permissive.edit(TextFieldValue("(Is this line required?)"))
        permissive.commit()
        assertEquals(listOf("(Is this line required?)"), permissive.source)

        val extended = GCodeEditor(validate = { line ->
            GCodeEditor.validateGrblLine(line) ?: if (line == "M2") "Program end is reserved" else null
        })
        extended.edit(TextFieldValue("?"))
        extended.commit()
        assertEquals("Use one ASCII line without embedded real-time commands", extended.error)
        extended.edit(TextFieldValue("M2"))
        extended.commit()
        assertEquals("Program end is reserved", extended.error)
        assertTrue(extended.source.isEmpty())
        extended.edit(TextFieldValue("G90"))
        extended.commit()
        assertEquals(listOf("G90"), extended.source)
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

    @Test fun `read-only mode expands the list and restores drafts and carets`() = editorTest(
        initialSource = List(40) { "X${it + 1}" },
        clipboardText = "G X1",
    ) { editor ->
        val input = onNodeWithTag("gcode-input")
        val buffer = onNodeWithTag("gcode-buffer")
        val editableHeight = buffer.fetchSemanticsNode().boundsInRoot.height
        input.paste()
        input.performKeyInput { pressKey(Key.Home); pressKey(Key.DirectionRight) }
        onNodeWithTag("gcode-line-1").performClick().assertIsSelected()
        input.performTextReplacement("G1 X20")
        input.performKeyInput { pressKey(Key.Home); pressKey(Key.DirectionRight) }

        runOnIdle { editor.readOnly = true }
        input.assertDoesNotExist()
        assertTrue(buffer.fetchSemanticsNode().boundsInRoot.height > editableHeight)
        onNodeWithTag("gcode-line-1").assertIsSelected()
        onNodeWithTag("keypad-CODE-2").performClick()
        onNodeWithTag("keypad-NUM+-15").performClick()
        runOnIdle {
            editor.onKeypadAction(KeypadAction.Backspace)
            assertEquals("X1", editor.source.first())
        }

        runOnIdle { editor.readOnly = false }
        onNodeWithTag("gcode-line-1").assertIsSelected()
        input.assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("G1 X20")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1)))
        onNodeWithTag("gcode-line-1").performClick().assertIsNotSelected()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("G X1")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1)))
        assertEquals(editableHeight, buffer.fetchSemanticsNode().boundsInRoot.height)
    }

    @Test fun `read-only inspection supports scrolling navigation and source replacement`() = editorTest(
        initialSource = List(40) { "X${it + 1}" },
    ) { editor ->
        val input = onNodeWithTag("gcode-input")
        runOnIdle { editor.readOnly = true }
        input.assertDoesNotExist()
        val otherInput = onNodeWithTag("other-input").performClick().assertIsFocused()

        onNodeWithTag("gcode-buffer").performScrollToIndex(14)
        onNodeWithTag("gcode-line-15").assertIsDisplayed().performClick().assertIsSelected()
        runOnIdle { editor.softKeys.previousLine.action?.invoke() }
        onNodeWithTag("gcode-line-14").assertIsDisplayed().assertIsSelected()
        runOnIdle { editor.softKeys.nextLine.action?.invoke() }
        onNodeWithTag("gcode-line-15").assertIsDisplayed().assertIsSelected()
        otherInput.assertIsFocused()

        val replacement = mutableListOf("  (header) ", "#2 = 3", "", "%", "!")
        runOnIdle {
            editor.replaceSource(replacement)
            replacement.clear()
            assertEquals(listOf("  (header) ", "#2 = 3", "", "%", "!"), editor.source)
            assertNull(editor.selectedLine)
        }
        onNodeWithTag("gcode-line-1").assertIsDisplayed().assertIsNotSelected()
        input.assertDoesNotExist()

        runOnIdle { editor.readOnly = false }
        input.assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        input.performTextInput("M2")
        input.performKeyInput { pressKey(Key.NumPadEnter) }
        input.performTextInput("G90")
        onNodeWithTag("keypad-NUM+-15").performClick()
        runOnIdle { assertEquals(listOf("  (header) ", "#2 = 3", "", "%", "!", "M2", "G90"), editor.source) }
    }

    private fun SemanticsNodeInteraction.paste() = performKeyInput {
        keyDown(Key.CtrlLeft)
        pressKey(Key.V)
        keyUp(Key.CtrlLeft)
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

    @Test fun `loading starts at the top and navigation and append scroll on demand`() = editorTest(
        initialSource = List(40) { "X$it" },
    ) { editor ->
        onNodeWithTag("gcode-line-1").assertIsDisplayed()
        runOnIdle { editor.selectLine(25) }
        onNodeWithTag("gcode-line-25").assertIsDisplayed().assertIsSelected()
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

        runOnIdle { editor.replaceSource(List(40) { "Y$it" }) }
        onNodeWithTag("gcode-line-1").assertIsDisplayed().assertTextContains("Y0")
        input.performTextInput("M2")
        input.performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("gcode-line-41").assertIsDisplayed().assertTextContains("M2")
    }

    @Test fun `switching editor instances retains independent viewports selections drafts and carets`() = runComposeUiTest {
        val first = GCodeEditor(List(60) { "X$it" })
        val second = GCodeEditor(List(60) { "Y$it" })
        val active = mutableStateOf<GCodeEditor?>(first)
        setContent {
            MaterialTheme {
                active.value?.Render(Modifier.requiredSize(400.dp, 300.dp))
            }
        }
        val input = onNodeWithTag("gcode-input")
        val buffer = onNodeWithTag("gcode-buffer")

        runOnIdle { first.selectLine(12) }
        input.performTextReplacement("G1 X12")
        input.performKeyInput { pressKey(Key.Home); pressKey(Key.DirectionRight) }
        buffer.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 9f) }
        runOnIdle {
            assertEquals(11, first.listState.firstVisibleItemIndex)
            assertEquals(9, first.listState.firstVisibleItemScrollOffset)
            active.value = second
        }

        onNodeWithTag("gcode-line-1").assertIsDisplayed()
        runOnIdle { second.selectLine(32) }
        input.performTextReplacement("G0 Y32")
        input.performKeyInput { pressKey(Key.MoveEnd); pressKey(Key.DirectionLeft) }
        buffer.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 15f) }
        runOnIdle {
            assertEquals(31, second.listState.firstVisibleItemIndex)
            assertEquals(15, second.listState.firstVisibleItemScrollOffset)
            active.value = first
        }

        onNodeWithTag("gcode-line-12").assertIsDisplayed().assertIsSelected()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("G1 X12")))
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1)))
        runOnIdle {
            assertEquals(11, first.listState.firstVisibleItemIndex)
            assertEquals(9, first.listState.firstVisibleItemScrollOffset)
            active.value = null
        }
        buffer.assertDoesNotExist()
        runOnIdle { active.value = second }

        onNodeWithTag("gcode-line-32").assertIsDisplayed().assertIsSelected()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("G0 Y32")))
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(5)))
        runOnIdle {
            assertEquals(31, second.listState.firstVisibleItemIndex)
            assertEquals(15, second.listState.firstVisibleItemScrollOffset)
        }
    }

    @Test fun `large source stays immutable and renders edits and status lazily`() {
        val rowCount = 100_000
        val decorations = mutableStateOf(GCodeLineDecorations())
        editorTest(decorations = { decorations.value }) { editor ->
            val loaded = MutableList(rowCount) { "G0 X${it + 1}" }
            val original = runOnIdle {
                editor.replaceSource(loaded)
                loaded[0] = "M2"
                editor.source
            }
            assertFalse(original is MutableList<*>)
            assertEquals(rowCount, original.size)
            onNodeWithTag("gcode-line-1").assertIsDisplayed().assertTextContains("G0 X1")
            onNodeWithTag("gcode-line-$rowCount").assertDoesNotExist()

            runOnIdle { decorations.value = GCodeLineDecorations(sentLine = 1) }
            onNodeWithTag("gcode-sent-line", useUnmergedTree = true).assertExists()
            runOnIdle {
                assertSame(original, editor.source)
                editor.selectLine(rowCount)
            }

            val row = onNodeWithTag("gcode-line-$rowCount")
            val input = onNodeWithTag("gcode-input")
            row.assertIsDisplayed().assertIsSelected()
            input.performKeyInput { pressKey(Key.Enter) }
            runOnIdle {
                assertSame(original, editor.source)
                editor.selectLine(rowCount)
            }

            input.performTextReplacement("G1 X25")
            runOnIdle { assertSame(original, editor.source) }
            input.performKeyInput { pressKey(Key.Enter) }
            row.assertTextContains("G1 X25")
            val edited = runOnIdle { editor.source }
            assertNotSame(original, edited)
            assertEquals("G0 X$rowCount", original[rowCount - 1])
            assertEquals("G1 X25", edited[rowCount - 1])

            input.performTextInput("M2")
            input.performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("gcode-line-${rowCount + 1}").assertIsDisplayed().assertTextContains("M2")
            runOnIdle {
                assertEquals(rowCount, edited.size)
                assertEquals(rowCount + 1, editor.source.size)
                editor.replaceSource(edited)
                assertSame(edited, editor.source)
            }
            onNodeWithTag("gcode-line-1").assertIsDisplayed()
        }
    }

    @Test fun `typed default banks remain stable when a consumer composes its own bindings`() {
        val editor = GCodeEditor()
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
