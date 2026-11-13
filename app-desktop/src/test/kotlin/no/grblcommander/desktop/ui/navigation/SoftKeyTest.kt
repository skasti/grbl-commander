package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.test.*

class SoftKeyTest {
    @Test fun defaultsAndFixedPositions() {
        val definition = SoftKeyDefinition("Open")
        assertTrue(definition.enabled())
        assertFalse(definition.active())
        assertNull(definition.action)
        assertNull(definition.icon)
        assertEquals((1..8).map { "F$it" }, BottomSoftKeySlot.entries.map { it.shortcut })
        assertEquals((1..7).map { "R$it" }, RightSoftKeySlot.entries.map { it.shortcut })
        assertEquals("", SoftKeyDefinition.Blank.label)
        assertNull(SoftKeyDefinition.Blank.icon)
        assertNull(SoftKeyDefinition.Blank.action)
        assertFalse(SoftKeyDefinition.Blank.enabled())
    }

    @Test fun modifiersPreserveAndExplicitlyReplaceFields() {
        val selected = mutableStateOf(false)
        var originalCalls = 0
        var replacementCalls = 0
        val icon = ImageVector.Builder("fixture", 16.dp, 16.dp, 16f, 16f).build()
        val original = SoftKeyDefinition(
            "Select", icon, { originalCalls++ },
            enabled = { selected.value }, active = { selected.value })
        val modified = SoftKeyModifier { it.copy(label = "Choose") }.modify(original)
        assertEquals("Select", original.label)
        assertEquals("Choose", modified.label)
        assertSame(original.action, modified.action)
        assertSame(icon, modified.icon)
        assertSame(original.enabled, modified.enabled)
        assertSame(original.active, modified.active)
        assertFalse(modified.enabled())
        selected.value = true
        assertTrue(modified.enabled())
        assertTrue(modified.active())
        modified.action!!()
        assertEquals(1, originalCalls)
        val replaced = SoftKeyModifier {
            it.copy(icon = null, action = { replacementCalls++ }, enabled = { false }, active = { false })
        }.modify(modified)
        replaced.action!!()
        assertEquals(1, replacementCalls)
        assertEquals(1, originalCalls)
        assertNull(replaced.icon)
        assertFalse(replaced.enabled())
        assertFalse(replaced.active())
    }
}
