package no.grblcommander.desktop.ui.components.controls

object Keymaps {
    private fun text(label: String, value: String = label) = KeypadKey(label, KeypadAction.Insert(value))
    private val backspace = KeypadKey("⌫", KeypadAction.Backspace)
    private val enter = KeypadKey("↵", KeypadAction.Enter)
    private fun page(vararg keys: KeypadKey?) = keys.mapIndexedNotNull { index, key -> key?.let { index to it } }.toMap()
    fun letterPage(vararg rows: String): Map<Int, KeypadKey> = buildMap {
        rows.forEachIndexed { row, letters ->
            letters.forEachIndexed { column, letter ->
                val text = letter.toString()
                put(row * 10 + column, KeypadKey(text, KeypadAction.Insert(text)))
            }
        }
        put(27, KeypadKey("Space", KeypadAction.Insert(" "), columnSpan = 2))
    }

    fun numPage(vararg labels: String): Map<Int, KeypadKey> =
        labels.mapIndexedNotNull { index, symbol ->
            val action = when (symbol) {
                "" -> return@mapIndexedNotNull null
                "⌫" -> KeypadAction.Backspace
                "↵" -> KeypadAction.Enter
                else -> KeypadAction.Insert(symbol)
            }
            val label = when (symbol) {
                " " -> "Space"
                else -> symbol
            }
            index to KeypadKey(label, action)
        }.toMap()

    val code = KeypadLayout("CODE", pages = listOf(
        page(*"ONGPXYZAIJKRFSTM".map { text(it.toString()) }.toTypedArray()),
        page(*"BCDEHLQUVW".map { text(it.toString()) }.toTypedArray()),
    ))
    val numPlus = KeypadLayout("NUM+", gapsBeforeColumns = mapOf(3 to .60f), pages = listOf(
        page(text("7"), text("8"), text("9"), backspace,
            text("4"), text("5"), text("6"), text("*"),
            text("1"), text("2"), text("3"), text("+"),
            text("−", "-"), text("0"), text("."), enter),
        page(text("#"), text("/"), text("="), backspace,
            text("("), text(")"), text("$"), text("*"),
            text("["), text("]"), text("%"), text("+"),
            text("−", "-"), text("Space", " "), text(":"), enter),
    ))
    val num = KeypadLayout("NUM", gapsBeforeColumns = mapOf(3 to .60f), pages = listOf(
        numPage(
            "7", "8", "9", "⌫",
            "4", "5", "6", "",
            "1", "2", "3", "",
            "", "0", "", "↵"
        ),
    ))

    val qwerty = KeypadLayout("QWERTY", rows = 3, columns = 10, pages = listOf(
        letterPage("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        letterPage("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"),
    ))
}
