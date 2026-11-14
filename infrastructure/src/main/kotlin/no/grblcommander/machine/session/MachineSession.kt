package no.grblcommander.machine.session

import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow
import no.grblcommander.machine.protocol.ControllerStatus

/** Application integration boundary, owned above the UI mode/screen lifetime. */
interface MachineSession : Closeable {
    val state: StateFlow<MachineSnapshot>
    /** Connect and await a controller status report; never reset or unlock implicitly. */
    suspend fun connect()
    /**
     * One line; acknowledgment is not execution completion. An interrupted transmitted
     * line blocks normal admission until its late ok/error is drained, without blocking
     * real-time/status. Queued lines interrupted by an alarm/uncertainty are rejected.
     */
    suspend fun executeLine(command: String): CommandAcknowledgment
    /**
     * Bypasses normal-command admission and acknowledgment waits, including during
     * alarm/uncertain normal-command outcomes. Exceptions propagate without replay;
     * a usable transport remains connected. A successfully or uncertainly transmitted
     * soft reset ends the session; known local rejection before I/O does not.
     */
    suspend fun sendRealtime(command: RealtimeCommand)
    /** Request and await a subsequent controller report, independently of normal commands. */
    suspend fun queryStatus(): ControllerStatus
    /** Close is idempotent; wait for owned coroutines after closing when disposing a service. */
    suspend fun awaitClosed()
}
