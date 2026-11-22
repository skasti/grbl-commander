package no.grblcommander.machine.session

import java.io.Closeable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import no.grblcommander.machine.protocol.ControllerStatus

/** Application integration boundary, owned above the UI mode/screen lifetime. */
interface MachineSession : Closeable {
    val state: StateFlow<MachineSnapshot>
    /** Passive, bounded, non-blocking multicast. Replays a bounded recent buffer. */
    val communication: Flow<CommunicationEvent>
    /** Snapshot of the bounded recent replay tail, including events not yet handled by a collector. */
    val recentCommunication: List<CommunicationEvent>
    /** Events evicted before handoff to at least one subscriber, counted once per event; not byte loss. */
    val droppedCommunicationEvents: StateFlow<Long>
    /** Connect and await a controller status report; never reset or unlock implicitly. */
    suspend fun connect()
    /**
     * One line; acknowledgment is not execution completion. A non-critical interrupted
     * line can drain its own late ok/error; critical events require explicit reset.
     * Neither blocks status access. Interrupted queued lines are never replayed.
     */
    suspend fun executeLine(command: String): CommandAcknowledgment
    /** A session-local fence invalidated by a reset, alarm or uncertain response. */
    val normalCommandEpoch: Long get() = 0L
    /** Submit a line only if the batch's original correlation fence still holds. */
    suspend fun executeLine(command: String, expectedEpoch: Long, onSent: () -> Unit = {}): CommandAcknowledgment {
        if (expectedEpoch != normalCommandEpoch) throw CommandCorrelationException("Normal command batch interrupted")
        return executeLine(command).also { onSent() }
    }
    /**
     * Bypasses normal-command admission and acknowledgment waits, including during
     * alarm/uncertain normal-command outcomes. Exceptions propagate without replay;
     * a usable transport remains connected. A successfully or uncertainly transmitted
     * soft reset uses reset() to establish a fresh response boundary on the same transport.
     */
    suspend fun sendRealtime(command: RealtimeCommand)
    /**
     * Explicitly reset once and await an initialization banner plus a fresh status report
     * on the same transport. Never replay old lines, unlock or home automatically.
     * A remaining alarm/resetRequired flag must still be handled by the application.
     */
    suspend fun reset()
    /** Request and await a subsequent controller report, independently of normal commands. */
    suspend fun queryStatus(): ControllerStatus
    /**
     * Prepare a lazy task owned by this session. The caller starts it after the handshake.
     * Closing the session cancels the task, and awaitClosed waits for its cleanup.
     */
    fun prepareSessionTask(block: suspend () -> Unit): Job
    /** Close is idempotent; wait for owned coroutines after closing when disposing a service. */
    suspend fun awaitClosed()
}

