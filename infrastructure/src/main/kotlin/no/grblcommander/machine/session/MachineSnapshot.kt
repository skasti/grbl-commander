package no.grblcommander.machine.session

import java.time.Instant
import no.grblcommander.machine.protocol.ControllerStatus

data class MachineSnapshot(
    val connection: Connection = Connection.DISCONNECTED,
    /** Last observation only. Check connection AND reportedAt before treating it as live. */
    val status: ControllerStatus? = null,
    val reportedAt: Instant? = null,
    val reportSequence: Long = 0,
    val lastMessage: String? = null,
    val failure: String? = null,
) {
    enum class Connection { DISCONNECTED, CONNECTING, CONNECTED, FAILED, CLOSED }
}
