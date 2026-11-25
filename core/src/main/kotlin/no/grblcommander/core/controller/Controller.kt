package no.grblcommander.core.controller

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.grblcommander.core.connection.ControllerEndpoint
import no.grblcommander.core.connection.controllerSession
import no.grblcommander.machine.session.*

/** Exclusive owner of one connection and at most one accepted execution. */
class Controller(
    parent: CoroutineScope,
    private val capacity: Int = 8,
    private val now: () -> Instant = Instant::now,
) {
    private val owner = SupervisorJob(requireNotNull(parent.coroutineContext[Job]))
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private val gate = Any()
    private val lifecycle = Mutex()
    private val realtime = Mutex()
    private val machine = MutableStateFlow(MachineSnapshot())
    val state = machine.asStateFlow()
    private val active = MutableStateFlow<Operation?>(null)
    val activeOperation = active.asStateFlow()

    private class Run(val operation: Operation, val job: CompletableJob, val buffer: CommandBuffer, val epoch: Long, var holdSequence: Long) {
        var held = false
        var resumeAfter: Long? = null
    }

    private var session: MachineSession? = null
    private var observer: Job? = null
    private var run: Run? = null
    private var transitioning = false
    private var readyAfter = -1L

    init { require(capacity in 1 until Int.MAX_VALUE) }

    suspend fun connect(endpoint: ControllerEndpoint) = replace { controllerSession(endpoint, scope) }
    internal suspend fun connect(session: MachineSession) = replace { session }

    private suspend fun replace(create: () -> MachineSession) = lifecycle.withLock {
        check(owner.isActive)
        disposeSession()
        val next = create()
        try {
            next.connect()
            synchronized(gate) {
                check(owner.isActive)
                readyAfter = -1
                session = next
                machine.value = next.state.value
                observer = scope.launch {
                    next.state.collect {
                        synchronized(gate) {
                            if (session === next) observe(next)
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            next.close()
            withContext(NonCancellable) { next.awaitClosed() }
            throw failure
        }
    }

    fun run(operation: Operation) = synchronized(gate) {
        val current = checkNotNull(session)
        val epoch = current.normalCommandEpoch
        val snapshot = current.state.value
        check(owner.isActive && !transitioning && run == null && snapshot.usable() &&
            fresh(snapshot) && snapshot.status?.executionState == "Idle" && snapshot.reportSequence > readyAfter)
        check(operation.reserve()) { "Operation already accepted" }
        val job = Job(owner)
        val execution = CoroutineScope(scope.coroutineContext + job)
        val buffer = CommandBuffer(current, execution, capacity, epoch) { command, result ->
            synchronized(gate) {
                operation.outcome(command, result, current.state.value.reportSequence)
                if (session === current && run?.operation === operation) observe(current)
            }
        }
        val accepted = Run(operation, job, buffer, epoch, snapshot.lastHoldSequence)
        run = accepted
        active.value = operation
        job.invokeOnCompletion { cause ->
            synchronized(gate) {
                if (run === accepted) {
                    operation.interrupt(cause?.message ?: "Execution ended")
                    if (operation.progress.value.phase != Operation.Phase.COMPLETED)
                        readyAfter = current.state.value.reportSequence
                    run = null
                    active.value = null
                }
            }
        }
        execution.launch {
            try {
                buffer.start()
                operation.produce(buffer)
                buffer.awaitClosed()
                operation.progress.first { it.phase in terminal }
            } finally {
                buffer.close()
                job.complete()
            }
        }
        Unit
    }

    private fun observe(current: MachineSession) {
        val snapshot = current.state.value
        machine.value = snapshot
        val execution = run ?: return
        if (transitioning || !snapshot.usable() || current.normalCommandEpoch != execution.epoch) {
            stop(execution)
            return
        }
        val mode = snapshot.status?.executionState
        if (snapshot.lastHoldSequence > execution.holdSequence) {
            execution.holdSequence = snapshot.lastHoldSequence
            execution.held = true
            execution.resumeAfter = null
            execution.buffer.`suspend`()
        }
        val fresh = fresh(snapshot)
        val after = execution.resumeAfter
        if (after != null && fresh && snapshot.reportSequence > after && (mode == "Run" || mode == "Idle")) {
            execution.resumeAfter = null
            execution.held = false
            execution.buffer.resume()
        }
        execution.operation.observe(snapshot.status?.executingLineNumber.takeIf { fresh }, snapshot.reportSequence,
            fresh && mode == "Idle" && !execution.held)
    }

    suspend fun sendRealtime(command: RealtimeCommand) {
        if (command == RealtimeCommand.SOFT_RESET) return reset()
        realtime.withLock {
            val current: MachineSession
            val execution: Run?
            synchronized(gate) {
                current = checkNotNull(session)
                check(!transitioning)
                execution = run
                if (command == RealtimeCommand.FEED_HOLD) {
                    execution?.held = true
                    execution?.resumeAfter = null
                    execution?.buffer?.`suspend`()
                }
                if (command == RealtimeCommand.CYCLE_START && execution != null) {
                    observe(current)
                    val snapshot = current.state.value
                    check(snapshot.usable() && fresh(snapshot) && snapshot.status?.executionState == "Hold:0")
                }
            }
            current.sendRealtime(command)
            if (command == RealtimeCommand.CYCLE_START && execution != null) synchronized(gate) {
                if (!transitioning && session === current && run === execution) {
                    execution.resumeAfter = current.state.value.reportSequence
                }
            }
        }
    }

    suspend fun reset() = lifecycle.withLock {
        val current: MachineSession
        val execution = synchronized(gate) {
            current = checkNotNull(session)
            transitioning = true
            run?.also(::stop)
        }
        try {
            withContext(NonCancellable) {
                execution?.job?.join()
                current.reset()
            }
        } finally {
            synchronized(gate) { transitioning = false }
        }
    }

    suspend fun disconnect() = lifecycle.withLock { disposeSession() }

    suspend fun close() = lifecycle.withLock {
        withContext(NonCancellable) {
            disposeSession()
            owner.cancelAndJoin()
        }
    }

    private suspend fun disposeSession() = withContext(NonCancellable) {
        val current: MachineSession
        val execution = synchronized(gate) {
            current = session ?: return@withContext
            transitioning = true
            run?.also(::stop)
        }
        current.close()
        execution?.job?.join()
        observer?.cancelAndJoin()
        current.awaitClosed()
        synchronized(gate) {
            machine.value = current.state.value
            session = null
            observer = null
            transitioning = false
        }
    }

    private fun stop(execution: Run) {
        execution.buffer.close()
        execution.job.cancel()
    }

    private fun fresh(snapshot: MachineSnapshot): Boolean {
        val age = Duration.between(snapshot.reportedAt ?: return false, now())
        return !age.isNegative && age <= Duration.ofSeconds(3)
    }

    private fun MachineSnapshot.usable() = connection == MachineSnapshot.Connection.CONNECTED &&
        normalCommandsSynchronized && !resetInProgress && !resetRequired && alarmCode == null && status?.isAlarm != true

    private companion object {
        val terminal = setOf(Operation.Phase.COMPLETED, Operation.Phase.INTERRUPTED, Operation.Phase.FAILED)
    }
}
