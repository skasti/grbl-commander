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
        val definition = mutableStateOf(
            SoftKeyDefinition("Run", action = { calls++ }, enabled = { allowed.value }),
        )
        override val bottomKeys get() = mapOf(BottomSoftKeySlot.F1 to definition.value)
        @Composable override fun Content() {}
    }

    @Test fun keyboardMappingUsesTypedBanks() {
        val keys = listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8)
        keys.forEachIndexed { index, key ->
            assertEquals(BottomSoftKeySlot.entries[index], softKeySlot(key))
            assertEquals(RightSoftKeySlot.entries.getOrNull(index), softKeySlot(key, shift = true))
        }
        assertNull(softKeySlot(Key.A))
    }

    @Test fun resolvesLatestDefinitionAndChecksEnabledAtInvocation() {
        val screen = Fixture()
        val stack = ScreenStack(screen)
        val original = stack.softKeyDefinition(BottomSoftKeySlot.F1)
        screen.allowed.value = false
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
        screen.allowed.value = true
        assertSame(original, stack.softKeyDefinition(BottomSoftKeySlot.F1))
        assertTrue(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
        assertEquals(1, screen.calls)

        var replacementCalls = 0
        screen.definition.value = screen.definition.value.copy(action = { replacementCalls++ }, active = { true })
        assertTrue(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
        assertEquals(1, screen.calls)
        assertEquals(1, replacementCalls)
        screen.definition.value = screen.definition.value.copy(enabled = { false })
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
    }

    @Test fun undefinedBlankActionlessAndModifierOnlySlotsCannotActivate() {
        val screen = Fixture()
        val stack = ScreenStack(screen)
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F2))
        screen.definition.value = SoftKeyDefinition.Blank
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
        screen.definition.value = SoftKeyDefinition("Label only")
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
        val modifier = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(BottomSoftKeySlot.F1 to SoftKeyModifier { it.copy(label = "Changed") })
            @Composable override fun Content() {}
        }
        stack.push(modifier)
        assertNull(stack.softKeyDefinition(BottomSoftKeySlot.F1))
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
    }

    @Test fun activeStackAndTopScreenDetermineEachActivation() {
        val root = Fixture()
        val overlay = Fixture()
        val program = ScreenStack(root)
        val manual = ScreenStack(Fixture())
        var selected = program
        program.push(overlay)
        assertTrue(activateSoftKey({ selected }, BottomSoftKeySlot.F1))
        assertEquals(1, overlay.calls)
        assertEquals(0, root.calls)
        program.pop()
        assertTrue(activateSoftKey({ selected }, BottomSoftKeySlot.F1))
        assertEquals(1, root.calls)
        selected = manual
        assertTrue(activateSoftKey({ selected }, BottomSoftKeySlot.F1))
        assertEquals(1, manual.top.let { (it as Fixture).calls })
        assertEquals(1, overlay.calls)
    }

    @Test fun navigationTriggeredByActionIsValid() {
        lateinit var stack: ScreenStack
        val overlay = Fixture()
        var calls = 0
        val root = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(
                BottomSoftKeySlot.F1 to SoftKeyDefinition("Open", action = {
                    calls++
                    stack.push(overlay)
                }),
            )
            @Composable override fun Content() {}
        }
        stack = ScreenStack(root)
        assertTrue(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
        assertSame(overlay, stack.top)
        assertEquals(1, calls)
    }
}
