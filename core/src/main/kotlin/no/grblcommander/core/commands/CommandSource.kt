package no.grblcommander.core.commands

sealed interface CommandSource {
    data object Terminal : CommandSource
    data object MDI : CommandSource
    data class Program(val fileName: String) : CommandSource
}
