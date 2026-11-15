package no.grblcommander.core.connection

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.grblcommander.configuration.ControllerEndpointPreferences
import no.grblcommander.machine.session.MachineSnapshot
import no.grblcommander.machine.transport.SerialPortDiscovery

/** UI-independent explicit settings actions, using the application-owned connection. */
class ControllerSettings(
    val connection: ControllerConnection,
    private val preferences: ControllerEndpointPreferences = ControllerEndpointPreferences(),
) {
    private val configurationLock = Mutex()
    private val mutableConfiguration = MutableStateFlow<ControllerEndpoint?>(null)
    /** Saved configuration, independent of the endpoint of an active session and the UI's draft. */
    val configuration = mutableConfiguration.asStateFlow()
    private val serialPorts = SerialPortDiscovery()

    suspend fun load(): ControllerEndpointDraft = configurationLock.withLock {
        val endpoint = withContext(Dispatchers.IO) { ControllerEndpointRecord.decode(preferences.load()) }
        mutableConfiguration.value = endpoint
        endpoint?.let(ControllerEndpointDraft::from) ?: ControllerEndpointDraft()
    }

    suspend fun save(draft: ControllerEndpointDraft) {
        persist(draft.endpoint())
    }

    private suspend fun persist(endpoint: ControllerEndpoint) = configurationLock.withLock {
        withContext(Dispatchers.IO) { preferences.save(ControllerEndpointRecord.encode(endpoint)) }
        mutableConfiguration.value = endpoint
    }

    suspend fun discoverSerialPorts() = serialPorts.discover()

    val requiresInterruptionConfirmation: Boolean get() = connection.state.value.let {
        it.closing || it.snapshot.connection in setOf(
            MachineSnapshot.Connection.CONNECTED, MachineSnapshot.Connection.CONNECTING,
        )
    }

    /** Reports successful handshake in the caller's context before persisting the endpoint. */
    suspend fun connect(
        draft: ControllerEndpointDraft,
        interruptionConfirmed: Boolean = false,
        onHandshakeComplete: () -> Unit = {},
    ) {
        val endpoint = draft.endpoint() // Validate before any connection lifecycle action.
        check(interruptionConfirmed || !requiresInterruptionConfirmation) { "Confirm interruption of the current connection first" }
        connection.connect(endpoint)
        onHandshakeComplete()
        // The handshake has completed. A storage failure must not hide actual connected health.
        try { persist(endpoint) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { throw IOException("Controller connected, but settings could not be saved: ${error.message}", error) }
    }

    suspend fun disconnect(interruptionConfirmed: Boolean = false) {
        check(interruptionConfirmed || !requiresInterruptionConfirmation) { "Confirm interruption of the current connection first" }
        connection.disconnect()
    }
}
