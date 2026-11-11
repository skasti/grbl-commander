package no.grblcommander.desktop.ui.softkeys

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.ScreenStack
import kotlin.test.*

class SoftKeyActivationTest {
    private class Fixture : Screen<Unit>(Unit) {
        var calls = 0
        val allowed = mutableStateOf(true)
        var definition = SoftKeyDefinition("Run", action = { calls++ }, enabled = { allowed.value })
        override val bottomKeys get() = mapOf(BottomSoftKeySlot.F1 to definition)
        @Composable override fun Content() {}
    }

    @Test fun keyboardMappingUsesTypedBanks() {
        val functionKeys = listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8)
        functionKeys.forEachIndexed { index, key ->
            assertEquals(BottomSoftKeySlot.entries[index], softKeySlot(key))
            assertEquals(RightSoftKeySlot.entries.getOrNull(index), softKeySlot(key, shift = true))
        }
        assertNull(softKeySlot(Key.A))
    }

    @Test fun checksEnabledAtInvocationAndActiveIsOnlyPresentation() {
        val screen = Fixture()
        val stack = ScreenStack(screen)
        val displayed = stack.softKeyBinding(BottomSoftKeySlot.F1)!!
        screen.allowed.value = false
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F1, displayed))
        screen.allowed.value = true
        assertTrue(activateSoftKey({ stack }, BottomSoftKeySlot.F1, displayed))
        assertEquals(1, screen.calls)
        screen.definition = screen.definition.copy(active = { true }, enabled = { false })
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
        assertEquals(1, screen.calls)
    }

    @Test fun undefinedBlankActionlessAndModifierOnlySlotsCannotActivate() {
        val screen = Fixture()
        val stack = ScreenStack(screen)
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F2))
        screen.definition = SoftKeyDefinition.Blank
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
        screen.definition = SoftKeyDefinition("Label only")
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
        val overlay = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(BottomSoftKeySlot.F1 to SoftKeyModifier { it.copy(label = "Changed") })
            @Composable override fun Content() {}
        }
        stack.push(overlay)
        assertNull(stack.softKeyBinding(BottomSoftKeySlot.F1))
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
        assertEquals(0, screen.calls)
    }

    @Test fun staleCoveredRemovedReopenedAndInactiveBindingsAreRejected() {
        val root = Fixture()
        val child = Fixture()
        val stack = ScreenStack(root)
        var activeStack = stack
        val rootBinding = stack.softKeyBinding(BottomSoftKeySlot.F1)!!
        stack.push(child)
        val childBinding = stack.softKeyBinding(BottomSoftKeySlot.F1)!!
        assertFalse(activateSoftKey({ activeStack }, BottomSoftKeySlot.F1, rootBinding))
        assertTrue(activateSoftKey({ activeStack }, BottomSoftKeySlot.F1, childBinding))
        assertEquals(1, child.calls)
        assertEquals(0, root.calls)
        stack.pop()
        assertFalse(activateSoftKey({ activeStack }, BottomSoftKeySlot.F1, childBinding))
        stack.push(child) // Same screen and same definition, but a different attachment lifetime.
        assertFalse(activateSoftKey({ activeStack }, BottomSoftKeySlot.F1, childBinding))
        activeStack = ScreenStack(Fixture())
        assertFalse(activateSoftKey({ activeStack }, BottomSoftKeySlot.F1, childBinding))
        assertEquals(1, child.calls)
        assertTrue(activateSoftKey({ activeStack }, BottomSoftKeySlot.F1))
        activeStack = stack
        assertTrue(activateSoftKey({ activeStack }, BottomSoftKeySlot.F1))
        assertEquals(2, child.calls)
    }

    @Test fun replacingDefinitionRejectsPendingPointerCallback() {
        val screen = Fixture()
        val stack = ScreenStack(screen)
        val displayed = stack.softKeyBinding(BottomSoftKeySlot.F1)!!
        screen.definition = screen.definition.copy(action = { error("stale pointer used replacement") })
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F1, displayed))
        assertEquals(0, screen.calls)
    }

    @Test fun navigationInsideEnabledPredicateCannotRunDetachedAction() {
        val stack = ScreenStack(Fixture())
        val child = Fixture()
        child.definition = child.definition.copy(enabled = { stack.pop(); true })
        stack.push(child)
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
        assertEquals(0, child.calls)
    }
}
