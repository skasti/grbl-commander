package no.grblcommander.desktop.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ScreenStackHostTest {
    @Test fun retainsCompositionAndBlocksCoveredPointerAndKeyboardInput() = runComposeUiTest {
        var disposed = 0
        var clicks = 0
        var text = "draft"
        var editorFocused = false
        val root = object : Screen {
            @Composable override fun Content(stack: ScreenStack) {
                var count by remember { mutableStateOf(0) }
                var value by remember { mutableStateOf(text) }
                DisposableEffect(Unit) { onDispose { disposed++ } }
                Column(Modifier.fillMaxSize()) {
                    Button(onClick = { count++ }) { Text("Count $count") }
                    BasicTextField(value, { value = it; text = it }, Modifier.testTag("editor").onFocusChanged { editorFocused = it.isFocused })
                    Box(Modifier.size(100.dp).testTag("covered-button").clickable { clicks++ })
                }
            }
        }
        val overlay = object : Screen {
            @Composable override fun Content(stack: ScreenStack) {
                Button(onClick = { stack.dismiss(this) }) { Text("Dismiss") }
            }
        }
        val stack = ScreenStack(root)
        setContent { ScreenStackHost(stack, Modifier.testTag("host")) }
        onNodeWithText("Count 0").performTouchInput { click() }
        onNodeWithTag("editor").performClick()
        runOnIdle { assertTrue(editorFocused) }
        val target = onNodeWithTag("covered-button").fetchSemanticsNode().boundsInRoot.center
        runOnIdle { stack.push(overlay) }
        onNodeWithText("Dismiss").assertExists()
        onNodeWithTag("editor").assertDoesNotExist()
        // Click outside the small overlay panel, where a covered button is drawn.
        onNodeWithTag("host").performTouchInput { click(target) }
        onNodeWithTag("host").performMouseInput { click(target) }
        onNodeWithTag("host").performKeyInput { pressKey(Key.A) }
        repeat(4) {
            onNodeWithTag("host").performKeyInput { pressKey(Key.Tab) }
            runOnIdle { assertFalse(editorFocused) }
            onNodeWithTag("host").performKeyInput { pressKey(Key.B) }
        }
        runOnIdle {
            assertEquals(0, clicks)
            assertEquals("draft", text)
            assertEquals(0, disposed)
        }
        onNodeWithText("Dismiss").performTouchInput { click() }
        onNodeWithText("Count 1").assertExists()
        onNodeWithTag("editor").assertExists()
        runOnIdle { assertSame(root, stack.top); assertEquals(0, disposed) }
        onNodeWithTag("covered-button").performTouchInput { click() }
        runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun editorExampleRestoresDraftOnCancelAndLoadsSelectedFixture() = runComposeUiTest {
        val editor = EditorScreen()
        val stack = ScreenStack(editor)
        setContent { ScreenStackHost(stack) }
        onNodeWithText("Editor draft").performTextReplacement("Unfinished program")
        onNodeWithText("Open example file").performTouchInput { click() }
        onNodeWithText("Choose an example file").assertExists()
        onNodeWithText("Cancel").performTouchInput { click() }
        onNodeWithText("Unfinished program").assertExists()
        runOnIdle { assertSame(editor, stack.top) }
        onNodeWithText("Open example file").performTouchInput { click() }
        onNodeWithText("example-b.nc").performTouchInput { click() }
        onNodeWithText("example-b.nc").assertExists()
        runOnIdle {
            assertSame(editor, stack.top)
            assertEquals("; Example B\n; Preview only", editor.draft)
        }
    }

    @Test fun independentComposeHostsResolveTheirOwnExplorer() = runComposeUiTest {
        val left = EditorScreen()
        val right = EditorScreen()
        val leftStack = ScreenStack(left)
        val rightStack = ScreenStack(right)
        setContent {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { ScreenStackHost(leftStack) }
                Box(Modifier.weight(1f)) { ScreenStackHost(rightStack) }
            }
        }
        onAllNodesWithText("Open example file")[0].performTouchInput { click() }
        runOnIdle {
            assertTrue(leftStack.top is FileExplorerScreen)
            assertSame(right, rightStack.top)
        }
        onNodeWithText("Cancel").performTouchInput { click() }
        runOnIdle {
            assertSame(left, leftStack.top)
            assertSame(right, rightStack.top)
            assertEquals(null, left.selectedFile)
        }
        onAllNodesWithText("Open example file")[1].performTouchInput { click() }
        onNodeWithText("example-b.nc").performTouchInput { click() }
        runOnIdle {
            assertSame(left, leftStack.top)
            assertSame(right, rightStack.top)
            assertEquals("example-b.nc", right.selectedFile)
            assertEquals(null, left.selectedFile)
        }
    }

    @Test fun popFromUiDeliversCancellationAndRestoresEditableScreen() = runComposeUiTest {
        val editor = EditorScreen()
        val stack = ScreenStack(editor)
        setContent { ScreenStackHost(stack) }
        onNodeWithText("Editor draft").performTextReplacement("Keep this")
        onNodeWithText("Open example file").performTouchInput { click() }
        runOnIdle { assertTrue(stack.pop() is FileExplorerScreen) }
        onNodeWithText("Keep this").assertExists()
        runOnIdle {
            assertSame(editor, stack.top)
            assertEquals(null, editor.selectedFile)
            assertEquals("Keep this", editor.draft)
        }
    }

    @Test fun nestedOverlaysRestoreInOrderAndPublishActiveSemantics() = runComposeUiTest {
        fun screen(label: String) = object : Screen {
            @Composable override fun Content(stack: ScreenStack) { Text(label) }
        }
        val root = screen("root")
        val middle = screen("middle")
        val top = screen("top")
        val stack = ScreenStack(root)
        setContent { ScreenStackHost(stack) }
        runOnIdle { stack.push(middle) }
        onNodeWithText("middle").assertExists()
        runOnIdle { stack.push(top) }
        onNodeWithText("top").assertExists()
        onNodeWithText("middle").assertDoesNotExist()
        onNodeWithText("root").assertDoesNotExist()
        runOnIdle { stack.pop() }
        onNodeWithText("middle").assertExists()
        runOnIdle { stack.pop(); stack.pop() }
        onNodeWithText("root").assertExists()
    }
}
