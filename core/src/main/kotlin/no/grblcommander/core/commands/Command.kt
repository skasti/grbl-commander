package no.grblcommander.core.commands

internal data class Command(val source: CommandSource, val line: Int?, val text: String)
