package no.grblcommander.core.connection

import no.grblcommander.machine.session.CommunicationEvent

/** Session identity disambiguates sequence numbers across explicit reconnects. */
data class ControllerTraffic(val sessionId: Long, val endpoint: ControllerEndpoint, val event: CommunicationEvent)
