package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import no.grblcommander.desktop.ui.screens.EditorScreen
import no.grblcommander.desktop.ui.screens.FileExplorer
import kotlin.test.*

class ScreenStackSoftKeyInheritanceTest {
    private class Fixture(
        override val inheritBottomKeys: Boolean = false,
        override val inheritRightKeys: Boolean = false,
        override val bottomKeys: Map<BottomSoftKeySlot, SoftKeyOverride> = emptyMap(),
        override val rightKeys: Map<RightSoftKeySlot, SoftKeyOverride> = emptyMap(),
    ) : Screen<Unit>(Unit) {
        @Composable override fun Content() {}
    }

    @Test fun defaultsAreIndependentAndRootIsAlwaysIncluded() {
        val root = Fixture(
            bottomKeys = mapOf(BottomSoftKeySlot.F1 to SoftKeyDefinition("Root")),
            rightKeys = mapOf(RightSoftKeySlot.R1 to SoftKeyDefinition("Right")),
        )
        assertFalse(root.inheritBottomKeys)
        assertFalse(root.inheritRightKeys)
        val stack = ScreenStack(root)
        assertEquals("Root", stack.softKeyDefinition(BottomSoftKeySlot.F1)?.label)
        assertEquals("Right", stack.softKeyDefinition(RightSoftKeySlot.R1)?.label)
        assertEquals(setOf(BottomSoftKeySlot.F1), stack.effectiveBottomKeys.keys)
        assertEquals(setOf(RightSoftKeySlot.R1), stack.effectiveRightKeys.keys)
        assertNull(stack.softKeyDefinition(BottomSoftKeySlot.F8))
    }

    @Test fun inheritingRootIsFallbackForBothBanks() {
        val root = Fixture(
            inheritBottomKeys = true,
            inheritRightKeys = true,
            bottomKeys = mapOf(BottomSoftKeySlot.F1 to SoftKeyDefinition("Root bottom")),
            rightKeys = mapOf(RightSoftKeySlot.R1 to SoftKeyDefinition("Root right")),
        )
        val stack = ScreenStack(root)
        assertEquals("Root bottom", stack.softKeyDefinition(BottomSoftKeySlot.F1)?.label)
        assertEquals("Root right", stack.softKeyDefinition(RightSoftKeySlot.R1)?.label)

        stack.push(Fixture(inheritBottomKeys = true, inheritRightKeys = true))
        stack.push(Fixture(
            inheritBottomKeys = true,
            inheritRightKeys = true,
            bottomKeys = mapOf(BottomSoftKeySlot.F1 to SoftKeyModifier { it.copy(label = "Inherited bottom") }),
            rightKeys = mapOf(RightSoftKeySlot.R1 to SoftKeyModifier { it.copy(label = "Inherited right") }),
        ))
        assertEquals("Inherited bottom", stack.softKeyDefinition(BottomSoftKeySlot.F1)?.label)
        assertEquals("Inherited right", stack.softKeyDefinition(RightSoftKeySlot.R1)?.label)
    }

    @Test fun threeLevelsFindDifferentBoundariesForEachBank() {
        val root = Fixture(
            bottomKeys = mapOf(
                BottomSoftKeySlot.F1 to SoftKeyDefinition("Root F1"),
                BottomSoftKeySlot.F2 to SoftKeyDefinition("Root F2"),
            ),
            rightKeys = mapOf(RightSoftKeySlot.R1 to SoftKeyDefinition("Root R1")),
        )
        val middle = Fixture(
            inheritBottomKeys = true,
            bottomKeys = mapOf(BottomSoftKeySlot.F2 to SoftKeyModifier {
                it.copy(label = "Middle F2")
            }),
            rightKeys = mapOf(RightSoftKeySlot.R2 to SoftKeyDefinition("Middle R2")),
        )
        val top = Fixture(
            inheritRightKeys = true,
            bottomKeys = mapOf(BottomSoftKeySlot.F3 to SoftKeyDefinition("Top F3")),
            rightKeys = mapOf(RightSoftKeySlot.R3 to SoftKeyDefinition("Top R3")),
        )
        val stack = ScreenStack(root)
        stack.push(middle)
        assertEquals("Root F1", stack.softKeyDefinition(BottomSoftKeySlot.F1)?.label)
        assertEquals("Middle F2", stack.softKeyDefinition(BottomSoftKeySlot.F2)?.label)
        assertNull(stack.softKeyDefinition(RightSoftKeySlot.R1))
        stack.push(top)

        assertNull(stack.softKeyDefinition(BottomSoftKeySlot.F1))
        assertNull(stack.softKeyDefinition(BottomSoftKeySlot.F2))
        assertEquals("Top F3", stack.softKeyDefinition(BottomSoftKeySlot.F3)?.label)
        assertNull(stack.softKeyDefinition(RightSoftKeySlot.R1))
        assertEquals("Middle R2", stack.softKeyDefinition(RightSoftKeySlot.R2)?.label)
        assertEquals("Top R3", stack.softKeyDefinition(RightSoftKeySlot.R3)?.label)
        assertEquals(setOf(BottomSoftKeySlot.F3), stack.effectiveBottomKeys.keys)
        assertEquals(setOf(RightSoftKeySlot.R2, RightSoftKeySlot.R3), stack.effectiveRightKeys.keys)
    }

