package no.grblcommander.desktop.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import no.grblcommander.machine.session.CommunicationEvent

/** Presentation only; the session always records and processes every category. */
internal class TerminalFilters {
    var status by mutableStateOf(false)
    var polling by mutableStateOf(false)
    var acknowledgments by mutableStateOf(true)
    var information by mutableStateOf(true)
    var errors by mutableStateOf(true)
    var time by mutableStateOf(true)

    fun includes(event: CommunicationEvent): Boolean = when (event.category) {
        CommunicationEvent.Category.STATUS -> if (event.direction == CommunicationEvent.Direction.RX) status else polling
        CommunicationEvent.Category.ACKNOWLEDGMENT -> acknowledgments
        CommunicationEvent.Category.ERROR, CommunicationEvent.Category.ALARM -> errors
        CommunicationEvent.Category.INFORMATION, CommunicationEvent.Category.STARTUP -> information
        CommunicationEvent.Category.COMMAND, CommunicationEvent.Category.REALTIME -> true
    }

    fun showAll() { status = true; polling = true; acknowledgments = true; information = true; errors = true; time = true }
}
