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
    /** Latest ALARM code; cleared only by a subsequent non-Alarm status report. */
    val alarmCode: Int? = null,
    /** False while an abandoned line's acknowledgment is outstanding or correlation is lost. */
    val normalCommandsSynchronized: Boolean = true,
    /** Local write/query diagnostic, separate from controller messages and fatal failure. */
    val lastRealtimeFailure: String? = null,
) {
    enum class Connection { DISCONNECTED, CONNECTING, CONNECTED, FAILED, CLOSED }
}
