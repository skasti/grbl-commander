package no.grblcommander.desktop.connection

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.grblcommander.machine.session.MachineSnapshot

/** UI-independent explicit settings actions, using the application-owned connection. */
class ControllerSettings(
    val connection: ControllerConnection,
    private val preferences: ControllerEndpointPreferences = ControllerEndpointPreferences(),
) {
    suspend fun load(): ControllerEndpointDraft = withContext(Dispatchers.IO) {
        preferences.load()?.let(ControllerEndpointDraft::from) ?: ControllerEndpointDraft()
    }

    suspend fun save(draft: ControllerEndpointDraft) {
        val endpoint = draft.endpoint()
        withContext(Dispatchers.IO) { preferences.save(endpoint) }
    }

    val requiresInterruptionConfirmation: Boolean get() = connection.state.value.let {
        it.closing || it.snapshot.connection in setOf(
            MachineSnapshot.Connection.CONNECTED, MachineSnapshot.Connection.CONNECTING,
        )
    }

    suspend fun connect(draft: ControllerEndpointDraft, interruptionConfirmed: Boolean = false) {
        val endpoint = draft.endpoint() // Validate before any connection lifecycle action.
        check(interruptionConfirmed || !requiresInterruptionConfirmation) { "Confirm interruption of the current connection first" }
        connection.connect(endpoint)
        // The handshake has completed. A storage failure must not hide actual connected health.
        try { withContext(Dispatchers.IO) { preferences.save(endpoint) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { throw IOException("Controller connected, but settings could not be saved: ${error.message}", error) }
    }

    suspend fun disconnect(interruptionConfirmed: Boolean = false) {
        check(interruptionConfirmed || !requiresInterruptionConfirmation) { "Confirm interruption of the current connection first" }
        connection.disconnect()
    }
}
