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
import kotlinx.coroutines.cancel
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
    private val firstStatus = CompletableDeferred<Unit>()
    private var pending: Pending? = null

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
                while (!firstStatus.isCompleted) {
                    writes.withLock {
                        synchronized(gate) { checkUsable(allowConnecting = true) }
                        transport.write(byteArrayOf(RealtimeCommand.STATUS_QUERY.byte))
                    }
                    withTimeoutOrNull(150) { firstStatus.await() }
                }
                firstStatus.await()
                synchronized(gate) {
                    checkUsable(allowConnecting = true)
                    mutableState.value = state.value.copy(connection = Connection.CONNECTED)
                }
            }
        } catch (error: Throwable) {
            terminate(error)
            throw error
        }
    }

    override suspend fun executeLine(command: String): CommandAcknowledgment {
        val bytes = GrblProtocol.encodeCommand(command)
        // Cancellation while waiting for admission cannot have transmitted anything.
        return normalCommands.withLock {
            val request = Pending()
            synchronized(gate) {
                checkUsable()
                pending = request
            }
            try {
                withTimeout(commandTimeoutMillis) {
                    writes.withLock {
                        synchronized(gate) { checkUsable() }
                        transport.write(bytes)
                    }
                    request.response.await()
                }
            } catch (error: Throwable) {
                // After admission bytes may have reached the controller. Correlation is lost.
                terminate(error)
                throw error
            }
        }
    }

    override suspend fun sendRealtime(command: RealtimeCommand) {
        try {
            writes.withLock {
                synchronized(gate) { checkUsable() }
                transport.write(byteArrayOf(command.byte))
                if (command == RealtimeCommand.SOFT_RESET) {
                    terminate(IOException("Controller reset; command outcomes are uncertain"))
                }
            }
        } catch (error: Throwable) {
            terminate(error)
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
            terminate(error)
            throw error
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
                )
                firstStatus.complete(Unit)
            }
            GrblMessage.Ok -> acknowledge(null)
            is GrblMessage.Error -> acknowledge(message.code)
            is GrblMessage.Alarm -> {
                mutableState.value = state.value.copy(lastMessage = line, status = null, reportedAt = null)
                terminate(IOException("Controller alarm ${message.code}; command outcomes are uncertain"))
            }
            is GrblMessage.Startup -> {
                mutableState.value = state.value.copy(lastMessage = line, status = null, reportedAt = null)
                if (state.value.connection == Connection.CONNECTED) {
                    terminate(IOException("Controller restarted; command correlation lost"))
                }
            }
            is GrblMessage.Information -> {
                mutableState.value = state.value.copy(lastMessage = line)
                pending?.let {
                    if (it.lines.size >= 256) throw IOException("Too many response lines without acknowledgment")
                    it.lines.add(line)
                }
            }
        }
    }

    private fun acknowledge(errorCode: Int?) {
        val request = pending ?: throw IOException("Unsolicited acknowledgment; command correlation lost")
        pending = null
        request.response.complete(CommandAcknowledgment(errorCode, Collections.unmodifiableList(request.lines.toList())))
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
            mutableState.value = state.value.copy(connection = connection, failure = error.message ?: error.javaClass.simpleName)
            firstStatus.completeExceptionally(error)
            pending?.response?.completeExceptionally(error)
            pending = null
            owner.cancel(CancellationException("Session terminated", error))
        }
    }

    override fun close() { terminate(IOException("Session closed"), Connection.CLOSED) }
    override suspend fun awaitClosed() { owner.join() }
}
