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
import no.grblcommander.desktop.ui.layout.LocalGridUnit
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class GCodeKeyboardTest {
    @Test fun `rapid pointer sequence emits each digit once without waiting between gestures`() = runComposeUiTest {
        val emitted = mutableListOf<String>()
        setContent { MaterialTheme { GCodeKeyboard({ if (it is KeypadAction.Insert) emitted += it.text }, Modifier.fillMaxSize()) } }
        val positions = listOf(8, 9, 10, 4, 5, 6, 0, 1, 2).map {
            onNodeWithTag("keypad-NUM+-$it").fetchSemanticsNode().boundsInRoot.center
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
        onNodeWithTag("keypad-NUM+-0").performClick() // 7 replaces selection.
        runOnIdle { assertEquals(TextFieldValue("G0 X7", TextRange(5)), value) }
        onNodeWithTag("input").assertIsFocused()
        onNodeWithTag("keypad-NUM+-3").performClick()
        runOnIdle { assertEquals("G0 X", value.text) }
        val before = onNodeWithTag("keypad-CODE-0").fetchSemanticsNode().boundsInRoot
        val number = onNodeWithTag("keypad-NUM+-0").fetchSemanticsNode().boundsInRoot
        assertEquals(before.width, before.height, .1f)
        assertTrue(before.width in 44f..72.1f)
        val nextNumber = onNodeWithTag("keypad-NUM+-1").fetchSemanticsNode().boundsInRoot
        val third = onNodeWithTag("keypad-NUM+-2").fetchSemanticsNode().boundsInRoot
        val fourth = onNodeWithTag("keypad-NUM+-3").fetchSemanticsNode().boundsInRoot
        assertEquals(number.width * .3f, nextNumber.left - number.right, 1.1f)
        assertEquals(number.width * .6f, fourth.left - third.right, 1.1f)
        onNodeWithTag("keypad-CODE-next").performClick()
        onNodeWithText("CODE 2/2 ▸").assertExists()
        onNodeWithText("NUM+ 1/2 ▸").assertExists()
        assertEquals(before, onNodeWithTag("keypad-CODE-0").fetchSemanticsNode().boundsInRoot)
        assertEquals(number, onNodeWithTag("keypad-NUM+-0").fetchSemanticsNode().boundsInRoot)
        onNodeWithTag("input").assertIsFocused()
        onNodeWithTag("keypad-CODE-0").performClick() // B
        onNodeWithTag("keypad-NUM+-next").performClick()
        onNodeWithTag("keypad-NUM+-13").performClick() // Space.
        onNodeWithTag("keypad-NUM+-15").performClick()
        runOnIdle { assertEquals("G0 XB ", value.text); assertEquals(1, enterCount); enter = { enterCount += 10 } }
        onNodeWithTag("keypad-NUM+-15").performClick()
        runOnIdle { assertEquals(11, enterCount) }
        onNodeWithTag("input").assertIsFocused().performTextInput("Z")
        runOnIdle { assertEquals("G0 XB Z", value.text) }
        onNodeWithTag("keypad-CODE-next").performClick()
        onNodeWithText("CODE 1/2 ▸").assertExists()
        onNodeWithText("NUM+ 2/2 ▸").assertExists()
        onNodeWithTag("keypad-NUM+-next").performClick()
        onNodeWithText("NUM+ 1/2 ▸").assertExists()
    }

    @Test fun `margin compacts only on the constrained axis before scrolling`() = runComposeUiTest {
        var height by mutableStateOf(620.dp)
        setContent {
            MaterialTheme {
                GCodeKeyboard({}, Modifier.size(320.dp, height).testTag("keypad-panel"))
            }
        }
        fun bounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertEquals(30f, bounds("keypad-panel").bottom - bounds("keypad-NUM+-next").bottom, .1f)
        runOnIdle { height = 532.dp }
        val panel = bounds("keypad-panel")
        val page = bounds("keypad-NUM+-next")
        val first = bounds("keypad-CODE-0")
        assertEquals(7f, first.top - panel.top, .1f)
        assertEquals(7f, panel.bottom - page.bottom, .1f)
        assertTrue(panel.right - page.right >= 30f)
        assertEquals(44f, first.width, .1f)
        onNodeWithTag("keypad-NUM+-next").assertIsDisplayed()
    }

    @Test fun `undersized viewport scrolls instead of shrinking the keys`() = runComposeUiTest {
        var enters = 0
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalGridUnit provides 52.dp) {
                    GCodeKeyboard({ if (it == KeypadAction.Enter) enters++ }, Modifier.size(200.dp, 300.dp))
                }
            }
        }
        val first = onNodeWithTag("keypad-CODE-0").fetchSemanticsNode().boundsInRoot
        assertEquals(52f, first.width, .1f)
        assertEquals(52f, first.height, .1f)
        onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 1000f) }
        onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(1000f, 0f) }
        onNodeWithTag("keypad-NUM+-15").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, enters) }
    }

    @Test fun `an instance override leaves the shared unit and proportional margins intact`() = runComposeUiTest {
        fun cluster(name: String) = KeyClusterState(KeypadLayout(name, rows = 1, columns = 1,
            pages = listOf(mapOf(0 to KeypadKey("1", KeypadAction.Insert("1"))))))
        val first = cluster("Default")
        val second = cluster("Override")
        val third = cluster("Direct")
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalGridUnit provides 61.dp) {
                    Row {
                        KeypadPanel(listOf(first), {})
                        GCodeKeyboard({}, letters = second, numbers = cluster("OverrideNumbers"), keySize = 50.dp)
                        Box(Modifier.testTag("margin").padding(start = LocalGridUnit.current * .5f)) {
                            KeyCluster(third, {})
                        }
                    }
                }
            }
        }
        fun bounds(tag: String) = onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val direct = bounds("keypad-Direct-0")
        for ((tag, expected) in listOf("Default" to 61.dp, "Override" to 50.dp, "OverrideNumbers" to 50.dp, "Direct" to 61.dp)) {
            val key = bounds("keypad-$tag-0")
            assertEquals(expected, key.right - key.left)
            assertEquals(expected, key.bottom - key.top)
        }
        assertEquals(31.dp, direct.left - bounds("margin").left)
    }

    @Test fun `custom dimensions labels actions gaps and single page use the same renderer`() = runComposeUiTest {
        val state = KeyClusterState(KeypadLayout("Custom", rows = 2, columns = 3,
            pages = listOf(mapOf(5 to KeypadKey("Macro", KeypadAction.Insert("G65 P1"))))))
        var action: KeypadAction? = null
        setContent { MaterialTheme { KeyCluster(state, { action = it }, Modifier.testTag("custom-keypad"), keySize = 50.dp) } }
        onNodeWithText("Custom 1/1").assertDoesNotExist()
        onNodeWithTag("keypad-Custom-next").assertDoesNotExist()
        val keypad = onNodeWithTag("custom-keypad").fetchSemanticsNode().boundsInRoot
        val lastKey = onNodeWithTag("keypad-Custom-5").fetchSemanticsNode().boundsInRoot
        assertEquals(keypad.bottom, lastKey.bottom, .1f)
        onNodeWithTag("keypad-Custom-0").assertDoesNotExist()
        onNodeWithText("Macro").performClick()
        runOnIdle { assertEquals(KeypadAction.Insert("G65 P1"), action) }
    }
}
