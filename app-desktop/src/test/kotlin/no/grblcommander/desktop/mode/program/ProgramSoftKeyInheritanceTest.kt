package no.grblcommander.desktop.mode.program

import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.RightSoftKeySlot
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.SoftKeyDefinition
import no.grblcommander.desktop.ui.navigation.activateSoftKey
import kotlin.test.*

class ProgramSoftKeyInheritanceTest {
    @Test fun editorExplorerIndependentlyReplacesBottomAndInheritsRight() {
        val editor = Editor()
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
}
