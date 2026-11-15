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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.grblcommander.machine.session.CommunicationEvent
import no.grblcommander.machine.session.MachineSession
import no.grblcommander.machine.session.MachineSnapshot
import no.grblcommander.machine.session.RealtimeCommand

/** App-lifetime command boundary. Screens never receive a transport or replace its session. */
class ControllerConnection(
    parentScope: CoroutineScope,
    private val sessionFactory: (ControllerEndpoint, CoroutineScope) -> MachineSession,
    private val historyCapacity: Int = 5_000,
) : Closeable {
    constructor(parentScope: CoroutineScope) : this(parentScope, ::controllerSession)

    init { require(historyCapacity > 0) }
    private val owner = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + owner)
    private val lifecycle = Mutex()
    private val gate = Any()
    private var closed = false
    private var nextSessionId = 0L
    private var active: Active? = null
    private val mutableState = MutableStateFlow(ControllerConnectionState())
    val state = mutableState.asStateFlow()
    private val mutableHistory = MutableStateFlow<List<ControllerTraffic>>(emptyList())
    val history = mutableHistory.asStateFlow()
    private val recentTraffic = ArrayDeque<ControllerTraffic>(historyCapacity)
    private var historyDirty = false
    private val mutableDropped = MutableStateFlow(0L)
    /** Across every session, including failed handshakes. History eviction is separate. */
    val droppedCommunicationEvents = mutableDropped.asStateFlow()

    private class Active(val session: MachineSession, val id: Long, val endpoint: ControllerEndpoint) {
        lateinit var connecting: Deferred<Unit>
        val observers = mutableListOf<Job>()
        var dropped = 0L
        var lastObservedSequence = 0L
        private val closed = AtomicBoolean()
        fun close() { if (closed.compareAndSet(false, true)) session.close() }
    }

    init {
        owner.invokeOnCompletion { close() }
        scope.launch {
            while (isActive) {
                delay(50) // At most 20 immutable snapshots/s, independent of traffic rate.
                synchronized(gate) { publishHistory() }
            }
        }
    }

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
                        if (active === entry && !closed) mutableState.value = state.value.copy(snapshot = session.state.value)
                    } }
                }
                entry.connecting = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        session.connect()
                        synchronized(gate) {
                            if (active === entry && !closed) mutableState.value = state.value.copy(snapshot = session.state.value)
                        }
                    }
                    catch (error: Throwable) {
                        withContext(NonCancellable) { entry.close(); session.awaitClosed() }
                        synchronized(gate) {
                            if (active === entry && !closed) mutableState.value = state.value.copy(snapshot = session.state.value)
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
                snapshot = state.value.snapshot.copy(connection = MachineSnapshot.Connection.DISCONNECTED), closing = false,
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
                    snapshot = old.session.state.value.copy(connection = if (closed) MachineSnapshot.Connection.CLOSED
                        else MachineSnapshot.Connection.DISCONNECTED),
                    closing = false,
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
        publishHistory()
    }

    /** Called under gate; ingestion is constant-space/constant-time per event. */
    private fun recordTraffic(entry: Active, event: CommunicationEvent) {
        if (recentTraffic.size == historyCapacity) recentTraffic.removeFirst()
        recentTraffic.addLast(ControllerTraffic(entry.id, entry.endpoint, event))
        entry.lastObservedSequence = event.sequence
        historyDirty = true
    }

    private fun publishHistory() {
        if (historyDirty) {
            mutableHistory.value = Collections.unmodifiableList(recentTraffic.toList())
            historyDirty = false
        }
    }

    suspend fun executeLine(command: String) = session().executeLine(command)
    suspend fun sendRealtime(command: RealtimeCommand) = session().sendRealtime(command)
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
            mutableState.value = state.value.copy(snapshot = state.value.snapshot.copy(connection = MachineSnapshot.Connection.CLOSED), closing = false)
        }
    }
}
