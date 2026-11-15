package no.grblcommander.machine.session

import java.time.Instant

/** Diagnostic observation, never an execution/delivery receipt. Sequence is session-local. */
data class CommunicationEvent(
    val sequence: Long,
    val timestamp: Instant,
    val direction: Direction,
    val category: Category,
    /** Exact decoded RX line or TX line including its terminating LF; realtime is visible ASCII/hex. */
    val text: String,
    val outcome: Outcome,
    /** Failure observations refer to the preceding transport handoff attempt. */
    val attemptSequence: Long? = null,
    val detail: String? = null,
) {
    enum class Direction { TX, RX }
    enum class Category { COMMAND, REALTIME, STATUS, ACKNOWLEDGMENT, ERROR, ALARM, STARTUP, INFORMATION }
    enum class Outcome { RECEIVED, ATTEMPTED, LOCAL_REJECTION, UNCERTAIN }
}
