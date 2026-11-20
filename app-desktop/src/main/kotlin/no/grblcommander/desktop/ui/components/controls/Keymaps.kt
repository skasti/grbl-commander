package no.grblcommander.desktop.ui.components.controls

object Keymaps {
    /** Builds a row-major page; repeated adjacent keys become one spanning key. */
    fun newPage(emptyPlaceholder: Char, vararg rows: String): Map<Int, KeypadKey> {
        val columns = rows.maxOfOrNull(String::length) ?: return emptyMap()
        return buildMap {
            rows.forEachIndexed { rowIndex, row ->
                var column = 0
                while (column < row.length) {
                    val key = newKey(row[column], emptyPlaceholder)
                    if (key == null) {
                        column++
                        continue
                    }

                    var span = 1
                    while (column + span < row.length && newKey(row[column + span], emptyPlaceholder) == key) {
                        span++
                    }
                    put(rowIndex * columns + column, key.copy(columnSpan = span))
                    column += span
                }
            }
        }
    }

    private fun newKey(symbol: Char, emptyPlaceholder: Char): KeypadKey? {
        val text = symbol.toString()
        if (symbol == emptyPlaceholder) return null
        val action = when (text) {
            "⌫" -> KeypadAction.Backspace
            "↵" -> KeypadAction.Enter
            "−" -> KeypadAction.Insert("-")
            else -> KeypadAction.Insert(text)
        }
        val label = if (text == " ") "Space" else text
        return KeypadKey(label, action)
    }

    fun newPage(vararg rows: String): Map<Int, KeypadKey> =
        newPage(emptyPlaceholder = '_', rows = rows)

    val code = KeypadLayout("CODE", pages = listOf(
        newPage("ONGP", "XYZA", "IJKR", "FSTM"),
        newPage("BCDE", "HLQU", "VW__"),
    ))
    val numPlus = KeypadLayout("NUM+", gapsBeforeColumns = mapOf(3 to .60f), pages = listOf(
        newPage("789⌫", "456*", "123+", "−0.↵"),
        newPage("#/=⌫", "()$*", "[]%+", "− :↵"),
    ))
    val num = KeypadLayout("NUM", gapsBeforeColumns = mapOf(3 to .60f), pages = listOf(
        newPage("789⌫", "456_", "123_", "_0_↵"),
    ))

    val qwerty = KeypadLayout("QWERTY", rows = 3, columns = 10, pages = listOf(
        newPage("qwertyuiop", "asdfghjkl_", "zxcvbnm  "),
        newPage("QWERTYUIOP", "ASDFGHJKL_", "ZXCVBNM  "),
    ))
}
