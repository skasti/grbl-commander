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
import no.grblcommander.machine.session.CommandAcknowledgment
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
    private var nextOperationId = 0L
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
        val commands = CommandBuffer(session)
        var run: OperationRun? = null
        var workflowTask: Job? = null
        var workflowEpoch = -1L
        var lastManualReplySequence = -1L
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
                    ), operation = state.value.operation)
                    throw error
                }
                val id = ++nextSessionId
                val entry = Active(session, id, endpoint)
                active = entry
                mutableState.value = ControllerConnectionState(endpoint, session.state.value, operation = state.value.operation)
                val postConnect = preparePostConnect(entry, automatic)
                if (postConnect != null) {
                    val context = ExecutionContext.PostConnect(id)
                    reserve(entry, context)
                    postConnect.invokeOnCompletion { synchronized(gate) { release(entry, context) } }
                }
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
                        if (active === entry && !closed && !state.value.closing) {
                            if (entry.workflowEpoch != session.normalCommandEpoch) entry.workflowTask?.cancel()
                            refreshOperation(entry)
                            mutableState.value = state.value.copy(snapshot = session.state.value)
                        }
                    } }
                }
                entry.observers += scope.launch {
                    session.state.first { it.connection == MachineSnapshot.Connection.CONNECTED ||
                        it.connection == MachineSnapshot.Connection.FAILED || it.connection == MachineSnapshot.Connection.CLOSED }
                    while (isActive) {
                        delay(500)
                        val snapshot = synchronized(gate) {
                            if (active !== entry || closed || state.value.closing) return@launch
                            refreshOperation(entry)
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
                        postConnect?.start()
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

    private fun preparePostConnect(entry: Active, automatic: Boolean): Job? {
        val actions = entry.endpoint.postConnect
        val queries = actions.queries()
        if (!actions.resetController && queries.isEmpty()) return null

        fun result(text: String) = synchronized(gate) {
            if (active === entry) mutableState.value = state.value.copy(
                postConnectResults = state.value.postConnectResults + text,
            )
        }

        return entry.commands.prepareBatch(
            continueAfterError = true,
            prepareCommands = prepare@{
                if (actions.resetController && automatic) {
                    result("Controller reset skipped: startup has no operator consent.")
                } else if (actions.resetController) {
                    try {
                        entry.session.reset()
                        result("Controller reset synchronized.")
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        result("Controller reset failed: ${error.message}. Queries skipped.")
                        return@prepare emptyList()
                    }
                }
                queries
            },
            onResult = { line ->
                when (line) {
                    is CommandBuffer.LineResult.Acknowledged -> result(
                        if (line.acknowledgment.accepted) "${line.command} acknowledged."
                        else "${line.command} rejected: error:${line.acknowledgment.errorCode}."
                    )
                    is CommandBuffer.LineResult.Unavailable ->
                        result("${line.command} and remaining queries skipped: normal command correlation unavailable.")
                    is CommandBuffer.LineResult.Interrupted -> {
                        result("${line.command} failed: ${line.failure.message}")
                        line.next?.let { result("$it and remaining queries skipped: previous query was interrupted.") }
                    }
                }
            },
        )
    }

    /** Choose another endpoint without opening it. There is no automatic reconnect. */
    suspend fun reconfigure(endpoint: ControllerEndpoint) {
        synchronized(gate) { manualLifecycleIntent++ }
        lifecycle.withLock {
            checkOpen()
            currentCoroutineContext().ensureActive()
            stopActive()
            currentCoroutineContext().ensureActive()
            synchronized(gate) { checkOpen(); mutableState.value = ControllerConnectionState(endpoint, operation = state.value.operation) }
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
            active?.also {
                mutableState.value = state.value.copy(closing = true)
                interruptOperation(it, "Controller disconnected")
            }
        } ?: return@withContext
        old.close()
        old.connecting.cancelAndJoin()
        old.session.awaitClosed()
        old.observers.forEach { it.cancelAndJoin() }
        synchronized(gate) {
            finishObservations(old)
            old.run?.publish(old.commands.state.value, old.session.state.value, fresh = false)
            if (active === old) {
                active = null
                mutableState.value = state.value.copy(
                    // Explicit disposal is a normal lifecycle action, not a connection failure.
                    // Retain the last observation, but not the session's technical close reason.
                    snapshot = old.session.state.value.copy(connection = if (closed) MachineSnapshot.Connection.CLOSED
                        else MachineSnapshot.Connection.DISCONNECTED, failure = null),
                    closing = false, dispatchOwner = null, activeExecution = null, resetPending = false,
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

    /** Snapshot and start a source in the current session. The returned handle owns its progress. */
    fun startOperation(source: Operation.Source, sourceId: String, lines: List<String>): Operation {
        val sourceLines = lines.toList()
        val commands = OperationCommands(sourceLines)
        val run = synchronized(gate) {
            val entry = activeSession()
            val session = entry.session
            val epoch = session.normalCommandEpoch
            val snapshot = session.state.value
            if (!state.value.copy(snapshot = snapshot).canExecuteLine() ||
                snapshot.status?.executionState != "Idle" || snapshot.alarmCode != null ||
                snapshot.reportSequence <= entry.lastManualReplySequence) {
                throw IOException("Starting an operation requires a fresh Idle controller and exclusive normal dispatch")
            }
            val operation = Operation(++nextOperationId, entry.id, source, sourceId, sourceLines)
            OperationRun(operation, epoch).also { run ->
                run.batch = entry.commands.prepareBatch(
                    prepareCommands = { commands.commands },
                    controllerLineNumbers = commands.lineNumbers,
                    expectedEpoch = epoch,
                    beforeLine = {
                        session.state.first {
                            it.connection != MachineSnapshot.Connection.CONNECTED ||
                                session.normalCommandEpoch != epoch ||
                                (it.status?.executionState?.substringBefore(':') in setOf("Idle", "Run") &&
                                    !ControllerConnectionState(snapshot = it).reportIsStale())
                        }
                    },
                    onResult = { result -> synchronized(gate) { recordOperationReply(entry, run, result) } },
                )
                entry.run = run
                reserve(entry, operation, retained = operation)
                scope.launch {
                    entry.commands.state.collect {
                        synchronized(gate) {
                            if (active === entry && entry.run === run) refreshOperation(entry)
                        }
                    }
                }.also { observer ->
                    // Polling handles physical completion after the batch has finished.
                    run.batch.invokeOnCompletion { observer.cancel() }
                }
            }
        }
        run.batch.start()
        return run.operation
    }

    private fun recordOperationReply(entry: Active, run: OperationRun, result: CommandBuffer.LineResult) {
        if (active !== entry || entry.run !== run || run.finished) return
        val sequence = entry.session.state.value.reportSequence
        val buffer = entry.commands.state.value
        when (result) {
            is CommandBuffer.LineResult.Acknowledged -> {
                val error = result.acknowledgment.errorCode
                if (error != null) run.finish(Operation.Phase.FAILED, "Controller rejected line with error:$error", sequence, buffer)
                else if (buffer.lastSentLineNumber == run.operation.lines.size) run.finalReplySequence = sequence
            }
            is CommandBuffer.LineResult.Unavailable ->
                run.finish(Operation.Phase.INTERRUPTED, "Normal command correlation unavailable", sequence, buffer)
            is CommandBuffer.LineResult.Interrupted ->
                run.finish(Operation.Phase.INTERRUPTED, result.failure.message ?: "Command outcome unknown", sequence, buffer)
        }
        refreshOperation(entry)
    }

    /** Called under gate by the report observer, buffer observer and polling loop. */
    private fun refreshOperation(entry: Active) {
        val run = entry.run ?: return
        val snapshot = entry.session.state.value
        val fresh = !ControllerConnectionState(snapshot = snapshot).reportIsStale()
        val mode = snapshot.status?.executionState?.substringBefore(':')
        val invalid = when {
            closed || state.value.closing || snapshot.connection != MachineSnapshot.Connection.CONNECTED -> "Controller disconnected"
            snapshot.alarmCode != null || mode == "Alarm" -> "Controller alarm"
            state.value.resetPending || snapshot.resetInProgress || snapshot.resetRequired -> "Controller reset boundary"
            entry.session.normalCommandEpoch != run.epoch || !snapshot.normalCommandsSynchronized -> "Command correlation lost"
            fresh && mode !in setOf("Idle", "Run", "Hold") -> "Unexpected controller state: ${snapshot.status?.executionState}"
            run.batch.isCancelled -> "Command batch interrupted"
            else -> null
        }
        if (invalid != null) interruptOperation(entry, invalid)
        entry.commands.refreshExecution(snapshot)
        run.publish(entry.commands.state.value, snapshot, fresh)
        if (run.finished && state.value.activeExecution === run.operation) {
            mutableState.value = state.value.copy(activeExecution = null)
        }
        if (run.canRelease(snapshot, fresh && mode == "Idle", state.value.resetPending)) {
            entry.run = null
            release(entry, run.operation)
        }
    }

    private fun interruptOperation(entry: Active, detail: String) {
        val run = entry.run ?: return
        if (run.finished) return
        mutableState.value = state.value.copy(activeExecution = null)
        run.finish(Operation.Phase.INTERRUPTED, detail, entry.session.state.value.reportSequence, entry.commands.state.value)
        run.batch.cancel()
    }

    /** Callers hold gate while checking their admission policy and preparing the session task. */
    private fun reserve(entry: Active, context: ExecutionContext, retained: Operation? = state.value.operation) {
        check(active === entry && context.sessionId == entry.id && state.value.dispatchOwner == null)
        mutableState.value = state.value.copy(dispatchOwner = context, activeExecution = context, operation = retained)
    }

    private fun admit(entry: Active, context: ExecutionContext, admits: (MachineSnapshot) -> Boolean = { true }) {
        val snapshot = entry.session.state.value
        if (!state.value.copy(snapshot = snapshot).canExecuteLine() || !admits(snapshot)) {
            throw IOException("Normal command dispatch unavailable")
        }
        reserve(entry, context)
    }

    private fun release(entry: Active, context: ExecutionContext) {
        if (active === entry && state.value.dispatchOwner === context) {
            mutableState.value = state.value.copy(dispatchOwner = null, activeExecution = null)
        }
    }

    suspend fun executeLine(command: String): CommandAcknowledgment {
        GrblProtocol.encodeCommand(command)
        val (entry, execution, epoch) = synchronized(gate) {
            val entry = activeSession()
            val epoch = entry.session.normalCommandEpoch
            val execution = ExecutionContext.Manual(entry.id)
            admit(entry, execution)
            Triple(entry, execution, epoch)
        }
        try {
            return entry.commands.executeLine(command, epoch)
        } finally {
            synchronized(gate) {
                entry.lastManualReplySequence = entry.session.state.value.reportSequence
                release(entry, execution)
            }
        }
    }

    /** Runs commands and workflow cleanup in one session task under the same reservation. */
    internal suspend fun <T> executeWorkflow(
        context: (Long) -> ExecutionContext,
        admits: (MachineSnapshot) -> Boolean = { true },
        work: suspend (suspend (String) -> CommandAcknowledgment) -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        val result = kotlinx.coroutines.CompletableDeferred<T>()
        val task = synchronized(gate) {
            val entry = activeSession()
            val epoch = entry.session.normalCommandEpoch
            val execution = context(entry.id)
            admit(entry, execution, admits)
            val task = try { entry.session.prepareSessionTask {
                try {
                    result.complete(work { command ->
                        currentCoroutineContext().ensureActive()
                        synchronized(gate) {
                            check(active === entry && state.value.dispatchOwner === execution && !state.value.resetPending)
                        }
                        entry.commands.executeLine(command, epoch)
                    })
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
            } catch (failure: Throwable) {
                release(entry, execution)
                throw failure
            }
            entry.workflowEpoch = epoch
            entry.workflowTask = task
            task.invokeOnCompletion { cause ->
                synchronized(gate) {
                    if (entry.workflowTask === task) {
                        entry.workflowTask = null
                        entry.lastManualReplySequence = entry.session.state.value.reportSequence
                        release(entry, execution)
                    }
                }
                if (cause != null) result.completeExceptionally(cause)
            }
            task
        }
        task.start()
        try {
            task.join()
            return result.await()
        } finally {
            withContext(NonCancellable) { task.cancelAndJoin() }
        }
    }

    suspend fun sendRealtime(command: RealtimeCommand) {
        if (command == RealtimeCommand.SOFT_RESET) return reset()
        if (command == RealtimeCommand.CYCLE_START) return cycleStart(requireHold = false)
        session().sendRealtime(command)
    }

    /** Resume a confirmed feed hold without accepting Idle as an alternate action. */
    suspend fun resumeFeedHold() = cycleStart(requireHold = true)

    private suspend fun cycleStart(requireHold: Boolean) {
        val selected = synchronized(gate) {
            val current = activeSession().session
            val observed = state.value.copy(snapshot = current.state.value)
            check(observed.canCycleStart() && (!requireHold || observed.snapshot.status?.executionState == "Hold:0")) {
                if (requireHold) "Resume requires a fresh synchronized Hold:0 report"
                else "Cycle Start requires a fresh synchronized Idle or Hold:0 report without an alarm or reset"
            }
            current
        }
        selected.sendRealtime(RealtimeCommand.CYCLE_START)
    }

    suspend fun reset() {
        currentCoroutineContext().ensureActive()
        val selected = synchronized(gate) {
            val selected = activeSession()
            val connection = selected.session.state.value.connection
            if (connection != MachineSnapshot.Connection.CONNECTED) throw IOException("Session is $connection")
            if (state.value.resetPending) throw IOException("Reset already in progress")
            mutableState.value = state.value.copy(resetPending = true)
            interruptOperation(selected, "Controller reset requested")
            selected
        }
        try {
            selected.workflowTask?.cancelAndJoin()
            selected.commands.cancelBatch()
            selected.session.reset()
        } finally {
            synchronized(gate) {
                if (active === selected) mutableState.value = state.value.copy(
                    snapshot = if (closed || state.value.closing) state.value.snapshot else selected.session.state.value,
                    resetPending = false,
                )
            }
        }
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
            active?.let { interruptOperation(it, "Connection owner closed") }
            try { active?.close() } finally { owner.cancel() }
        }
    }

    suspend fun awaitClosed() {
        owner.join()
        active?.session?.awaitClosed()
        synchronized(gate) {
            active?.let {
                finishObservations(it)
                it.run?.publish(it.commands.state.value, it.session.state.value, fresh = false)
            }
            mutableState.value = state.value.copy(
                snapshot = state.value.snapshot.copy(connection = MachineSnapshot.Connection.CLOSED, failure = null),
                closing = false, dispatchOwner = null, activeExecution = null, resetPending = false,
            )
        }
    }
}
