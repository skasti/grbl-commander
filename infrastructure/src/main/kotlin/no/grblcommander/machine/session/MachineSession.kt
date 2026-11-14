package no.grblcommander.machine.session

import java.io.Closeable
import kotlinx.coroutines.flow.StateFlow
import no.grblcommander.machine.protocol.ControllerStatus

/** Application integration boundary, owned above the UI mode/screen lifetime. */
interface MachineSession : Closeable {
    val state: StateFlow<MachineSnapshot>
    /** Connect and await a controller status report; never reset or unlock implicitly. */
    suspend fun connect()
    /** One line; the return value acknowledges parsing/acceptance, not execution completion. */
    suspend fun executeLine(command: String): CommandAcknowledgment
    /** Bypasses normal-command admission and the acknowledgment wait. */
    suspend fun sendRealtime(command: RealtimeCommand)
    /** Request and await a subsequent controller report, independently of normal commands. */
    suspend fun queryStatus(): ControllerStatus
    /** Close is idempotent; wait for owned coroutines after closing when disposing a service. */
    suspend fun awaitClosed()
}