    @Test fun sequentialModifiersPreserveOriginalCallbackAndPredicates() {
        val allowed = mutableStateOf(false)
        val active = mutableStateOf(false)
        var rootCalls = 0
        val original = SoftKeyDefinition(
            "Root", action = { rootCalls++ },
            enabled = { allowed.value }, active = { active.value },
        )
        val root = Fixture(bottomKeys = mapOf(BottomSoftKeySlot.F1 to original))
        val middle = Fixture(inheritBottomKeys = true, bottomKeys = mapOf(
            BottomSoftKeySlot.F1 to SoftKeyModifier { it.copy(label = it.label + " middle") },
        ))
        val top = Fixture(inheritBottomKeys = true, bottomKeys = mapOf(
            BottomSoftKeySlot.F1 to SoftKeyModifier { it.copy(label = it.label + " top") },
        ))
        val stack = ScreenStack(root)
        stack.push(middle)
        stack.push(top)

        val composed = assertNotNull(stack.softKeyDefinition(BottomSoftKeySlot.F1))
        assertEquals("Root middle top", composed.label)
        assertSame(original.action, composed.action)
        assertSame(original.enabled, composed.enabled)
        assertSame(original.active, composed.active)
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
        allowed.value = true
        active.value = true
        assertTrue(composed.enabled())
        assertTrue(composed.active())
        assertTrue(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
        assertEquals(1, rootCalls)
        stack.pop()
        assertEquals("Root middle", stack.softKeyDefinition(BottomSoftKeySlot.F1)?.label)
        stack.pop()
        assertSame(original, stack.softKeyDefinition(BottomSoftKeySlot.F1))
    }

    @Test fun definitionsReplaceEarlierBindingsAndModifiersOnMissingSlotsAreNoOps() {
        var rootCalls = 0
        var middleCalls = 0
        val root = Fixture(
            rightKeys = mapOf(RightSoftKeySlot.R1 to SoftKeyDefinition("Root", action = { rootCalls++ })),
        )
        val middle = Fixture(inheritRightKeys = true, rightKeys = mapOf(
            RightSoftKeySlot.R1 to SoftKeyDefinition("Middle", action = { middleCalls++ }),
            RightSoftKeySlot.R2 to SoftKeyModifier { it.copy(label = "Missing") },
        ))
        val top = Fixture(inheritRightKeys = true, rightKeys = mapOf(
            RightSoftKeySlot.R1 to SoftKeyModifier { it.copy(label = "Modified middle") },
            RightSoftKeySlot.R2 to SoftKeyModifier { it.copy(label = "Still missing") },
        ))
        val stack = ScreenStack(root)
        stack.push(middle)
        stack.push(top)
        assertEquals("Modified middle", stack.softKeyDefinition(RightSoftKeySlot.R1)?.label)
        assertNull(stack.softKeyDefinition(RightSoftKeySlot.R2))
        assertTrue(activateSoftKey({ stack }, RightSoftKeySlot.R1))
        assertEquals(0, rootCalls)
        assertEquals(1, middleCalls)
    }

    @Test fun blankSuppressesInheritedActionButDisabledLabelRemains() {
        var calls = 0
        val root = Fixture(rightKeys = mapOf(
            RightSoftKeySlot.R1 to SoftKeyDefinition("Action", action = { calls++ }),
            RightSoftKeySlot.R2 to SoftKeyDefinition("Disabled", action = { calls++ }, enabled = { false }),
        ))
        val overlay = Fixture(inheritRightKeys = true, rightKeys = mapOf(
            RightSoftKeySlot.R1 to SoftKeyDefinition.Blank,
        ))
        val stack = ScreenStack(root)
        stack.push(overlay)
        assertSame(SoftKeyDefinition.Blank, stack.effectiveRightKeys[RightSoftKeySlot.R1])
        assertEquals("Disabled", stack.softKeyDefinition(RightSoftKeySlot.R2)?.label)
        assertFalse(activateSoftKey({ stack }, RightSoftKeySlot.R1))
        assertFalse(activateSoftKey({ stack }, RightSoftKeySlot.R2))
        assertEquals(0, calls)
        stack.pop()
        assertTrue(activateSoftKey({ stack }, RightSoftKeySlot.R1))
        assertEquals(1, calls)
    }

    @Test fun editorExplorerIndependentlyReplacesBottomAndInheritsRight() {
        val editor = EditorScreen()
        val stack = ScreenStack(editor)
        val openFile = assertNotNull(stack.softKeyDefinition(RightSoftKeySlot.R1))
        val back = assertNotNull(stack.softKeyDefinition(RightSoftKeySlot.R2))
        val explorer = FileExplorer()
        explorer.open(editor)
        assertFalse(explorer.inheritBottomKeys)
        assertTrue(explorer.inheritRightKeys)
        assertEquals("Select", stack.softKeyDefinition(BottomSoftKeySlot.F1)?.label)
        assertEquals("Clear selection", stack.softKeyDefinition(BottomSoftKeySlot.F2)?.label)
        assertFalse(stack.softKeyDefinition(BottomSoftKeySlot.F2)!!.enabled())
        assertEquals("Cancel", stack.softKeyDefinition(BottomSoftKeySlot.F8)?.label)
        val modified = assertNotNull(stack.softKeyDefinition(RightSoftKeySlot.R1))
        assertEquals("Editor unavailable", modified.label)
        assertSame(openFile.action, modified.action)
        assertFalse(modified.enabled())
        assertFalse(activateSoftKey({ stack }, RightSoftKeySlot.R1))
        assertSame(SoftKeyDefinition.Blank, stack.softKeyDefinition(RightSoftKeySlot.R2))
        assertEquals("Info", stack.softKeyDefinition(RightSoftKeySlot.R7)?.label)
        assertTrue(explorer.cancel())
        assertSame(openFile, stack.softKeyDefinition(RightSoftKeySlot.R1))
        assertSame(back, stack.softKeyDefinition(RightSoftKeySlot.R2))
        assertNull(stack.softKeyDefinition(RightSoftKeySlot.R7))
    }

    @Test fun predicateUpdatesDoNotRecomposeStructuralModifiers() {
        val allowed = mutableStateOf(false)
        val highlighted = mutableStateOf(false)
        var modifierCalls = 0
        val original = SoftKeyDefinition(
            "Base", action = {}, enabled = { allowed.value }, active = { highlighted.value },
        )
        val root = Fixture(bottomKeys = mapOf(BottomSoftKeySlot.F1 to original))
        val overlay = Fixture(inheritBottomKeys = true, bottomKeys = mapOf(
            BottomSoftKeySlot.F1 to SoftKeyModifier {
                modifierCalls++
                it.copy(label = "Modified")
            },
        ))
        val stack = ScreenStack(root)
        stack.push(overlay)
        val first = assertNotNull(stack.effectiveBottomKeys[BottomSoftKeySlot.F1])
        assertEquals(1, modifierCalls)
        allowed.value = true
        highlighted.value = true
        assertTrue(first.enabled())
        assertTrue(first.active())
        assertSame(first, stack.effectiveBottomKeys[BottomSoftKeySlot.F1])
        assertEquals(1, modifierCalls)
        stack.pop()
        assertSame(original, stack.effectiveBottomKeys[BottomSoftKeySlot.F1])
    }

    @Test fun modeSwitchAndPopNeverActivateDetachedOrInactiveScreens() {
        var programCalls = 0
        var manualCalls = 0
        val program = ScreenStack(Fixture(bottomKeys = mapOf(
            BottomSoftKeySlot.F1 to SoftKeyDefinition("Program", action = { programCalls++ }),
        )))
        val manual = ScreenStack(Fixture(bottomKeys = mapOf(
            BottomSoftKeySlot.F1 to SoftKeyDefinition("Manual", action = { manualCalls++ }),
        )))
        val overlay = Fixture(inheritBottomKeys = true, bottomKeys = mapOf(
            BottomSoftKeySlot.F1 to SoftKeyModifier { it.copy(label = "Inherited program") },
        ))
        program.push(overlay)
        var selected = program
        assertTrue(activateSoftKey({ selected }, BottomSoftKeySlot.F1))
        assertEquals(1, programCalls)
        selected = manual
        assertTrue(activateSoftKey({ selected }, BottomSoftKeySlot.F1))
        assertEquals(1, manualCalls)
        assertEquals("Inherited program", program.softKeyDefinition(BottomSoftKeySlot.F1)?.label)
        assertSame(overlay, program.pop())
        assertTrue(activateSoftKey({ selected }, BottomSoftKeySlot.F1))
        assertEquals(2, manualCalls)
        assertEquals(1, programCalls)
        selected = program
        assertEquals("Program", selected.softKeyDefinition(BottomSoftKeySlot.F1)?.label)
        assertTrue(activateSoftKey({ selected }, BottomSoftKeySlot.F1))
        assertEquals(2, programCalls)
    }
}
