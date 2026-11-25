package no.grblcommander.core.connection

import no.grblcommander.core.controller.LineProgram

internal class OperationCommands(source: List<String>) {
    private val program = LineProgram(source)
    val commands = program.source.indices.map(program::wire)
    val lineNumbers = program.lineNumbers
}
