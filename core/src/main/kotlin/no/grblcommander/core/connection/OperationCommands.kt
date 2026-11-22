package no.grblcommander.core.connection

import no.grblcommander.machine.protocol.GrblProtocol

/** A batch's exact wire text and the unambiguous N words used for status correlation. */
internal class OperationCommands(source: List<String>) {
    private val numberWord = Regex("""N([0-9]+)(?=[A-Z]|$)""")
    private val simpleMotion = Regex("""G0?[01](?:[XYZABCUVF][+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+))+""")

    val commands: List<String>
    val lineNumbers: Map<Int, Int>

    init {
        require(source.isNotEmpty()) { "An operation needs at least one line" }
        val normalized = source.map { line ->
            GrblProtocol.encodeCommand(line)
            stripComments(line).filterNot(Char::isWhitespace).uppercase()
        }
        val numbers = normalized.map { line ->
            numberWord.findAll(line).mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it in 1..10_000_000 }.toList()
        }
        var next = 1
        val annotated = numbers.toMutableList()
        // Leave control flow and macro programs intact, including any references to N words.
        val canAnnotate = normalized.all { simpleMotion.matches(it) }
        commands = source.mapIndexed { index, line ->
            if (!canAnnotate) return@mapIndexed line
            val numbered = "N$next $line"
            if (next > 10_000_000 || numbered.length > 120) return@mapIndexed line
            annotated[index] = listOf(next)
            next++
            numbered
        }
        val counts = annotated.flatten().groupingBy { it }.eachCount()
        lineNumbers = annotated.mapIndexedNotNull { index, words ->
            words.singleOrNull()?.takeIf { counts[it] == 1 }?.let { it to index + 1 }
        }.toMap()
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
