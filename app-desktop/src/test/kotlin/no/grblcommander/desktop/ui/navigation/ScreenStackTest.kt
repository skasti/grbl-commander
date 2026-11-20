package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

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
}
