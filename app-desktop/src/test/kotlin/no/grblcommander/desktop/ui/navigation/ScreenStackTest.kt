package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.Composable
import no.grblcommander.desktop.ui.screens.EditorScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScreenStackTest {
    /** Equal by value, but still distinct navigation instances. */
    private class StubScreen : Screen<Unit>(Unit) {
        @Composable override fun Content() {}
        override fun equals(other: Any?) = other is StubScreen
        override fun hashCode() = 0
    }

    @Test fun rootBindsAtConstructionAndCannotBeRemoved() {
        val root = StubScreen()
        assertFailsWith<IllegalStateException> { root.screenStack }
        val stack = ScreenStack(root)
        assertSame(stack, root.screenStack)
        assertSame(stack, stack.screenStack)
        repeat(3) { assertNull(stack.pop()) }
        assertFalse(stack.dismiss(root))
        assertSame(root, stack.top)
        assertEquals(1, stack.size)
        assertFailsWith<IllegalStateException> { ScreenStack(root) }
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
        assertSame(stack, first.screenStack)
        assertFalse(stack.dismiss(first))
        assertSame(second, stack.pop())
        assertFailsWith<IllegalStateException> { second.screenStack }
        assertSame(first, stack.top)
        assertSame(first, stack.pop())
        assertSame(root, stack.top)
        stack.push(first)
        assertSame(first, stack.top)
    }

    @Test fun duplicateAndCrossStackBindingAreRejected() {
        val root = StubScreen()
        val firstStack = ScreenStack(root)
        val otherStack = ScreenStack(StubScreen())
        val child = StubScreen()
        assertFailsWith<IllegalArgumentException> { firstStack.push(root) }
        firstStack.push(child)
        assertFailsWith<IllegalArgumentException> { firstStack.push(child) }
        assertFailsWith<IllegalStateException> { otherStack.push(child) }
        assertSame(child, firstStack.top)
        assertEquals(1, otherStack.size)
        assertSame(child, firstStack.pop())
        otherStack.push(child)
        assertSame(otherStack, child.screenStack)
    }

    @Test fun screenOwnedOpenFromBoundRootDeliversTypedResultAfterRestoration() {
        val editor = EditorScreen()
        val stack = ScreenStack(editor)
        editor.draft = "Unfinished edit"
        val results = mutableListOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>()
        val chooser = _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorer("/programs")
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
        assertEquals(listOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>(_root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorerResult.Selected("example-a.nc")), results)
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
        val chooser = _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorer("/programs")
        var result: no.grblcommander.desktop.ui.screens.FileExplorerResult? = null
        chooser.open(stack) { result = it }
        assertSame(stack, chooser.screenStack)
        assertSame(chooser, stack.top)
        assertTrue(chooser.selectFile("example-b.nc"))
        assertSame(covered, stack.top)
        assertEquals(_root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorerResult.Selected("example-b.nc"), result)
    }

    @Test fun popAndOrdinaryDismissDeliverCancellationExactlyOnce() {
        val root = StubScreen()
        val stack = ScreenStack(root)
        val results = mutableListOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>()
        val first = _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorer()
        first.open(root) {
            assertSame(root, stack.top)
            results += it
        }
        assertSame(first, stack.pop())
        assertFalse(first.cancel())
        assertNull(stack.pop())
        assertEquals(listOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>(_root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorerResult.Cancelled), results)

        val second = _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorer()
        second.open(stack) {
            assertSame(root, stack.top)
            results += it
        }
        assertTrue(stack.dismiss(second))
        assertFalse(stack.dismiss(second))
        assertFalse(second.selectFile("example-a.nc"))
        assertEquals(listOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>(
            _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorerResult.Cancelled, _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorerResult.Cancelled,
        ), results)
    }

    @Test fun nestedOverlaysAreBoundToParentAndRejectCoveredCompletionAndOpen() {
        val root = StubScreen()
        val stack = ScreenStack(root)
        val outerResults = mutableListOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>()
        val innerResults = mutableListOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>()
        val outer = _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorer()
        outer.open(root, outerResults::add)
        val inner = _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorer()
        inner.open(outer, innerResults::add)
        assertSame(stack, inner.screenStack)
        assertSame(stack, outer.screenStack)
        assertFalse(outer.selectFile("example-a.nc"))
        assertFalse(stack.dismiss(outer))
        assertFailsWith<IllegalStateException> { _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorer().open(outer) {} }
        assertSame(inner, stack.pop())
        assertEquals(listOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>(_root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorerResult.Cancelled), innerResults)
        assertTrue(outer.selectFile("example-b.nc"))
        assertSame(root, stack.top)
        assertEquals(listOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>(_root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorerResult.Selected("example-b.nc")), outerResults)
        assertFalse(inner.cancel())
    }

    @Test fun staleOrUnboundInstancesCannotCompleteOrOpen() {
        val root = StubScreen()
        val stack = ScreenStack(root)
        val stale = _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorer()
        assertFalse(stale.cancel())
        assertFailsWith<IllegalStateException> { _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorer().open(stale) {} }
        val events = mutableListOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>()
        stale.open(root, events::add)
        assertSame(stale, stack.pop())
        val newer = _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorer()
        newer.open(root, events::add)
        assertFalse(stale.selectFile("example-b.nc"))
        assertSame(newer, stack.top)
        assertEquals(listOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>(_root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorerResult.Cancelled), events)
        assertTrue(newer.cancel())
        assertEquals(listOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>(
            _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorerResult.Cancelled, _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorerResult.Cancelled,
        ), events)
    }

    @Test fun independentStacksKeepTheirOwnBindingsAndCallbacks() {
        val leftRoot = StubScreen()
        val rightRoot = StubScreen()
        val leftStack = ScreenStack(leftRoot)
        val rightStack = ScreenStack(rightRoot)
        val leftResults = mutableListOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>()
        val rightResults = mutableListOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>()
        val left = _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorer()
        val right = _root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorer()
        left.open(leftRoot, leftResults::add)
        right.open(rightStack, rightResults::add)
        assertSame(leftStack, left.screenStack)
        assertSame(rightStack, right.screenStack)
        assertFailsWith<IllegalStateException> { rightStack.push(left) }
        assertTrue(left.cancel())
        assertEquals(listOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>(_root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorerResult.Cancelled), leftResults)
        assertSame(right, rightStack.top)
        assertTrue(right.selectFile("example-a.nc"))
        assertEquals(listOf<no.grblcommander.desktop.ui.screens.FileExplorerResult>(_root_ide_package_.no.grblcommander.desktop.ui.screens.FileExplorerResult.Selected("example-a.nc")), rightResults)
        assertSame(leftRoot, leftStack.top)
        assertSame(rightRoot, rightStack.top)
    }
}
