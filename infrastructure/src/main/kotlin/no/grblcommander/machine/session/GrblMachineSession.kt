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
        var attempted = false
        try {
            writes.withLock {
                currentCoroutineContext().ensureActive()
                synchronized(gate) { checkUsable() }
                attempted = true
                transport.write(byteArrayOf(command.byte))
                synchronized(gate) { mutableState.value = state.value.copy(lastRealtimeFailure = null) }
                if (command == RealtimeCommand.SOFT_RESET) {
                    terminate(IOException("Controller reset; command outcomes are uncertain"))
                }
            }
        } catch (error: Throwable) {
            if (attempted) {
                if (!transport.isConnected || (command == RealtimeCommand.SOFT_RESET && error !is IllegalArgumentException)) {
                    terminate(error)
                }
                else recordRealtimeFailure("$command", error)
            }
            throw error
        }
    }

    override suspend fun queryStatus(): ControllerStatus = statusQueries.withLock {
        val sequence = synchronized(gate) { checkUsable(); state.value.reportSequence }
        try {
            withTimeout(commandTimeoutMillis) {
                sendRealtime(RealtimeCommand.STATUS_QUERY)
                val observed = state.first { it.reportSequence > sequence || isTerminal(it.connection) }
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
                mutableState.value = state.value.copy(
                    status = message.report, reportedAt = Instant.now(),
                    reportSequence = state.value.reportSequence + 1,
                    alarmCode = state.value.alarmCode.takeIf { message.report.executionState.substringBefore(':') == "Alarm" },
                )
            }
            GrblMessage.Ok -> acknowledge(null)
            is GrblMessage.Error -> acknowledge(message.code)
            is GrblMessage.Alarm -> {
                mutableState.value = state.value.copy(
                    lastMessage = line, status = null, reportedAt = null, alarmCode = message.code,
                )
                // ALARM is not an acknowledgment. Never free its line's response slot
                // for a new command until the expected ok/error has been drained.
                commandEpoch++
                pending?.let { abandon(it, CommandCorrelationException("Controller alarm ${message.code}; line outcome unknown")) }
            }
            is GrblMessage.Startup -> {
                mutableState.value = state.value.copy(lastMessage = line, status = null, reportedAt = null)
                if (state.value.connection == Connection.CONNECTED) {
                    terminate(IOException("Controller restarted; command correlation lost"))
                }
            }
            is GrblMessage.AlarmDescription, is GrblMessage.Information -> {
                mutableState.value = state.value.copy(lastMessage = line)
                pending?.takeUnless { it.abandoned }?.let {
                    if (it.lines.size >= 256) {
                        abandon(it, CommandCorrelationException("Response line limit exceeded; awaiting the line acknowledgment"))
                    } else it.lines.add(line)
                }
            }
        }
    }

    private fun acknowledge(errorCode: Int?) {
        val request = pending ?: run {
            if (state.value.connection == Connection.CONNECTING) {
                // Firmware may emit startup-block acknowledgments or NVS diagnostics.
                // No line command has been admitted during the status-only handshake.
                mutableState.value = state.value.copy(lastMessage = errorCode?.let { "error:$it" } ?: "ok")
                return
            }
            commandEpoch++
            mutableState.value = state.value.copy(normalCommandsSynchronized = false)
            return
        }
        pending = null
        mutableState.value = state.value.copy(normalCommandsSynchronized = true)
        request.response.complete(CommandAcknowledgment(errorCode, Collections.unmodifiableList(request.lines.toList())))
    }

    /** Keep one tombstone for a transmitted line; only its acknowledgment can drain it. */
    private fun abandon(request: Pending, error: Throwable, knownNotSent: Boolean = false) {
        if (pending !== request || (request.abandoned && !knownNotSent)) return
        request.abandoned = true
        if (request.transmissionStarted && !knownNotSent) {
            commandEpoch++
            mutableState.value = state.value.copy(normalCommandsSynchronized = false)
        } else {
            pending = null
            mutableState.value = state.value.copy(normalCommandsSynchronized = true)
        }
        request.response.completeExceptionally(error)
    }

    private fun checkNormalCommands() {
        checkUsable()
        if (!state.value.normalCommandsSynchronized) {
            throw CommandCorrelationException("Normal command correlation unresolved; real-time/status remain available")
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
                normalCommandsSynchronized = false,
            )
            pending?.response?.completeExceptionally(error)
            pending = null
            owner.cancel(CancellationException("Session terminated", error))
        }
    }

    override fun close() { terminate(IOException("Session closed"), Connection.CLOSED) }
    override suspend fun awaitClosed() { owner.join() }
}
