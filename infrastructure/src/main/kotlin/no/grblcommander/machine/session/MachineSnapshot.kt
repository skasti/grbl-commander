package no.grblcommander.machine.session

import java.time.Instant
import no.grblcommander.machine.protocol.ControllerStatus
import no.grblcommander.machine.protocol.ControllerAlarm

data class MachineSnapshot(
    val connection: Connection = Connection.DISCONNECTED,
    /** Last observation only. Check connection AND reportedAt before treating it as live. */
    val status: ControllerStatus? = null,
    val reportedAt: Instant? = null,
    val reportSequence: Long = 0,
    val lastMessage: String? = null,
    val failure: String? = null,
    /** Latest ALARM/status code; reset invalidates it, and non-Alarm reports clear it. */
    val alarmCode: Int? = null,
    val alarmDescription: String? = null,
    /** Protocol restriction, independent of transport health and the visible alarm. */
    val resetRequired: Boolean = false,
    val resetInProgress: Boolean = false,
    /** False while a line response, reset boundary or critical event remains unresolved. */
    val normalCommandsSynchronized: Boolean = true,
    /** Local write/query diagnostic, separate from controller messages and fatal failure. */
    val lastRealtimeFailure: String? = null,
) {
    val alarm: ControllerAlarm? get() = alarmCode?.let { ControllerAlarm.fromCode(it, alarmDescription) }

    enum class Connection { DISCONNECTED, CONNECTING, CONNECTED, FAILED, CLOSED }
}
