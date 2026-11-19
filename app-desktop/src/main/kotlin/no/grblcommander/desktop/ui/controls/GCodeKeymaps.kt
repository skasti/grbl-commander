package no.grblcommander.desktop.ui.controls

object GCodeKeymaps {
    private fun text(label: String, value: String = label) = KeypadKey(label, KeypadAction.Insert(value))
    private val backspace = KeypadKey("⌫", KeypadAction.Backspace)
    private val enter = KeypadKey("↵", KeypadAction.Enter)
    private fun page(vararg keys: KeypadKey?) = keys.mapIndexedNotNull { index, key -> key?.let { index to it } }.toMap()

    val letters = KeypadLayout("CODE", pages = listOf(
        page(*"ONGPXYZAIJKRFSTM".map { text(it.toString()) }.toTypedArray()),
        page(*"BCDEHLQUVW".map { text(it.toString()) }.toTypedArray()),
    ))
    val numbers = KeypadLayout("NUM+", gapsBeforeColumns = mapOf(3 to .60f), pages = listOf(
        page(text("7"), text("8"), text("9"), backspace,
            text("4"), text("5"), text("6"), text("*"),
            text("1"), text("2"), text("3"), text("+"),
            text("−", "-"), text("0"), text("."), enter),
        page(text("#"), text("/"), text("="), backspace,
            text("("), text(")"), text("$"), text("*"),
            text("["), text("]"), text("%"), text("+"),
            text("−", "-"), text("Space", " "), text(":"), enter),
    ))

    internal val qwerty = KeypadLayout("QWERTY", rows = 3, columns = 10, pages = listOf(
        letterPage("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        letterPage("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"),
    ))

    fun letterPage(vararg rows: String): Map<Int, KeypadKey> = buildMap {
        rows.forEachIndexed { row, letters ->
            letters.forEachIndexed { column, letter ->
                put(row * 10 + column, text(letter.toString()))
            }
        }
        put(27, KeypadKey("Space", KeypadAction.Insert(" "), columnSpan = 2))
    }
}
