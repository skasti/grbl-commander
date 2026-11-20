package no.grblcommander.desktop.mode.program

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EditorNavigationTest {
    @Test fun editorExampleRestoresDraftOnCancelAndLoadsSelectedFixture() = runComposeUiTest {
        val editor = Editor()
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
        onNodeWithText("Select file").performTouchInput { click() }
        onNodeWithText("example-b.nc").assertExists()
        runOnIdle {
            assertSame(editor, stack.top)
            assertEquals("; Example B\n; Preview only", editor.draft)
        }
    }

    @Test fun independentComposeHostsResolveTheirOwnExplorer() = runComposeUiTest {
        val left = Editor()
        val right = Editor()
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
            assertTrue(leftStack.top is FileExplorer)
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
        onNodeWithText("Select file").performTouchInput { click() }
        runOnIdle {
            assertSame(left, leftStack.top)
            assertSame(right, rightStack.top)
            assertEquals("example-b.nc", right.selectedFile)
            assertEquals(null, left.selectedFile)
        }
    }

    @Test fun popFromUiDeliversCancellationAndRestoresEditableScreen() = runComposeUiTest {
        val editor = Editor()
        val stack = ScreenStack(editor)
        setContent { ScreenStackHost(stack) }
        onNodeWithText("Editor draft").performTextReplacement("Keep this")
        onNodeWithText("Open example file").performTouchInput { click() }
        runOnIdle { assertTrue(stack.pop() is FileExplorer) }
        onNodeWithText("Keep this").assertExists()
        runOnIdle {
            assertSame(editor, stack.top)
            assertEquals(null, editor.selectedFile)
            assertEquals("Keep this", editor.draft)
        }
    }
}
