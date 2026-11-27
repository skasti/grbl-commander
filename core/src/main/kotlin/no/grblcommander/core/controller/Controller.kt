package no.grblcommander.core.controller

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.grblcommander.core.commands.CommandBuffer
import no.grblcommander.core.connection.ControllerEndpoint
import no.grblcommander.core.connection.controllerSession
import no.grblcommander.machine.session.*

/** Exclusive owner of one connection and at most one accepted execution. */
class Controller(
    parent: CoroutineScope,
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

    private var session: MachineSession? = null
    private var observer: Job? = null
    private var statusPoller: Job? = null
    private var transitioning = false
    private var readyAfter = -1L
    private var activeEpoch: Long? = null

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
                statusPoller = scope.launch { pollStatus(next) }
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
        check(owner.isActive && !transitioning && active.value == null && snapshot.usable() &&
            snapshot.isFresh(now) && snapshot.status?.executionState == "Idle" && snapshot.reportSequence > readyAfter)
        val commandBuffer = CommandBuffer(current, operation.bufferCapacity, epoch)
        try {
            operation.prepare(commandBuffer, scope, snapshot)
        } catch (failure: Throwable) {
            commandBuffer.close()
            throw failure
        }
        activeEpoch = epoch
        active.value = operation
        operation.start(::handleOperationFinished)
    }

    private fun handleOperationFinished(operation: Operation) = synchronized(gate) {
        if (active.value === operation) {
            val current = checkNotNull(session)
            if (operation.progress.value.phase != Operation.Phase.COMPLETED) {
                readyAfter = current.state.value.reportSequence
            }
            active.value = null
            activeEpoch = null
        }
    }

    private fun observe(current: MachineSession) {
        val snapshot = current.state.value
        machine.value = snapshot
        if (!transitioning) active.value?.let { operation ->
            if (current.normalCommandEpoch != activeEpoch) {
                operation.interrupt("Controller session or command correlation lost")
            } else {
                operation.stateHandler(snapshot)
            }
        }
    }

    private suspend fun pollStatus(current: MachineSession) {
        while (currentCoroutineContext().isActive) {
            delay(500)
            if (current.state.value.connection != MachineSnapshot.Connection.CONNECTED) return
            realtime.withLock {
                val shouldPoll = synchronized(gate) {
                    session === current && !transitioning && !current.state.value.resetInProgress
                }
                if (shouldPoll) {
                    try {
                        current.sendRealtime(RealtimeCommand.STATUS_QUERY)
                    } catch (_: Exception) {
                        // The session records write failures. Preserve cancellation.
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
        }
    }

    suspend fun sendRealtime(command: RealtimeCommand) {
        if (command == RealtimeCommand.SOFT_RESET) return reset()
        realtime.withLock {
            val current: MachineSession
            val operation: Operation?
            val cycleHold: Long?
            synchronized(gate) {
                current = checkNotNull(session)
                check(!transitioning)
                operation = active.value
                if (command == RealtimeCommand.FEED_HOLD) operation?.pauseDispatch()
                cycleHold = if (command == RealtimeCommand.CYCLE_START && operation != null) {
                    val snapshot = current.state.value
                    check(snapshot.usable() && snapshot.isFresh(now) && snapshot.status?.executionState == "Hold:0")
                    snapshot.lastHoldSequence
                } else null
            }
            current.sendRealtime(command)
            if (cycleHold != null && operation != null) {
                val requestStatus = synchronized(gate) {
                    val snapshot = current.state.value
                    if (transitioning || session !== current || active.value !== operation ||
                        snapshot.lastHoldSequence != cycleHold
                    ) return@withLock
                    operation.cycleStarted(cycleHold, snapshot.reportSequence)
                    // Request another sample if Run or Idle arrived before the write returned.
                    val mode = snapshot.status?.executionState?.substringBefore(':')
                    mode == "Run" || mode == "Idle"
                }
                if (requestStatus) current.sendRealtime(RealtimeCommand.STATUS_QUERY)
            }
        }
    }

    suspend fun reset() = lifecycle.withLock {
        val current: MachineSession
        val operation = synchronized(gate) {
            current = checkNotNull(session)
            transitioning = true
            active.value?.also { it.interrupt("Controller reset requested") }
        }
        try {
            withContext(NonCancellable) {
                operation?.awaitFinished()
                realtime.withLock { current.reset() }
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
        val operation = synchronized(gate) {
            current = session ?: return@withContext
            transitioning = true
            active.value?.also { it.interrupt("Controller session closed") }
        }
        current.close()
        statusPoller?.cancelAndJoin()
        operation?.awaitFinished()
        observer?.cancelAndJoin()
        current.awaitClosed()
        synchronized(gate) {
            machine.value = current.state.value
            session = null
            activeEpoch = null
            observer = null
            statusPoller = null
            transitioning = false
        }
    }

}

internal fun MachineSnapshot.isFresh(now: () -> Instant): Boolean {
    val age = Duration.between(reportedAt ?: return false, now())
    return !age.isNegative && age <= Duration.ofSeconds(3)
}

internal fun MachineSnapshot.usable() = connection == MachineSnapshot.Connection.CONNECTED &&
    normalCommandsSynchronized && !resetInProgress && !resetRequired && alarmCode == null && status?.isAlarm != true
