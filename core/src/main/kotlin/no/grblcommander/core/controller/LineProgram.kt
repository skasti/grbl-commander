package no.grblcommander.core.controller

import java.util.Collections
import no.grblcommander.machine.protocol.GrblProtocol

/** Immutable source and correlation index. Wire text is prepared on demand. */
internal class LineProgram(lines: List<String>) {
    val source: List<String> = Collections.unmodifiableList(ArrayList(lines))
    private val generated = IntArray(source.size)
    val lineNumbers: Map<Int, Int>

    init {
        require(source.isNotEmpty()) { "An operation needs at least one line" }
        val positions = mutableMapOf<Int, Int>()
        var simple = true
        source.forEachIndexed { index, line ->
            require(GrblProtocol.commandValidationError(line) == null) { "Invalid command at line ${index + 1}" }
            val normalized = stripComments(line).filterNot(Char::isWhitespace).uppercase()
            simple = simple && simpleMotion.matches(normalized)
            val words = numberWord.findAll(normalized).mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it in 1..10_000_000 }.toList()
            for (word in words) {
                positions[word] = if (words.size == 1 && word !in positions) index + 1 else 0
            }
        }
        if (simple) {
            var next = 1
            source.forEachIndexed { index, line ->
                if (next <= 10_000_000 && line.length + next.toString().length + 2 <= 120) {
                    generated[index] = next
                    positions[next++] = index + 1
                }
            }
        }
        lineNumbers = positions.filterValues { it != 0 }
    }

    fun wire(index: Int): String {
        val number = generated[index]
        return if (number == 0) source[index] else "N$number ${source[index]}"
    }

    private companion object {
        private val numberWord = Regex("""N([0-9]+)(?=[A-Z]|$)""")
        private val simpleMotion = Regex("""G0?[01](?:[XYZABCUVF][+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+))+""")
    }

    private fun stripComments(line: String): String = buildString {
        var depth = 0
        for (character in line) {
            when {
                character == '(' -> depth++
                character == ')' && depth > 0 -> depth--
                character == ';' && depth == 0 -> break
                depth == 0 -> append(character)
            }
        }
    }
}
