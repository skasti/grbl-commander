package no.grblcommander.core.connection

import java.io.Closeable
import java.io.IOException
import java.util.Collections
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.grblcommander.machine.session.CommunicationEvent
import no.grblcommander.machine.session.MachineSession
import no.grblcommander.machine.session.MachineSnapshot
import no.grblcommander.machine.session.RealtimeCommand
import no.grblcommander.machine.protocol.GrblProtocol

/** App-lifetime command boundary. Screens never receive a transport or replace its session. */
class ControllerConnection(
    parentScope: CoroutineScope,
    private val sessionFactory: (ControllerEndpoint, CoroutineScope) -> MachineSession,
    private val diagnosticCapacity: Int = 5_000,
    trafficCapacity: Int = 1_024,
) : Closeable {
    constructor(parentScope: CoroutineScope) : this(parentScope, ::controllerSession)

    init { require(diagnosticCapacity > 0 && trafficCapacity > 0) }
    private val owner = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + owner)
    private val lifecycle = Mutex()
    private val gate = Any()
    private var closed = false
    private var nextSessionId = 0L
    private var active: Active? = null
    private val mutableState = MutableStateFlow(ControllerConnectionState())
    val state = mutableState.asStateFlow()
    private val mutableTraffic = MutableSharedFlow<ControllerTraffic>(
        extraBufferCapacity = trafficCapacity, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val traffic: SharedFlow<ControllerTraffic> = mutableTraffic.asSharedFlow()
    private val recentTraffic = ArrayDeque<ControllerTraffic>(diagnosticCapacity)
    private var cursor = 0L
    /** Logical traffic position, including gaps in the session stream. */
    val trafficCursor: Long get() = synchronized(gate) { cursor }

    /** Explicit, consistent diagnostic snapshot. No list is created during publication. */
    fun diagnosticSnapshot(): List<ControllerTraffic> = synchronized(gate) {
        Collections.unmodifiableList(recentTraffic.toList())
    }
    private val mutableDropped = MutableStateFlow(0L)
    /** Across every session, including failed handshakes. History eviction is separate. */
    val droppedCommunicationEvents = mutableDropped.asStateFlow()

    private class Active(val session: MachineSession, val id: Long, val endpoint: ControllerEndpoint) {
        lateinit var connecting: Deferred<Unit>
        val observers = mutableListOf<Job>()
        var dropped = 0L
        var lastObservedSequence = 0L
        var manualCommandAfterSequence = Long.MAX_VALUE
        var manualCommandText: String? = null
        private val closed = AtomicBoolean()
        fun close() { if (closed.compareAndSet(false, true)) session.close() }
    }

    init { owner.invokeOnCompletion { close() } }

    /** Explicit connect/switch; the old one-shot session is fully disposed before creating another. */
    suspend fun connect(endpoint: ControllerEndpoint) {
        val entry = lifecycle.withLock {
            checkOpen()
            currentCoroutineContext().ensureActive()
            stopActive()
            currentCoroutineContext().ensureActive()
            synchronized(gate) {
                checkOpen()
                val session = try { sessionFactory(endpoint, scope) } catch (error: Throwable) {
                    mutableState.value = ControllerConnectionState(endpoint, MachineSnapshot(
                        connection = MachineSnapshot.Connection.FAILED, failure = error.message,
                        normalCommandsSynchronized = false, resetRequired = true,
                    ))
                    throw error
                }
                val id = ++nextSessionId
                val entry = Active(session, id, endpoint)
                active = entry
                mutableState.value = ControllerConnectionState(endpoint, session.state.value)
                // Subscribe before connect, including the first status query/banner in history.
                entry.observers += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    session.communication.collect { event -> synchronized(gate) {
                        recordTraffic(entry, event)
                    } }
                }
                entry.observers += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    session.droppedCommunicationEvents.collect { count -> synchronized(gate) {
                        mutableDropped.value += count - entry.dropped
                        entry.dropped = count
                    } }
                }
                entry.observers += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    session.state.collect { synchronized(gate) {
                        if (active === entry && !closed && !state.value.closing) mutableState.value = state.value.copy(snapshot = session.state.value)
                    } }
                }
                entry.observers += scope.launch {
                    session.state.first { it.connection == MachineSnapshot.Connection.CONNECTED ||
                        it.connection == MachineSnapshot.Connection.FAILED || it.connection == MachineSnapshot.Connection.CLOSED }
                    while (isActive) {
                        delay(500)
                        val snapshot = synchronized(gate) {
                            if (active !== entry || closed || state.value.closing) return@launch
                            session.state.value
                        }
                        if (snapshot.connection != MachineSnapshot.Connection.CONNECTED) return@launch
                        if (snapshot.resetInProgress) continue // Reset owns its own status handshake.
                        try {
                            // Status has no acknowledgment slot; the existing reader owns all reports.
                            session.sendRealtime(RealtimeCommand.STATUS_QUERY)
                        } catch (_: Exception) {
                            // Session records I/O errors. Preserve cancellation, never reconnect here.
                            currentCoroutineContext().ensureActive()
                        }
                    }
                }
                entry.connecting = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        session.connect()
                        synchronized(gate) {
                            if (active === entry && !closed && !state.value.closing) mutableState.value = state.value.copy(snapshot = session.state.value)
                        }
                    }
                    catch (error: Throwable) {
                        withContext(NonCancellable) { entry.close(); session.awaitClosed() }
                        synchronized(gate) {
                            if (active === entry && !closed && !state.value.closing) mutableState.value = state.value.copy(snapshot = session.state.value)
                        }
                        throw error
                    }
                }
                entry.connecting.start()
                entry
            }
        }
        try { entry.connecting.await() }
        catch (error: CancellationException) {
            // Cancelling the operator's connect action must also cancel its handshake.
            withContext(NonCancellable) {
                entry.connecting.cancelAndJoin()
                entry.close()
                entry.session.awaitClosed()
            }
            throw error
        }
    }

    /** Choose another endpoint without opening it. There is no automatic reconnect. */
    suspend fun reconfigure(endpoint: ControllerEndpoint) = lifecycle.withLock {
        checkOpen()
        currentCoroutineContext().ensureActive()
        stopActive()
        currentCoroutineContext().ensureActive()
        synchronized(gate) { checkOpen(); mutableState.value = ControllerConnectionState(endpoint) }
    }

    suspend fun disconnect() = lifecycle.withLock {
        checkOpen()
        currentCoroutineContext().ensureActive()
        stopActive()
        synchronized(gate) {
            if (!closed) mutableState.value = state.value.copy(
                snapshot = state.value.snapshot.copy(connection = MachineSnapshot.Connection.DISCONNECTED, failure = null), closing = false,
            )
        }
    }

    private suspend fun stopActive() = withContext(NonCancellable) {
        val old = synchronized(gate) {
            active?.also { mutableState.value = state.value.copy(closing = true) }
        } ?: return@withContext
        old.close()
        old.connecting.cancelAndJoin()
        old.session.awaitClosed()
        old.observers.forEach { it.cancelAndJoin() }
        synchronized(gate) {
            finishObservations(old)
            if (active === old) {
                active = null
                mutableState.value = state.value.copy(
                    // Explicit disposal is a normal lifecycle action, not a connection failure.
                    // Retain the last observation, but not the session's technical close reason.
                    snapshot = old.session.state.value.copy(connection = if (closed) MachineSnapshot.Connection.CLOSED
                        else MachineSnapshot.Connection.DISCONNECTED, failure = null),
                    closing = false, manualCommandPending = false, manualCommandTransmitted = false,
                )
            }
        }
    }

    /** Collect the bounded tail after observers stop, without duplicating already recorded events. */
    private fun finishObservations(entry: Active) {
        mutableDropped.value += entry.session.droppedCommunicationEvents.value - entry.dropped
        entry.dropped = entry.session.droppedCommunicationEvents.value
        entry.session.recentCommunication.forEach { event ->
            if (event.sequence > entry.lastObservedSequence) recordTraffic(entry, event)
        }
    }

    /** Called under gate; ingestion is constant-space/constant-time per event. */
    private fun recordTraffic(entry: Active, event: CommunicationEvent) {
        if (active === entry && state.value.manualCommandPending &&
            event.sequence > entry.manualCommandAfterSequence && event.text == entry.manualCommandText &&
            event.category == CommunicationEvent.Category.COMMAND && event.outcome == CommunicationEvent.Outcome.ATTEMPTED) {
            mutableState.value = state.value.copy(manualCommandTransmitted = true)
        }
        if (event.sequence <= entry.lastObservedSequence) return
        // Advance over upstream gaps as well as observed events. A consumer's cursor
        // gaps then count the union of session/core losses, never their sum twice.
        cursor += event.sequence - entry.lastObservedSequence
        val traffic = ControllerTraffic(entry.id, entry.endpoint, event, cursor)
        if (recentTraffic.size == diagnosticCapacity) recentTraffic.removeFirst()
        recentTraffic.addLast(traffic)
        entry.lastObservedSequence = event.sequence
        mutableTraffic.tryEmit(traffic) // DROP_OLDEST may evict even when this returns true.
    }

    suspend fun executeLine(command: String): no.grblcommander.machine.session.CommandAcknowledgment {
        GrblProtocol.encodeCommand(command)
        val selected = synchronized(gate) {
            val selected = session()
            if (!state.value.copy(snapshot = selected.state.value).canExecuteLine()) throw IOException("Manual command unavailable: controller must be synchronized and idle")
            active!!.manualCommandAfterSequence = selected.recentCommunication.lastOrNull()?.sequence ?: 0L
            active!!.manualCommandText = command + "\n"
            selected.also { mutableState.value = state.value.copy(manualCommandPending = true, manualCommandTransmitted = false) }
        }
        try { return selected.executeLine(command) }
        finally { synchronized(gate) {
            if (active?.session === selected) mutableState.value = state.value.copy(manualCommandPending = false, manualCommandTransmitted = false)
        } }
    }
    suspend fun sendRealtime(command: RealtimeCommand) {
        val selected = synchronized(gate) {
            val current = session()
            if (command == RealtimeCommand.CYCLE_START) check(state.value.copy(snapshot = current.state.value).canCycleStart()) {
                "Resume requires a fresh Hold:0 report and a pending application-owned manual command"
            }
            current
        }
        selected.sendRealtime(command)
    }
    suspend fun reset() = session().reset()
    suspend fun queryStatus() = session().queryStatus()

    private fun session(): MachineSession = synchronized(gate) {
        checkOpen()
        if (state.value.closing) throw IOException("Controller connection is closing")
        active?.session ?: throw IOException("No controller connected")
    }

    private fun checkOpen() { check(!closed && owner.isActive) { "Controller connection owner closed" } }

    /** Immediately closes I/O; awaitClosed joins the session and all application observers. */
    override fun close() = synchronized(gate) {
        if (!closed) {
            closed = true
            mutableState.value = state.value.copy(closing = true)
            try { active?.close() } finally { owner.cancel() }
        }
    }

    suspend fun awaitClosed() {
        owner.join()
        active?.session?.awaitClosed()
        synchronized(gate) {
            active?.let { finishObservations(it) }
            mutableState.value = state.value.copy(snapshot = state.value.snapshot.copy(connection = MachineSnapshot.Connection.CLOSED, failure = null), closing = false)
        }
    }
}
