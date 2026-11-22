package no.grblcommander.core.connection

import java.time.Duration
import java.time.Instant
import no.grblcommander.machine.session.MachineSnapshot

data class ControllerConnectionState(
    val endpoint: ControllerEndpoint? = null,
    val snapshot: MachineSnapshot = MachineSnapshot(),
    val closing: Boolean = false,
    /** Owns the manual line slot until acknowledgment; not proof of motion. */
    val manualCommandPending: Boolean = false,
    val postConnectPending: Boolean = false,
    /** Results are diagnostics; successful acknowledgment does not imply motion completion. */
    val postConnectResults: List<String> = emptyList(),
    /** Covers the interval before the session starts its reset handshake. */
    val resetPending: Boolean = false,
    val operation: Operation? = null,
    /** A run owns normal dispatch, including while it waits for controller completion. */
    val operationPending: Boolean = false,
) {
    fun canExecuteLine(now: Instant = Instant.now()): Boolean = !closing && !resetPending && !manualCommandPending && !postConnectPending && !operationPending &&
        !reportIsStale(now) && snapshot.normalCommandsSynchronized &&
        !snapshot.resetInProgress && !snapshot.resetRequired &&
        snapshot.status?.executionState?.substringBefore(':') in setOf("Idle", "Alarm", "Check")

    /** Explicit start/resume follows controller state, independently of command acknowledgment. */
    fun canCycleStart(now: Instant = Instant.now()): Boolean = !closing && !resetPending && !postConnectPending &&
        !reportIsStale(now) && (snapshot.status?.executionState == "Hold:0" ||
            (!operationPending && snapshot.status?.executionState == "Idle")) &&
        snapshot.alarmCode == null && !snapshot.resetInProgress && !snapshot.resetRequired &&
        snapshot.normalCommandsSynchronized

    fun reportIsStale(now: Instant = Instant.now()): Boolean =
        snapshot.connection != MachineSnapshot.Connection.CONNECTED || snapshot.reportedAt?.let {
            Duration.between(it, now) > Duration.ofSeconds(3)
        } != false
}
