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

    @Test fun repeatedResolutionAndPredicateChangesKeepLogicalIdentity() {
        val screen = Fixture()
        val stack = ScreenStack(screen)
        val first = stack.softKeyBinding(BottomSoftKeySlot.F1)!!
        val again = stack.softKeyBinding(BottomSoftKeySlot.F1)!!
        assertEquals(first, again)
        assertEquals(first.hashCode(), again.hashCode())

        screen.allowed.value = false
        val disabled = stack.softKeyBinding(BottomSoftKeySlot.F1)!!
        assertEquals(first, disabled)
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F1, first))
        screen.allowed.value = true
        assertEquals(first, stack.softKeyBinding(BottomSoftKeySlot.F1))
        assertTrue(activateSoftKey({ stack }, BottomSoftKeySlot.F1, first))
        assertEquals(1, screen.calls)
    }

    @Test fun equivalentModifierCopiesHaveStableIdentityAndKeepOriginalOwnership() {
        val origin = Fixture()
        val stack = ScreenStack(origin)
        val originEntry = stack.entries.last()
        val overlay = Fixture()
        stack.push(overlay)
        val modifierEntry = stack.entries.last()
        val modifier = SoftKeyModifier { it.copy(label = "Choose") }

        // Synthetic #4 output: test provenance without implementing inheritance.
        fun resolved() = SoftKeyBinding(
            stack, originEntry, modifier.modify(origin.definition),
            sourceDefinition = origin.definition,
            modifiers = listOf(modifierEntry to modifier),
        )
        val displayed = resolved()
        val recalculated = resolved()
        assertNotSame(displayed.definition, recalculated.definition)
        assertEquals(displayed, recalculated)
        assertEquals(displayed.hashCode(), recalculated.hashCode())
        assertSame(originEntry, displayed.entry)
        assertSame(origin.definition.action, displayed.definition.action)
        assertSame(origin.definition.enabled, displayed.definition.enabled)

        // A modifier with equivalent output is still different composition.
        val otherModifier = SoftKeyModifier { it.copy(label = "Choose") }
        val recomposed = SoftKeyBinding(
            stack, originEntry, otherModifier.modify(origin.definition),
            sourceDefinition = origin.definition,
            modifiers = listOf(modifierEntry to otherModifier),
        )
        assertNotEquals(displayed, recomposed)

        // Reattaching the modifier owner creates a different navigation lifetime.
        stack.pop()
        stack.push(overlay)
        val reopenedModifierEntry = stack.entries.last()
        assertNotEquals(displayed, SoftKeyBinding(
            stack, originEntry, modifier.modify(origin.definition),
            sourceDefinition = origin.definition,
            modifiers = listOf(reopenedModifierEntry to modifier),
        ))
    }

    @Test fun changesToEffectiveFieldsAndSourceInvalidateModifierStyleBinding() {
        val origin = Fixture()
        val stack = ScreenStack(origin)
        val entry = stack.entries.last()
        val source = origin.definition
        val actionA: () -> Unit = { origin.calls++ }
        val actionB: () -> Unit = { error("Obsolete action must not run") }
        val modifier = SoftKeyModifier { it.copy(label = "Modified") }
        fun binding(effective: SoftKeyDefinition, sourceDefinition: SoftKeyDefinition = source) =
            SoftKeyBinding(stack, entry, effective, sourceDefinition, listOf(entry to modifier))

        val initial = binding(modifier.modify(source).copy(action = actionA))
        assertEquals(initial, binding(modifier.modify(source).copy(action = actionA)))
        assertNotEquals(initial, binding(modifier.modify(source).copy(action = actionB)))
        assertNotEquals(initial, binding(modifier.modify(source).copy(label = "Updated", action = actionA)))
        assertNotEquals(initial, binding(modifier.modify(source).copy(enabled = { false }, action = actionA)))
        assertNotEquals(initial, binding(modifier.modify(source).copy(active = { true }, action = actionA)))
        assertNotEquals(initial, binding(modifier.modify(source).copy(action = actionA),
            source.copy()))
    }

    @Test fun equivalentSourceReplacementAlsoRejectsPendingActivation() {
        val screen = Fixture()
        val stack = ScreenStack(screen)
        val pending = stack.softKeyBinding(BottomSoftKeySlot.F1)!!
        screen.definition = screen.definition.copy() // Identical fields, new screen-owned override.
        assertNotEquals(pending, stack.softKeyBinding(BottomSoftKeySlot.F1))
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F1, pending))
        assertEquals(0, screen.calls)
        assertTrue(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
        assertEquals(1, screen.calls)
    }

}
