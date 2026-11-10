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

    private fun opener(stack: ScreenStack) =
        FileExplorer(ScreenNavigationContext(stack, stack.entries.last()))

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

    @Test fun explicitSelectionRestoresTheSameEditorAndCannotDeliverTwice() {
        val editor = EditorScreen()
        val stack = ScreenStack(editor)
        editor.draft = "Unfinished edit"
        val received = mutableListOf<FileExplorerResult>()
        val explorer = opener(stack)
        explorer.open(initialDirectory = "/programs", onResult = received::add)
        val chooser = stack.top as FileExplorerScreen
        assertEquals("/programs", chooser.initialDirectory)
        assertTrue(chooser.select(chooser.files.first()))
        assertSame(editor, stack.top)
        assertEquals("Unfinished edit", editor.draft)
        assertEquals(listOf(FileExplorerResult.Selected("example-a.nc")), received)
        assertFalse(chooser.cancel())
        assertFalse(chooser.select(chooser.files.last()))
        assertEquals(1, received.size)

        explorer.open(onResult = received::add)
        val newer = stack.top
        assertFalse(chooser.select(chooser.files.last()))
        assertSame(newer, stack.top)
    }

    @Test fun popAndDismissDeliverTypedCancellationAfterRestoringOwner() {
        val root = StubScreen()
        val stack = ScreenStack(root)
        val results = mutableListOf<FileExplorerResult>()
        val explorer = opener(stack)
        explorer.open { result ->
            assertSame(root, stack.top)
            results += result
        }
        val first = stack.top as FileExplorerScreen
        assertSame(first, stack.pop())
        assertFalse(first.cancel())
        assertEquals(listOf(FileExplorerResult.Cancelled), results)

        explorer.open { result ->
            assertSame(root, stack.top)
            results += result
        }
        val second = stack.top as FileExplorerScreen
        assertTrue(stack.dismiss(second))
        assertFalse(stack.dismiss(second))
        assertFalse(second.cancel())
        assertEquals(listOf(FileExplorerResult.Cancelled, FileExplorerResult.Cancelled), results)
    }

    @Test fun nestedOverlaysOnlyCompleteTopAndDoNotStealOtherCallbacks() {
        val root = StubScreen()
        val stack = ScreenStack(root)
        val outerResults = mutableListOf<FileExplorerResult>()
        val innerResults = mutableListOf<FileExplorerResult>()
        val outerOpener = opener(stack)
        outerOpener.open(onResult = outerResults::add)
        val outer = stack.top as FileExplorerScreen
        val innerOpener = opener(stack)
        innerOpener.open(onResult = innerResults::add)
        val inner = stack.top as FileExplorerScreen
        assertFalse(outer.select(outer.files.first()))
        assertFailsWith<IllegalStateException> { outerOpener.open {} }
        assertSame(inner, stack.pop())
        assertEquals(listOf(FileExplorerResult.Cancelled), innerResults)
        assertTrue(outer.select(outer.files.last()))
        assertSame(root, stack.top)
        assertEquals(listOf(FileExplorerResult.Selected("example-b.nc")), outerResults)
        assertFalse(inner.cancel())
    }

    @Test fun independentStacksAndDetachedContextsCannotCrossNavigate() {
        val first = ScreenStack(StubScreen())
        val second = ScreenStack(StubScreen())
        val firstContext = ScreenNavigationContext(first, first.entries.last())
        val secondContext = ScreenNavigationContext(second, second.entries.last())
        val left = FileExplorer(firstContext)
        val right = FileExplorer(secondContext)
        val leftResults = mutableListOf<FileExplorerResult>()
        val rightResults = mutableListOf<FileExplorerResult>()
        left.open(onResult = leftResults::add)
        assertTrue(first.top is FileExplorerScreen)
        assertEquals(1, second.size)
        right.open(onResult = rightResults::add)
        (first.top as FileExplorerScreen).cancel()
        assertEquals(listOf(FileExplorerResult.Cancelled), leftResults)
        assertTrue(second.top is FileExplorerScreen)
        (second.top as FileExplorerScreen).select((second.top as FileExplorerScreen).files.first())
        assertEquals(listOf(FileExplorerResult.Selected("example-a.nc")), rightResults)
        firstContext.dispose()
        assertFailsWith<IllegalStateException> { left.open {} }
        assertEquals(1, first.size)
        assertEquals(1, second.size)
    }

    @Test fun unboundExplorerCannotComplete() {
        val unbound = FileExplorerScreen("/programs")
        assertFailsWith<IllegalArgumentException> { unbound.cancel() }
    }
}
