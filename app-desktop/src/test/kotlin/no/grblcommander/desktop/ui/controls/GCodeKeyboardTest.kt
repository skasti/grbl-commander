package no.grblcommander.desktop.ui.controls

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class GCodeKeyboardTest {
    @Test fun `rapid pointer sequence emits each digit once without waiting between gestures`() = runComposeUiTest {
        val emitted = mutableListOf<String>()
        setContent { MaterialTheme { GCodeKeyboard({ if (it is KeypadAction.Insert) emitted += it.text }, Modifier.fillMaxSize()) } }
        val positions = listOf(8, 9, 10, 4, 5, 6, 0, 1, 2).map {
            onNodeWithTag("keypad-123-$it").fetchSemanticsNode().boundsInRoot.center
        }
        onRoot().performMouseInput {
            moveTo(positions.first())
            positions.forEach { position ->
                animateMoveTo(position, durationMillis = 250)
                press(); advanceEventTime(25)
                moveTo(position + androidx.compose.ui.geometry.Offset(1f, 0f))
                advanceEventTime(25); release()
            }
        }
        runOnIdle { assertEquals((1..9).map(Int::toString), emitted) }
    }

    @Test fun `keys and independent page controls preserve focus selection and geometry`() = runSkikoComposeUiTest(size = androidx.compose.ui.geometry.Size(1024f, 768f)) {
        var value by mutableStateOf(TextFieldValue("G0 X12", TextRange(6, 4)))
        var enterCount = 0
        var enter: () -> Unit by mutableStateOf({ enterCount++ })
        val letters = KeyClusterState(GCodeKeymaps.letters)
        val numbers = KeyClusterState(GCodeKeymaps.numbers)
        setContent {
            MaterialTheme {
                Column {
                    BasicTextField(value, { value = it }, Modifier.testTag("input"))
                    GCodeKeyboard({ action -> editWithKeypad(value, action, { value = it }, { enter() }) },
                        Modifier.heightIn(max = 740.dp), letters, numbers)
                }
            }
        }
        onNodeWithTag("input").performClick().assertIsFocused()
        // Set a reversed selection after the focus click, as an input owner/selection gesture would.
        runOnIdle { value = value.copy(selection = TextRange(6, 4)) }
        onNodeWithTag("keypad-123-0").performClick() // 7 replaces selection.
        runOnIdle { assertEquals(TextFieldValue("G0 X7", TextRange(5)), value) }
        onNodeWithTag("input").assertIsFocused()
        onNodeWithTag("keypad-123-3").performClick()
        runOnIdle { assertEquals("G0 X", value.text) }
        val before = onNodeWithTag("keypad-ABC-0").fetchSemanticsNode().boundsInRoot
        val number = onNodeWithTag("keypad-123-0").fetchSemanticsNode().boundsInRoot
        assertEquals(before.width, before.height, .1f)
        assertTrue(before.width in 44f..72.1f)
        val nextNumber = onNodeWithTag("keypad-123-1").fetchSemanticsNode().boundsInRoot
        val third = onNodeWithTag("keypad-123-2").fetchSemanticsNode().boundsInRoot
        val fourth = onNodeWithTag("keypad-123-3").fetchSemanticsNode().boundsInRoot
        assertEquals(number.width * .3f, nextNumber.left - number.right, 1.1f)
        assertEquals(number.width * .6f, fourth.left - third.right, 1.1f)
        onNodeWithTag("keypad-ABC-next").performClick()
        onNodeWithText("ABC 2/2 ▸").assertExists()
        onNodeWithText("123 1/2 ▸").assertExists()
        assertEquals(before, onNodeWithTag("keypad-ABC-0").fetchSemanticsNode().boundsInRoot)
        assertEquals(number, onNodeWithTag("keypad-123-0").fetchSemanticsNode().boundsInRoot)
        onNodeWithTag("input").assertIsFocused()
        onNodeWithTag("keypad-ABC-0").performClick() // B
        onNodeWithTag("keypad-123-next").performClick()
        onNodeWithTag("keypad-123-13").performClick() // Space.
        onNodeWithTag("keypad-123-15").performClick()
        runOnIdle { assertEquals("G0 XB ", value.text); assertEquals(1, enterCount); enter = { enterCount += 10 } }
        onNodeWithTag("keypad-123-15").performClick()
        runOnIdle { assertEquals(11, enterCount) }
        onNodeWithTag("input").assertIsFocused().performTextInput("Z")
        runOnIdle { assertEquals("G0 XB Z", value.text) }
        onNodeWithTag("keypad-ABC-next").performClick()
        onNodeWithText("ABC 1/2 ▸").assertExists()
        onNodeWithText("123 2/2 ▸").assertExists()
        onNodeWithTag("keypad-123-next").performClick()
        onNodeWithText("123 1/2 ▸").assertExists()
    }

    @Test fun `undersized viewport scrolls instead of shrinking the keys`() = runComposeUiTest {
        var enters = 0
        setContent {
            MaterialTheme {
                GCodeKeyboard({ if (it == KeypadAction.Enter) enters++ }, Modifier.size(200.dp, 300.dp).testTag("keypad-panel"))
            }
        }
        val first = onNodeWithTag("keypad-ABC-0").fetchSemanticsNode().boundsInRoot
        assertEquals(44f, first.width, .1f)
        assertEquals(44f, first.height, .1f)
        val panel = onNodeWithTag("keypad-panel").fetchSemanticsNode().boundsInRoot
        assertEquals(30f, first.top - panel.top, .1f)
        assertTrue(first.left - panel.left >= 30f)
        onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 1000f) }
        onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(1000f, 0f) }
        val enter = onNodeWithTag("keypad-123-15").assertIsDisplayed()
        val bounds = enter.fetchSemanticsNode().boundsInRoot
        assertEquals(30f, panel.right - bounds.right, .1f)
        val page = onNodeWithTag("keypad-123-next").fetchSemanticsNode().boundsInRoot
        assertEquals(30f, panel.bottom - page.bottom, .1f)
        enter.performClick()
        runOnIdle { assertEquals(1, enters) }
    }

    @Test fun `custom dimensions labels actions gaps and single page use the same renderer`() = runComposeUiTest {
        val state = KeyClusterState(KeypadLayout("Custom", rows = 2, columns = 3,
            pages = listOf(mapOf(5 to KeypadKey("Macro", KeypadAction.Insert("G65 P1"))))))
        var action: KeypadAction? = null
        setContent { MaterialTheme { KeyCluster(state, 50.dp, { action = it }) } }
        onNodeWithText("Custom 1/1").assertExists()
        onNodeWithTag("keypad-Custom-next").assertIsNotEnabled()
        onNodeWithTag("keypad-Custom-0").assertDoesNotExist()
        onNodeWithText("Macro").performClick()
        runOnIdle { assertEquals(KeypadAction.Insert("G65 P1"), action) }
    }
}
