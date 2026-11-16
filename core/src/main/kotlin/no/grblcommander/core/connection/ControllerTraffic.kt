package no.grblcommander.core.connection

import no.grblcommander.machine.session.CommunicationEvent

/** Session/sequence identify the event; cursor orders all sessions and includes upstream gaps. */
data class ControllerTraffic(
    val sessionId: Long,
    val endpoint: ControllerEndpoint,
    val event: CommunicationEvent,
    val cursor: Long,
)
