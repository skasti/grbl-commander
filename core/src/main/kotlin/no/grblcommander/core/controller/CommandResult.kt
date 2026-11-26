package no.grblcommander.core.controller

import no.grblcommander.machine.session.CommandAcknowledgment

internal sealed interface CommandResult {
    data object Sent : CommandResult
    data object UncertainWrite : CommandResult
    data class Reply(val acknowledgment: CommandAcknowledgment) : CommandResult
    data class Interrupted(val cause: Exception) : CommandResult
}