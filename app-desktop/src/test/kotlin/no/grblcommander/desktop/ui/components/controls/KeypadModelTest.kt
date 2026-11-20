package no.grblcommander.desktop.ui.components.controls

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.*

class KeypadModelTest {
    @Test fun `default grids retain exact mappings and stable edit keys`() {
        assertEquals("ONGPXYZAIJKRFSTM", GCodeKeymaps.letters.pages[0].values.joinToString("") { it.label })
        assertEquals(listOf("7", "8", "9", "⌫", "4", "5", "6", "*", "1", "2", "3", "+", "−", "0", ".", "↵"),
            GCodeKeymaps.numbers.pages[0].values.map { it.label })
        val letters = GCodeKeymaps.letters.pages.flatMap { it.values }.map { (it.action as KeypadAction.Insert).text }.toSet()
        assertTrue(('A'..'Z').all { it.toString() in letters })
        GCodeKeymaps.numbers.pages.forEach {
            assertEquals(KeypadAction.Backspace, it[3]!!.action)
            assertEquals(KeypadAction.Enter, it[15]!!.action)
            assertEquals(KeypadAction.Insert("*"), it[7]!!.action)
            assertEquals(KeypadAction.Insert("+"), it[11]!!.action)
        }
    }
    @Test fun `clusters cycle independently including single page and preserve empty slots`() {
        val letters = KeyClusterState(GCodeKeymaps.letters)
        val numbers = KeyClusterState(GCodeKeymaps.numbers)
        letters.next()
        assertEquals(1, letters.pageIndex); assertEquals(0, numbers.pageIndex)
        assertNull(letters.keys[15])
        numbers.next(); letters.next()
        assertEquals(0, letters.pageIndex); assertEquals(1, numbers.pageIndex)
        val single = KeyClusterState(KeypadLayout("Only", pages = listOf(emptyMap())))
        single.next(); assertEquals(0, single.pageIndex)
    }
    @Test fun `layout copies configuration and rejects invalid geometry`() {
        val page = mutableMapOf(0 to KeypadKey("Macro", KeypadAction.Insert("G65")))
        val layout = KeypadLayout("Custom", rows = 2, columns = 3, pages = listOf(page), spacing = .2f, gapsBeforeColumns = mapOf(2 to .6f))
        page.clear()
        assertEquals("Macro", layout.pages.single()[0]!!.label)
        assertEquals(3.8f, layout.widthInKeys, .001f)
        assertFailsWith<IllegalArgumentException> { KeypadLayout("Empty", pages = emptyList()) }
        assertFailsWith<IllegalArgumentException> { KeypadLayout("Invalid", pages = listOf(mapOf(16 to KeypadKey("X", KeypadAction.Enter)))) }
        assertFailsWith<IllegalArgumentException> { KeypadLayout("Invalid", pages = listOf(emptyMap()), spacing = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { KeypadLayout("Invalid", pages = listOf(emptyMap()), gapsBeforeColumns = mapOf(4 to .6f)) }
    }
    @Test fun `editing inserts at caret replaces reversed selection and handles backspace unicode and Enter`() {
        var value = TextFieldValue("G0 X12", TextRange(5))
        fun apply(action: KeypadAction) = editWithKeypad(value, action, { value = it }, { error("Unexpected Enter") })
        apply(KeypadAction.Insert("-")); assertEquals(TextFieldValue("G0 X1-2", TextRange(6)), value)
        value = TextFieldValue("G0 X12", TextRange(6, 4), TextRange(4, 6))
        apply(KeypadAction.Insert("9")); assertEquals(TextFieldValue("G0 X9", TextRange(5)), value)
        apply(KeypadAction.Backspace); assertEquals("G0 X", value.text)
        value = TextFieldValue("X😀", TextRange(3))
        apply(KeypadAction.Backspace); assertEquals(TextFieldValue("X", TextRange(1)), value)
        value = TextFieldValue("X", TextRange.Zero)
        apply(KeypadAction.Backspace); assertEquals("X", value.text)
        var entered = 0
        editWithKeypad(value, KeypadAction.Enter, { error("Enter must not edit implicitly") }, { entered++ })
        assertEquals(1, entered)
    }
}
