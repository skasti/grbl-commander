package no.grblcommander.desktop.mode.program

import androidx.compose.runtime.Composable
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.ScreenStack
import kotlin.test.*

class FileExplorerTest {
    private class StubScreen : Screen<Unit>(Unit) {
        @Composable override fun Content() {}
    }

    @Test fun screenOwnedOpenFromBoundRootDeliversTypedResultAfterRestoration() {
        val editor = Editor()
        val stack = ScreenStack(editor)
        editor.draft = "Unfinished edit"
        val results = mutableListOf<FileExplorerResult>()
        val chooser = FileExplorer("/programs")
        chooser.open(editor) { result ->
            assertSame(editor, stack.top)
            results += result
        }
        assertSame(stack, chooser.screenStack)
        assertSame(chooser, stack.top)
        assertEquals("/programs", chooser.initialDirectory)
        assertTrue(chooser.selectFile(chooser.files.first().name))
        assertSame(editor, stack.top)
        assertEquals("Unfinished edit", editor.draft)
        assertEquals(listOf<FileExplorerResult>(FileExplorerResult.Selected("example-a.nc")), results)
        assertFalse(chooser.cancel())
        assertFalse(chooser.selectFile(chooser.files.last().name))
        assertEquals(1, results.size)
        assertFailsWith<IllegalStateException> { chooser.screenStack }
    }

    @Test fun directStackOpenCanOverlayAnAlreadyActiveScreen() {
        val root = StubScreen()
        val stack = ScreenStack(root)
        val covered = StubScreen()
        stack.push(covered)
        val chooser = FileExplorer("/programs")
        var result: FileExplorerResult? = null
        chooser.open(stack) { result = it }
        assertSame(stack, chooser.screenStack)
        assertSame(chooser, stack.top)
        assertTrue(chooser.selectFile("example-b.nc"))
        assertSame(covered, stack.top)
        assertEquals(FileExplorerResult.Selected("example-b.nc"), result)
    }

    @Test fun popAndOrdinaryDismissDeliverCancellationExactlyOnce() {
        val root = StubScreen()
        val stack = ScreenStack(root)
        val results = mutableListOf<FileExplorerResult>()
        val first = FileExplorer()
        first.open(root) {
            assertSame(root, stack.top)
            results += it
        }
        assertSame(first, stack.pop())
        assertFalse(first.cancel())
        assertNull(stack.pop())
        assertEquals(listOf<FileExplorerResult>(FileExplorerResult.Cancelled), results)

        val second = FileExplorer()
        second.open(stack) {
            assertSame(root, stack.top)
            results += it
        }
        assertTrue(stack.dismiss(second))
        assertFalse(stack.dismiss(second))
        assertFalse(second.selectFile("example-a.nc"))
        assertEquals(listOf<FileExplorerResult>(
            FileExplorerResult.Cancelled, FileExplorerResult.Cancelled,
        ), results)
    }

    @Test fun nestedOverlaysAreBoundToParentAndRejectCoveredCompletionAndOpen() {
        val root = StubScreen()
        val stack = ScreenStack(root)
        val outerResults = mutableListOf<FileExplorerResult>()
        val innerResults = mutableListOf<FileExplorerResult>()
        val outer = FileExplorer()
        outer.open(root, outerResults::add)
        val inner = FileExplorer()
        inner.open(outer, innerResults::add)
        assertSame(stack, inner.screenStack)
        assertSame(stack, outer.screenStack)
        assertFalse(outer.selectFile("example-a.nc"))
        assertFalse(stack.dismiss(outer))
        assertFailsWith<IllegalStateException> { FileExplorer().open(outer) {} }
        assertSame(inner, stack.pop())
        assertEquals(listOf<FileExplorerResult>(FileExplorerResult.Cancelled), innerResults)
        assertTrue(outer.selectFile("example-b.nc"))
        assertSame(root, stack.top)
        assertEquals(listOf<FileExplorerResult>(FileExplorerResult.Selected("example-b.nc")), outerResults)
        assertFalse(inner.cancel())
    }

    @Test fun staleOrUnboundInstancesCannotCompleteOrOpen() {
        val root = StubScreen()
        val stack = ScreenStack(root)
        val stale = FileExplorer()
        assertFalse(stale.cancel())
        assertFailsWith<IllegalStateException> { FileExplorer().open(stale) {} }
        val events = mutableListOf<FileExplorerResult>()
        stale.open(root, events::add)
        assertSame(stale, stack.pop())
        val newer = FileExplorer()
        newer.open(root, events::add)
        assertFalse(stale.selectFile("example-b.nc"))
        assertSame(newer, stack.top)
        assertEquals(listOf<FileExplorerResult>(FileExplorerResult.Cancelled), events)
        assertTrue(newer.cancel())
        assertEquals(listOf<FileExplorerResult>(
            FileExplorerResult.Cancelled, FileExplorerResult.Cancelled,
        ), events)
    }

    @Test fun independentStacksKeepTheirOwnBindingsAndCallbacks() {
        val leftRoot = StubScreen()
        val rightRoot = StubScreen()
        val leftStack = ScreenStack(leftRoot)
        val rightStack = ScreenStack(rightRoot)
        val leftResults = mutableListOf<FileExplorerResult>()
        val rightResults = mutableListOf<FileExplorerResult>()
        val left = FileExplorer()
        val right = FileExplorer()
        left.open(leftRoot, leftResults::add)
        right.open(rightStack, rightResults::add)
        assertSame(leftStack, left.screenStack)
        assertSame(rightStack, right.screenStack)
        assertFailsWith<IllegalStateException> { rightStack.push(left) }
        assertTrue(left.cancel())
        assertEquals(listOf<FileExplorerResult>(FileExplorerResult.Cancelled), leftResults)
        assertSame(right, rightStack.top)
        assertTrue(right.selectFile("example-a.nc"))
        assertEquals(listOf<FileExplorerResult>(FileExplorerResult.Selected("example-a.nc")), rightResults)
        assertSame(leftRoot, leftStack.top)
        assertSame(rightRoot, rightStack.top)
    }
}
