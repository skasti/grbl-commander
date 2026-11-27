package no.grblcommander.core.commands

import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import no.grblcommander.machine.session.MachineSession
import no.grblcommander.machine.session.MachineSnapshot

/** Single execution. Capacity excludes the one command held by the buffer worker. */
internal class CommandBuffer(
    private val session: MachineSession,
    capacity: Int = DEFAULT_CAPACITY,
    private val epoch: Long = session.normalCommandEpoch,
) : Closeable {
    init { require(capacity in 1 until Channel.UNLIMITED) }

    private enum class Gate { OPEN, SUSPENDED, CLOSED }
    private val gate = MutableStateFlow(Gate.OPEN)

    var suspended: Boolean
        get() = gate.value == Gate.SUSPENDED
        private set(value) {
            if (value) gate.compareAndSet(Gate.OPEN, Gate.SUSPENDED)
            else gate.compareAndSet(Gate.SUSPENDED, Gate.OPEN)
        }

    fun `suspend`() { suspended = true }
    fun resume() { suspended = false }

    private var reportHandler: ((Command, CommandResult, MachineSnapshot) -> Unit)? = null
    private var worker: Job? = null

    @Synchronized
    internal fun bindReport(handler: (Command, CommandResult, MachineSnapshot) -> Unit) {
        check(worker == null && gate.value != Gate.CLOSED) { "Report handler must be bound before the buffer starts" }
        check(reportHandler == null) { "Report handler is already bound" }
        reportHandler = handler
    }

    private fun report(command: Command, result: CommandResult) {
        val handler = checkNotNull(reportHandler) { "Report handler must be bound before the buffer starts" }
        handler(command, result, session.state.value)
    }

    internal suspend fun awaitDispatchEligibility() {
        while (true) {
            when (gate.value) {
                Gate.OPEN -> return // For the worker, this read admits its current command.
                Gate.CLOSED -> throw CancellationException("Buffer closed")
                Gate.SUSPENDED -> gate.first { it != Gate.SUSPENDED }
            }
        }
    }

    private val queue = Channel<Command>(capacity)
    private val terminated = CompletableDeferred<Unit>()
    private fun createWorker(executionScope: CoroutineScope) = executionScope.launch(start = CoroutineStart.LAZY) {
        for (command in queue) {
            val reply = try {
                awaitDispatchEligibility()
                currentCoroutineContext().ensureActive()
                session.executeLine(command.text, epoch,
                    onUncertainWrite = { report(command, CommandResult.UncertainWrite) },
                    onSent = { report(command, CommandResult.Sent) },
                )
            } catch (error: Exception) {
                report(command, CommandResult.Interrupted(error))
                if (error is CancellationException) throw error
                break
            }
            report(command, CommandResult.Reply(reply))
            if (!reply.accepted) break
        }
    }.also { task ->
        task.invokeOnCompletion {
            gate.value = Gate.CLOSED
            queue.cancel()
            terminated.complete(Unit)
        }
    }

    @Synchronized
    fun start(executionScope: CoroutineScope) {
        checkNotNull(executionScope.coroutineContext[Job]) { "An execution lifetime is required" }
        check(gate.value != Gate.CLOSED) { "Buffer is closed" }
        check(worker == null) { "Buffer has already started or closed" }
        checkNotNull(reportHandler) { "Report handler must be bound before the buffer starts" }
        val task = createWorker(executionScope)
        worker = task
        check(task.start()) { "Buffer has already started or closed" }
    }
    suspend fun enqueue(command: Command) = queue.send(command)
    fun finishProduction() { queue.close() }

    @Synchronized
    override fun close() {
        gate.value = Gate.CLOSED
        worker?.cancel() ?: terminated.complete(Unit)
        queue.cancel()
    }

    suspend fun awaitClosed() = terminated.await()

    companion object {
        const val DEFAULT_CAPACITY = 8
    }
}
