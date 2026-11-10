package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ModeScreenStacksTest {
    private enum class Mode { Program, Manual, Mdi }

    private class StubScreen : Screen<Unit>(Unit) {
        @Composable
        override fun Content() {}
    }

    @Test
    fun switchingModesDoesNotPopOrRecreateExistingScreens() {
        val roots = mutableMapOf<Mode, Screen<*>>()
        val modes = ModeScreenStacks(Mode.Program) { mode ->
            StubScreen().also { roots[mode] = it }
        }
        val programStack = modes.activeStack
        val editor = StubScreen()
        val explorer = StubScreen()
        programStack.push(editor)
        programStack.push(explorer)

        modes.selectMode(Mode.Manual)
        val manualStack = modes.activeStack
        val manualOverlay = StubScreen()
        manualStack.push(manualOverlay)

        assertEquals(Mode.Manual, modes.activeMode)
        assertSame(manualOverlay, modes.activeStack.top)
        assertSame(roots[Mode.Manual], manualStack.screens.first())

        modes.selectMode(Mode.Program)
        assertSame(programStack, modes.activeStack)
        assertSame(explorer, modes.activeStack.top)
        assertSame(editor, modes.activeStack.screens[1])
        assertEquals(3, modes.activeStack.size)

        modes.selectMode(Mode.Manual)
        assertSame(manualStack, modes.activeStack)
        assertSame(manualOverlay, modes.activeStack.top)
        assertEquals(2, roots.size)
    }

    @Test
    fun eachModeRootIsCreatedOnceAndOtherModesRemainIndependent() {
        val creations = mutableMapOf<Mode, Int>()
        val modes = ModeScreenStacks(Mode.Program) { mode ->
            creations[mode] = (creations[mode] ?: 0) + 1
            StubScreen()
        }

        val program = modes.stackFor(Mode.Program)
        val mdi = modes.stackFor(Mode.Mdi)
        val manual = modes.stackFor(Mode.Manual)
        modes.selectMode(Mode.Mdi)
        modes.selectMode(Mode.Program)
        modes.selectMode(Mode.Manual)
        modes.selectMode(Mode.Mdi)

        assertSame(program, modes.stackFor(Mode.Program))
        assertSame(mdi, modes.activeStack)
        assertSame(manual, modes.stackFor(Mode.Manual))
        assertEquals(mapOf(Mode.Program to 1, Mode.Mdi to 1, Mode.Manual to 1), creations)
        assertEquals(3, modes.stacks.size)
    }

    @Test
    fun separateOwnersDoNotShareModeStacks() {
        val first = ModeScreenStacks(Mode.Program) { StubScreen() }
        val second = ModeScreenStacks(Mode.Program) { StubScreen() }
        val firstOverlay = StubScreen()
        first.activeStack.push(firstOverlay)

        assertSame(firstOverlay, first.activeStack.top)
        assertEquals(2, first.activeStack.size)
        assertEquals(1, second.activeStack.size)

        first.selectMode(Mode.Manual)
        assertEquals(Mode.Program, second.activeMode)
        assertEquals(1, second.stacks.size)
    }
}
