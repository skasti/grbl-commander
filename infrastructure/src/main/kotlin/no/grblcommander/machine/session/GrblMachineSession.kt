package no.grblcommander.machine.session

import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import no.grblcommander.machine.protocol.ControllerStatus
import no.grblcommander.machine.protocol.ControllerAlarm
import no.grblcommander.machine.protocol.GrblLineDecoder
import no.grblcommander.machine.protocol.GrblMessage
import no.grblcommander.machine.protocol.GrblProtocol
import no.grblcommander.machine.session.MachineSnapshot.Connection
import no.grblcommander.machine.transport.MachineTransport

/** One transport, one reader, one normal command in flight, no automatic replay. */
class GrblMachineSession(
    private val transport: MachineTransport,
    parentScope: CoroutineScope,
    private val handshakeTimeoutMillis: Long = 5_000,
    private val commandTimeoutMillis: Long = 5_000,
) : MachineSession {
    init { require(handshakeTimeoutMillis > 0 && commandTimeoutMillis > 0) }
    private class Pending {
        val response = CompletableDeferred<CommandAcknowledgment>()
        val lines = mutableListOf<String>()
        var transmissionStarted = false
        var abandoned = false
    }

    private class ResetAttempt(val alarmCodeBeforeReset: Int? = null) {
        var invalidated = false
        var bannerReportSequence: Long? = null
        var abandoned = false
    }

    private val gate = Any()
    private val started = AtomicBoolean()
    private val owner = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + owner + Dispatchers.IO)
    private val mutableState = MutableStateFlow(MachineSnapshot())
    override val state = mutableState.asStateFlow()
    private val normalCommands = Mutex()
    private val statusQueries = Mutex()
    private val writes = Mutex()
    private var pending: Pending? = null
    private var commandEpoch = 0L
    private var blockingAlarmLatched = false
    private var resetClearedAlarmCode: Int? = null
    private var resetBoundaryEstablished = false
    private var unownedAcknowledgment = false
    private var resetAttempt: ResetAttempt? = null
    private val alarmDescriptions = mutableMapOf<Int, String>()

    init {
        owner.invokeOnCompletion { cause ->
            terminate(cause ?: IOException("Session owner stopped"), Connection.CLOSED)
        }
    }

    override suspend fun connect() {
        check(started.compareAndSet(false, true)) { "Session is one-shot" }
        synchronized(gate) {
            check(!terminal()) { "Session closed" }
            mutableState.value = MachineSnapshot(connection = Connection.CONNECTING)
        }
        try {
            withTimeout(handshakeTimeoutMillis) {
                transport.connect()
                scope.launch { readLoop() }
                // Query reports rather than assuming a banner or resetting a running machine.
                while (true) {
                    writes.withLock {
                        synchronized(gate) { checkUsable(allowConnecting = true) }
                        transport.write(byteArrayOf(RealtimeCommand.STATUS_QUERY.byte))
                    }
                    withTimeoutOrNull(150) {
                        state.first { it.status != null || isTerminal(it.connection) }
                    }
                    val ready = synchronized(gate) {
                        checkUsable(allowConnecting = true)
                        if (state.value.status != null) {
                            mutableState.value = state.value.copy(connection = Connection.CONNECTED)
                            true
                        } else false
                    }
                    if (ready) break
                }
            }
        } catch (error: Throwable) {
            terminate(error)
            throw error
        }
    }

    override suspend fun executeLine(command: String): CommandAcknowledgment {
        val bytes = GrblProtocol.encodeCommand(command)
        val epoch = synchronized(gate) { checkNormalCommands(); commandEpoch }
        // Cancellation while waiting for admission cannot have transmitted anything.
        return normalCommands.withLock {
            val request = Pending()
            synchronized(gate) {
                checkNormalCommands()
                if (epoch != commandEpoch) throw CommandCorrelationException("Queued line interrupted; submit explicitly again")
                pending = request
            }
            try {
                withTimeout(commandTimeoutMillis) {
                    writes.withLock {
                        currentCoroutineContext().ensureActive()
                        synchronized(gate) {
                            checkUsable()
                            if (request.abandoned) throw CommandCorrelationException("Line interrupted before transmission")
                            request.transmissionStarted = true
                        }
                        transport.write(bytes)
                    }
                    request.response.await()
                }
            } catch (error: Throwable) {
                if (!transport.isConnected) terminate(error)
                else synchronized(gate) { abandon(request, error, knownNotSent = error is IllegalArgumentException) }
                throw error
            }
        }
    }

    override suspend fun sendRealtime(command: RealtimeCommand) {
        if (command == RealtimeCommand.SOFT_RESET) return reset()
        var attempted = false
        try {
            writes.withLock {
                currentCoroutineContext().ensureActive()
                synchronized(gate) { checkUsable() }
                attempted = true
                transport.write(byteArrayOf(command.byte))
                synchronized(gate) { mutableState.value = state.value.copy(lastRealtimeFailure = null) }
            }
        } catch (error: Throwable) {
            if (attempted) {
                if (!transport.isConnected) terminate(error)
                else recordRealtimeFailure("$command", error)
            }
            throw error
        }
    }

    override suspend fun reset() {
        var request: ResetAttempt? = null
        try {
            withTimeout(handshakeTimeoutMillis) {
                writes.withLock {
                    currentCoroutineContext().ensureActive()
                    val attempt = synchronized(gate) {
                        checkUsable()
                        if (resetAttempt?.abandoned == false) throw IOException("Reset already in progress")
                        ResetAttempt(state.value.alarmCode).also { resetAttempt = it; request = it }
                    }
                    // Arm banner observation before I/O: a fast peer may reply before write returns.
                    transport.write(byteArrayOf(RealtimeCommand.SOFT_RESET.byte))
                    synchronized(gate) { invalidateForReset(attempt) }
                }
                val attempt = checkNotNull(request)
                while (true) {
                    sendRealtime(RealtimeCommand.STATUS_QUERY)
                    withTimeoutOrNull(150) {
                        state.first { snapshot ->
                            isTerminal(snapshot.connection) || synchronized(gate) { resetReady(attempt) }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    val ready = synchronized(gate) {
                        checkUsable()
                        if (resetReady(attempt)) {
                            resetAttempt = null
                            refreshSynchronization()
                            true
                        } else false
                    }
                    if (ready) break
                }
            }
        } catch (error: Throwable) {
            request?.let { attempt ->
                if (!transport.isConnected) terminate(error)
                else synchronized(gate) {
                    if (resetAttempt === attempt) {
                        if (error is IllegalArgumentException && !attempt.invalidated) {
                            resetAttempt = null // known local rejection, no reset bytes sent
                        } else {
                            invalidateForReset(attempt)
                            attempt.abandoned = true
                        }
                        refreshSynchronization()
                    }
                }
                recordRealtimeFailure("SOFT_RESET", error)
            }
            throw error
        }
    }

    private fun resetReady(attempt: ResetAttempt): Boolean {
        val sequence = attempt.bannerReportSequence ?: return false
        return resetAttempt === attempt && state.value.status != null && state.value.reportSequence > sequence
    }

    private fun invalidateForReset(attempt: ResetAttempt) {
        if (!attempt.invalidated) {
            attempt.invalidated = true
            commandEpoch++
            discardPending(CommandCorrelationException("Controller reset; previous line outcome unknown"))
            mutableState.value = state.value.copy(status = null, reportedAt = null)
        }
        refreshSynchronization()
    }

    override suspend fun queryStatus(): ControllerStatus = statusQueries.withLock {
        val sequence = synchronized(gate) { checkUsable(); state.value.reportSequence }
        try {
            withTimeout(commandTimeoutMillis) {
                sendRealtime(RealtimeCommand.STATUS_QUERY)
                val observed = state.first { (it.reportSequence > sequence && it.status != null) || isTerminal(it.connection) }
                synchronized(gate) { checkUsable() }
                checkNotNull(observed.status)
            }
        } catch (error: Throwable) {
            if (!transport.isConnected) terminate(error)
            else recordRealtimeFailure("Status query", error)
            throw error
        }
    }

    private fun recordRealtimeFailure(operation: String, error: Throwable) = synchronized(gate) {
        if (!terminal()) {
            val detail = when (error) {
                is IllegalArgumentException -> "local rejection"
                is CancellationException -> "cancelled; outcome may be unknown"
                else -> "failed; outcome may be unknown"
            }
            mutableState.value = state.value.copy(lastRealtimeFailure = "$operation $detail: ${error.message}")
        }
    }

    private suspend fun readLoop() {
        try {
            val decoder = GrblLineDecoder()
            val buffer = ByteArray(1_024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = transport.read(buffer)
                if (count < 0) throw IOException("Controller disconnected")
                decoder.accept(buffer, count, ::receive)
            }
        } catch (error: Throwable) {
            terminate(error)
        }
    }

    private fun receive(line: String) = synchronized(gate) {
        if (terminal()) return@synchronized
        when (val message = GrblProtocol.parse(line)) {
            is GrblMessage.Status -> {
                val previous = state.value
                val wasAlarm = previous.alarmCode != null || previous.status?.isAlarm == true
                val code = if (message.report.isAlarm) message.report.alarmCode ?: previous.alarmCode else null
                val observed = previous.copy(
                    status = message.report, reportedAt = Instant.now(),
                    reportSequence = previous.reportSequence + 1, alarmCode = code,
                    alarmDescription = code?.let { alarmDescriptions[it] },
                )
                if (message.report.blockingCriticalEvent == true && !blockingAlarmLatched) {
                    blockingAlarmLatched = true
                    resetBoundaryEstablished = false
                    resetClearedAlarmCode = null
                    commandEpoch++
                    discardPending(CommandCorrelationException("Controller reports a blocking critical event; reset required"))
                } else if (message.report.isAlarm && (!wasAlarm || code != previous.alarmCode)) {
                    observeAlarm(code, fromStatus = true)
                }
                if (!message.report.isAlarm) resetClearedAlarmCode = null
                refreshSynchronization(observed)
            }
            GrblMessage.Ok -> acknowledge(null)
            is GrblMessage.Error -> acknowledge(message.code)
            is GrblMessage.Alarm -> {
                val observed = state.value.copy(
                    lastMessage = line, status = null, reportedAt = null, alarmCode = message.code,
                    alarmDescription = alarmDescriptions[message.code],
                )
                observeAlarm(message.code)
                refreshSynchronization(observed)
            }
            is GrblMessage.Startup -> {
                val attempt = resetAttempt ?: if (state.value.connection == Connection.CONNECTING) null else {
                    // An external reset is a new boundary, but never resumes application work.
                    ResetAttempt().also { it.abandoned = true; resetAttempt = it }
                }
                if (attempt != null) {
                    resetClearedAlarmCode = state.value.alarmCode ?: attempt.alarmCodeBeforeReset
                    resetBoundaryEstablished = !attempt.abandoned
                    invalidateForReset(attempt)
                    attempt.bannerReportSequence = state.value.reportSequence
                }
                blockingAlarmLatched = false
                unownedAcknowledgment = false
                mutableState.value = state.value.copy(
                    lastMessage = line, status = null, reportedAt = null, alarmCode = null, alarmDescription = null,
                )
                refreshSynchronization()
            }
            is GrblMessage.AlarmDescription -> {
                if (message.code in alarmDescriptions || alarmDescriptions.size < 512) {
                    alarmDescriptions[message.code] = message.description
                }
                if (state.value.alarmCode == message.code) {
                    mutableState.value = state.value.copy(alarmDescription = message.description)
                }
                collectInformation(line)
            }
            is GrblMessage.Information -> collectInformation(line)
        }
    }

    private fun observeAlarm(code: Int?, fromStatus: Boolean = false) {
        // Critical core events may discard the line entirely. Unknown numeric
        // extension alarms get the same conservative reset barrier, not a critical label.
        val retainedAfterReset = fromStatus && resetBoundaryEstablished &&
            code != null && code == resetClearedAlarmCode
        // Learning the retained post-reset code is not a new event and must not
        // interrupt an operator's already pending unlock/homing request.
        if (retainedAfterReset) return
        // Only the known pre-reset code can be retained. A new event or changed
        // code ends that history; absence of $C:1 says nothing about alarm age.
        if (code != null || !fromStatus || resetAttempt?.bannerReportSequence == null) {
            resetClearedAlarmCode = null
        }
        commandEpoch++
        if (code != null && ControllerAlarm.fromCode(code).isCritical != false) {
            blockingAlarmLatched = true
            resetBoundaryEstablished = false
        }
        if (code == null && !(fromStatus && resetAttempt?.bannerReportSequence != null)) {
            resetBoundaryEstablished = false
        }
        val error = CommandCorrelationException("Controller alarm ${code ?: "unknown"}; line outcome unknown")
        if (blockingAlarmLatched) discardPending(error)
        else pending?.let { abandon(it, error) }
        refreshSynchronization()
    }

    private fun collectInformation(line: String) {
        mutableState.value = state.value.copy(lastMessage = line)
        pending?.takeUnless { it.abandoned }?.let {
            if (it.lines.size >= 256) {
                abandon(it, CommandCorrelationException("Response line limit exceeded; acknowledgment unresolved"))
            } else it.lines.add(line)
        }
    }

    private fun discardPending(error: Throwable) {
        pending?.let { it.abandoned = true; it.response.completeExceptionally(error) }
        pending = null
    }

    /** Derive protocol availability separately from transport health and alarm presentation. */
    private fun refreshSynchronization(snapshot: MachineSnapshot = state.value) {
        val unknownAlarm = (snapshot.alarmCode != null || snapshot.status?.isAlarm == true) &&
            snapshot.alarm?.isCritical == null && !resetBoundaryEstablished
        val reset = resetAttempt?.takeIf { it.invalidated }
        val resetRequired = blockingAlarmLatched || unownedAcknowledgment || unknownAlarm ||
            (reset != null && (reset.abandoned || reset.bannerReportSequence == null))
        mutableState.value = snapshot.copy(
            resetRequired = resetRequired,
            resetInProgress = reset != null && !reset.abandoned,
            normalCommandsSynchronized = !resetRequired && reset == null && pending?.abandoned != true,
        )
    }

    private fun acknowledge(errorCode: Int?) {
        // No acknowledgment can clear a critical/reset boundary requirement.
        if (blockingAlarmLatched || resetAttempt?.invalidated == true) return
        val request = pending ?: run {
            if (state.value.connection == Connection.CONNECTING) {
                // Firmware may emit startup-block acknowledgments or NVS diagnostics.
                // No line command has been admitted during the status-only handshake.
                mutableState.value = state.value.copy(lastMessage = errorCode?.let { "error:$it" } ?: "ok")
                return
            }
            commandEpoch++
            unownedAcknowledgment = true
            refreshSynchronization()
            return
        }
        pending = null
        refreshSynchronization()
        request.response.complete(CommandAcknowledgment(errorCode, Collections.unmodifiableList(request.lines.toList())))
    }

    /** Keep one tombstone for a transmitted line; only its acknowledgment can drain it. */
    private fun abandon(request: Pending, error: Throwable, knownNotSent: Boolean = false) {
        if (pending !== request || (request.abandoned && !knownNotSent)) return
        request.abandoned = true
        if (request.transmissionStarted && !knownNotSent) {
            commandEpoch++
        } else {
            pending = null
        }
        request.response.completeExceptionally(error)
        refreshSynchronization()
    }

    private fun checkNormalCommands() {
        checkUsable()
        if (!state.value.normalCommandsSynchronized) {
            throw CommandCorrelationException(
                if (state.value.resetRequired) "Explicit reset required; transport and status remain available"
                else "Normal command correlation unresolved; drain the old response or explicitly reset",
            )
        }
    }

    private fun checkUsable(allowConnecting: Boolean = false) {
        val connection = state.value.connection
        if (connection != Connection.CONNECTED && !(allowConnecting && connection == Connection.CONNECTING)) {
            throw IOException("Session is $connection", state.value.failure?.let(::IOException))
        }
    }

    private fun terminal() = isTerminal(state.value.connection)
    private fun isTerminal(connection: Connection) = connection == Connection.FAILED || connection == Connection.CLOSED

    private fun terminate(error: Throwable, connection: Connection = Connection.FAILED) {
        synchronized(gate) {
            if (terminal()) return
            try { transport.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            mutableState.value = state.value.copy(
                connection = connection, failure = error.message ?: error.javaClass.simpleName,
                normalCommandsSynchronized = false, resetInProgress = false, resetRequired = true,
            )
            resetAttempt = null
            pending?.response?.completeExceptionally(error)
            pending = null
            owner.cancel(CancellationException("Session terminated", error))
        }
    }

    override fun close() { terminate(IOException("Session closed"), Connection.CLOSED) }
    override suspend fun awaitClosed() { owner.join() }
}
