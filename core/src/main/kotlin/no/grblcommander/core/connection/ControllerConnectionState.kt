package no.grblcommander.core.connection

import java.time.Duration
import java.time.Instant
import no.grblcommander.machine.session.MachineSnapshot

data class ControllerConnectionState(
    val endpoint: ControllerEndpoint? = null,
    val snapshot: MachineSnapshot = MachineSnapshot(),
    val closing: Boolean = false,
    val dispatchOwner: ExecutionContext? = null,
    val activeExecution: ExecutionContext? = dispatchOwner,
    /** Results are diagnostics; successful acknowledgment does not imply motion completion. */
    val postConnectResults: List<String> = emptyList(),
    /** Covers the interval before the session starts its reset handshake. */
    val resetPending: Boolean = false,
    val operation: Operation? = null,
) {
    val manualCommandPending get() = dispatchOwner is ExecutionContext.Manual
    val postConnectPending get() = dispatchOwner is ExecutionContext.PostConnect
    val operationPending get() = dispatchOwner is Operation

    fun canExecuteLine(now: Instant = Instant.now()): Boolean = commandBlockReason(now) == null

    fun commandBlockReason(now: Instant = Instant.now()): String? = when {
        closing -> "Controller connection is closing."
        snapshot.connection != MachineSnapshot.Connection.CONNECTED -> "Controller is disconnected."
        resetPending || snapshot.resetInProgress -> "Controller reset is in progress."
        snapshot.resetRequired -> "Controller reset is required before sending commands."
        !snapshot.normalCommandsSynchronized -> "Waiting for command responses to synchronize."
        dispatchOwner != null -> "Waiting for ${dispatchOwner.description} to finish."
        reportIsStale(now) -> "Waiting for a fresh controller status report."
        snapshot.status?.executionState?.substringBefore(':') !in setOf("Idle", "Alarm", "Check") ->
            "Controller is in ${snapshot.status?.executionState ?: "an unknown state"}."
        else -> null
    }

    /** Explicit start/resume follows controller state, independently of command acknowledgment. */
    fun canCycleStart(now: Instant = Instant.now()): Boolean = !closing && !resetPending &&
        !reportIsStale(now) && ((dispatchOwner?.permitsHeldResume != false && snapshot.status?.executionState == "Hold:0") ||
            (dispatchOwner?.permitsIdleStart != false && snapshot.status?.executionState == "Idle")) &&
        snapshot.alarmCode == null && !snapshot.resetInProgress && !snapshot.resetRequired &&
        snapshot.normalCommandsSynchronized

    fun reportIsStale(now: Instant = Instant.now()): Boolean =
        snapshot.connection != MachineSnapshot.Connection.CONNECTED || snapshot.reportedAt?.let {
            Duration.between(it, now) > Duration.ofSeconds(3)
        } != false
}
