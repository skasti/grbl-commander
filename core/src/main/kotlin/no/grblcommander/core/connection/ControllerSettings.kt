package no.grblcommander.core.connection

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
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

/** UI-independent settings and one-shot startup, using the application-owned connection. */
class ControllerSettings(
    val connection: ControllerConnection,
    private val preferences: ControllerEndpointPreferences = ControllerEndpointPreferences(),
) {
    private val startupGate = Any()
    private val uiReady = CompletableDeferred<Unit>()
    private var initialized = false
    private var manualOperation = false
    private var startupJob: Job? = null

    /** Called once by the application owner; no connection attempt precedes UI readiness. */
    suspend fun initialize() {
        val job = currentCoroutineContext()[Job]!!
        synchronized(startupGate) {
            if (initialized || manualOperation) return
            initialized = true
            startupJob = job
        }
        try {
            load()
            val endpoint = configuration.value ?: return
            uiReady.await()
            connection.connectAtStartup(endpoint)
        } catch (cancelled: CancellationException) {
            // Timeouts from the session have already recorded their connection failure.
            throw cancelled
        } catch (error: Exception) {
            connection.startupFailed(error)
        } catch (error: LinkageError) {
            connection.startupFailed(error)
        } finally {
            synchronized(startupGate) { if (startupJob === job) startupJob = null }
        }
    }

    fun onUiReady() { uiReady.complete(Unit) }

    private suspend fun stopStartup() {
        val job = synchronized(startupGate) {
            manualOperation = true
            startupJob
        }
        if (job !== currentCoroutineContext()[Job]) job?.cancelAndJoin()
    }

    private val configurationLock = Mutex()
    private val mutableConfiguration = MutableStateFlow<ControllerEndpoint?>(null)
    /** Saved configuration, independent of the endpoint of an active session and the UI's draft. */
    val configuration = mutableConfiguration.asStateFlow()
    private val mutableSavedDraft = MutableStateFlow(ControllerEndpointDraft())
    val savedDraft = mutableSavedDraft.asStateFlow()
    private val serialPorts = SerialPortDiscovery()

    suspend fun load(): ControllerEndpointDraft = configurationLock.withLock {
        val draft = withContext(Dispatchers.IO) { ControllerEndpointRecord.decodeDraft(preferences.load()) }
        mutableConfiguration.value = draft?.endpoint()
        mutableSavedDraft.value = draft ?: ControllerEndpointDraft()
        mutableSavedDraft.value
    }

    suspend fun save(draft: ControllerEndpointDraft) {
        val endpoint = draft.endpoint()
        stopStartup()
        persist(endpoint, draft)
    }

    private suspend fun persist(endpoint: ControllerEndpoint, draft: ControllerEndpointDraft) = configurationLock.withLock {
        withContext(Dispatchers.IO) { preferences.save(ControllerEndpointRecord.encode(endpoint, draft)) }
        mutableConfiguration.value = endpoint
        mutableSavedDraft.value = draft
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
        resetConfirmed: Boolean = false,
        onHandshakeComplete: () -> Unit = {},
    ) {
        val endpoint = draft.endpoint() // Validate before any connection lifecycle action.
        check(interruptionConfirmed || !requiresInterruptionConfirmation) { "Confirm interruption of the current connection first" }
        check(!endpoint.postConnect.resetController || resetConfirmed) { "Confirm controller reset for this connection first" }
        stopStartup()
        connection.connect(endpoint, resetConfirmed)
        onHandshakeComplete()
        // The handshake has completed. A storage failure must not hide actual connected health.
        try { persist(endpoint, draft) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { throw IOException("Controller connected, but settings could not be saved: ${error.message}", error) }
    }

    suspend fun disconnect(interruptionConfirmed: Boolean = false) {
        check(interruptionConfirmed || !requiresInterruptionConfirmation) { "Confirm interruption of the current connection first" }
        stopStartup()
        connection.disconnect()
    }
}
