package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScreenStackTest {
    private class StubScreen : Screen {
        @Composable override fun Content(stack: ScreenStack) {}
        override fun equals(other: Any?) = other is StubScreen
        override fun hashCode() = 0
    }

    @Test fun rootCannotBeRemovedAndStackCannotBecomeEmpty() {
        val root = StubScreen()
        val stack = ScreenStack(root)
        repeat(3) { assertNull(stack.pop()) }
        assertFalse(stack.dismiss(root))
        assertSame(root, stack.top)
        assertEquals(1, stack.size)
    }

    @Test fun orderAndRestorationUseIdentityEvenForEqualScreens() {
        val root = StubScreen()
        val first = StubScreen()
        val second = StubScreen()
        val stack = ScreenStack(root)
        stack.push(first)
        stack.push(second)
        stack.screens.zip(listOf(root, first, second)).forEach { (actual, expected) ->
            assertSame(expected, actual)
        }
        assertFalse(stack.dismiss(first))
        assertSame(second, stack.pop())
        assertSame(first, stack.top)
        assertSame(first, stack.pop())
        assertSame(root, stack.top)
        stack.push(first)
        assertSame(first, stack.top)
    }

    @Test fun duplicateInstanceIsRejectedWithoutChangingStack() {
        val root = StubScreen()
        val stack = ScreenStack(root)
        assertFailsWith<IllegalArgumentException> { stack.push(root) }
        assertEquals(1, stack.size)
    }

    @Test fun editorRetainsDraftOnCancellationAndReceivesSelection() {
        val editor = EditorScreen()
        val stack = ScreenStack(editor)
        editor.draft = "Unfinished edit"
        editor.openFileExplorer(stack)
        val cancelled = stack.top as FileExplorerScreen
        cancelled.cancel(stack)
        assertSame(editor, stack.top)
        assertEquals("Unfinished edit", editor.draft)
        assertNull(editor.selectedFile)
        editor.openFileExplorer(stack)
        val chooser = stack.top as FileExplorerScreen
        chooser.select(stack, chooser.files.first())
        assertSame(editor, stack.top)
        assertEquals("example-a.nc", editor.selectedFile)
        assertEquals(chooser.files.first().contents, editor.draft)
        // A stale callback must neither change the editor nor pop a newer screen.
        editor.openFileExplorer(stack)
        val newer = stack.top
        chooser.select(stack, chooser.files.last())
        assertSame(newer, stack.top)
        assertEquals("example-a.nc", editor.selectedFile)
    }

    @Test fun resultIsDeliveredOnceAfterRestoringUnderlyingScreen() {
        val root = StubScreen()
        val stack = ScreenStack(root)
        var count = 0
        val explorer = FileExplorerScreen {
            assertSame(root, stack.top)
            assertNull(it)
            count++
        }
        stack.push(explorer)
        explorer.cancel(stack)
        explorer.cancel(stack)
        assertEquals(1, count)
        assertTrue(stack.screens.single() === root)
    }
}
