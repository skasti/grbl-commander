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
    val manualCommandTransmitted: Boolean = false,
) {
    fun canExecuteLine(now: Instant = Instant.now()): Boolean = !closing && !manualCommandPending &&
        !reportIsStale(now) && snapshot.normalCommandsSynchronized &&
        !snapshot.resetInProgress && !snapshot.resetRequired &&
        snapshot.status?.executionState?.substringBefore(':') in setOf("Idle", "Alarm", "Check")

    /** No Start from Idle or resume of work whose application owner is unknown. */
    fun canCycleStart(now: Instant = Instant.now()): Boolean = !closing && manualCommandPending && manualCommandTransmitted &&
        !reportIsStale(now) && snapshot.status?.executionState == "Hold:0" &&
        snapshot.alarmCode == null && !snapshot.resetInProgress && !snapshot.resetRequired &&
        snapshot.normalCommandsSynchronized

    fun reportIsStale(now: Instant = Instant.now()): Boolean =
        snapshot.connection != MachineSnapshot.Connection.CONNECTED || snapshot.reportedAt?.let {
            Duration.between(it, now) > Duration.ofSeconds(3)
        } != false
}
