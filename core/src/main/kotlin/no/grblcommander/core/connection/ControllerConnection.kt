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
        var postConnect: Job? = null
        val observers = mutableListOf<Job>()
        var dropped = 0L
        var lastObservedSequence = 0L
        private val closed = AtomicBoolean()
        fun close() { if (closed.compareAndSet(false, true)) session.close() }
    }

    init { owner.invokeOnCompletion { close() } }

    private var manualLifecycleIntent = 0L

    /** Startup shares the ordinary lifecycle lock and never replaces a manual operation. */
    internal suspend fun connectAtStartup(endpoint: ControllerEndpoint) = establish(endpoint, automatic = true)

    internal fun startupFailed(error: Throwable) = synchronized(gate) {
        if (!closed && manualLifecycleIntent == 0L) mutableState.value = state.value.copy(
            snapshot = state.value.snapshot.copy(connection = MachineSnapshot.Connection.FAILED,
                failure = error.message ?: error.javaClass.simpleName, normalCommandsSynchronized = false),
        )
    }

    /** Explicit connect/switch; the old one-shot session is fully disposed before creating another. */
    suspend fun connect(endpoint: ControllerEndpoint, resetConfirmed: Boolean = false) {
        check(!endpoint.postConnect.resetController || resetConfirmed) { "Confirm controller reset for this connection first" }
        establish(endpoint, automatic = false)
    }

    private suspend fun establish(endpoint: ControllerEndpoint, automatic: Boolean) {
        if (!automatic) synchronized(gate) { manualLifecycleIntent++ }
        val entry = lifecycle.withLock {
            if (automatic && synchronized(gate) { manualLifecycleIntent != 0L || active != null }) return
            checkOpen()
            currentCoroutineContext().ensureActive()
            stopActive()
            currentCoroutineContext().ensureActive()
            synchronized(gate) {
                checkOpen()
                if (automatic && manualLifecycleIntent != 0L) return
                val session = try { sessionFactory(endpoint, scope) } catch (error: Throwable) {
                    mutableState.value = ControllerConnectionState(endpoint, MachineSnapshot(
                        connection = MachineSnapshot.Connection.FAILED, failure = error.message,
                        normalCommandsSynchronized = false, resetRequired = true,
                    ))
                    throw error
                }
                val id = ++nextSessionId
                val entry = Active(session, id, endpoint)
                val actions = endpoint.postConnect
                if (actions.resetController || actions.queries().isNotEmpty()) {
                    entry.postConnect = session.prepareSessionTask { runPostConnect(entry, automatic) }
                }
                active = entry
                mutableState.value = ControllerConnectionState(
                    endpoint, session.state.value, postConnectPending = entry.postConnect != null,
                )
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
                        entry.postConnect?.start()
                    }
                    catch (error: Throwable) {
                        withContext(NonCancellable) { entry.close(); session.awaitClosed() }
                        synchronized(gate) {
                            if (active === entry && !closed && !state.value.closing) mutableState.value = state.value.copy(snapshot = session.state.value, postConnectPending = false)
                        }
                        throw error
                    }
                }
                entry.connecting.start()
                entry.postConnect?.let { task ->
                    entry.observers += scope.launch {
                        // Joining a lazy task starts it, so wait until handshake has started or disposed it.
                        entry.connecting.join()
                        task.join()
                        synchronized(gate) {
                            if (active === entry) mutableState.value = state.value.copy(postConnectPending = false)
                        }
                    }
                }
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

    /** One session owns all normal responses; failures never replay a query or imply transport loss. */
    private suspend fun runPostConnect(entry: Active, automatic: Boolean) {
        val session = entry.session
        val actions = entry.endpoint.postConnect
        fun result(text: String) = synchronized(gate) {
            if (active === entry) mutableState.value = state.value.copy(
                postConnectResults = state.value.postConnectResults + text,
            )
        }
        if (actions.resetController && automatic) result("Controller reset skipped: startup has no operator consent.")
        if (actions.resetController && !automatic) {
            try { session.reset(); result("Controller reset synchronized.") }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                result("Controller reset failed: ${error.message}. Queries skipped.")
                return
            }
        }
        val queries = actions.queries()
        for ((index, query) in queries.withIndex()) {
            currentCoroutineContext().ensureActive()
            val snapshot = session.state.value
            if (snapshot.connection != MachineSnapshot.Connection.CONNECTED || !snapshot.normalCommandsSynchronized ||
                snapshot.resetRequired || snapshot.resetInProgress) {
                result("$query and remaining queries skipped: normal command correlation unavailable.")
                break
            }
            try {
                val acknowledgment = session.executeLine(query)
                result(if (acknowledgment.accepted) "$query acknowledged." else "$query rejected: error:${acknowledgment.errorCode}.")
            } catch (error: Exception) {
                // A delayed response may restore correlation before this coroutine observes the failure.
                currentCoroutineContext().ensureActive()
                result("$query failed: ${error.message}")
                queries.getOrNull(index + 1)?.let { next ->
                    result("$next and remaining queries skipped: previous query was interrupted.")
                }
                break
            }
        }
    }

    /** Choose another endpoint without opening it. There is no automatic reconnect. */
    suspend fun reconfigure(endpoint: ControllerEndpoint) {
        synchronized(gate) { manualLifecycleIntent++ }
        lifecycle.withLock {
            checkOpen()
            currentCoroutineContext().ensureActive()
            stopActive()
            currentCoroutineContext().ensureActive()
            synchronized(gate) { checkOpen(); mutableState.value = ControllerConnectionState(endpoint) }
        }
    }

    suspend fun disconnect() {
        synchronized(gate) { manualLifecycleIntent++ }
        lifecycle.withLock {
            checkOpen()
            currentCoroutineContext().ensureActive()
            stopActive()
            synchronized(gate) {
                if (!closed) mutableState.value = state.value.copy(
                    snapshot = state.value.snapshot.copy(connection = MachineSnapshot.Connection.DISCONNECTED, failure = null), closing = false,
                )
            }
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
                    closing = false, manualCommandPending = false, postConnectPending = false,
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
            selected.also { mutableState.value = state.value.copy(manualCommandPending = true) }
        }
        try { return selected.executeLine(command) }
        finally { synchronized(gate) {
            if (active?.session === selected) mutableState.value = state.value.copy(manualCommandPending = false)
        } }
    }
    suspend fun sendRealtime(command: RealtimeCommand) {
        if (command == RealtimeCommand.SOFT_RESET) return reset()
        val selected = synchronized(gate) {
            val current = session()
            if (command == RealtimeCommand.CYCLE_START) check(state.value.copy(snapshot = current.state.value).canCycleStart()) {
                "Cycle Start requires a fresh synchronized Idle or Hold:0 report without an alarm or reset"
            }
            current
        }
        selected.sendRealtime(command)
    }
    suspend fun reset() {
        val selected = synchronized(gate) { activeSession() }
        val connection = selected.session.state.value.connection
        if (connection != MachineSnapshot.Connection.CONNECTED) throw IOException("Session is $connection")
        currentCoroutineContext().ensureActive()
        // A completed reset can restore normal admission on this very session.
        selected.postConnect?.cancelAndJoin()
        selected.session.reset()
    }
    suspend fun queryStatus() = session().queryStatus()

    private fun session(): MachineSession = synchronized(gate) { activeSession().session }

    private fun activeSession(): Active {
        checkOpen()
        if (state.value.closing) throw IOException("Controller connection is closing")
        return active ?: throw IOException("No controller connected")
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
            mutableState.value = state.value.copy(
                snapshot = state.value.snapshot.copy(connection = MachineSnapshot.Connection.CLOSED, failure = null),
                closing = false, postConnectPending = false,
            )
        }
    }
}
