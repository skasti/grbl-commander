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

/** Single execution. Capacity excludes the one command held by the buffer worker. */
internal class CommandBuffer(
    private val session: MachineSession,
    executionScope: CoroutineScope,
    capacity: Int = DEFAULT_CAPACITY,
    private val epoch: Long = session.normalCommandEpoch,
    private val report: (Command, CommandResult) -> Unit,
) : Closeable {
    init {
        requireNotNull(executionScope.coroutineContext[Job]) { "An execution lifetime is required" }
        require(capacity in 1 until Channel.UNLIMITED)
    }

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
    private val worker = executionScope.launch(start = CoroutineStart.LAZY) {
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

    fun start() = check(worker.start()) { "Buffer has already started or closed" }
    suspend fun enqueue(command: Command) = queue.send(command)
    fun finishProduction() { queue.close() }

    override fun close() {
        gate.value = Gate.CLOSED
        worker.cancel()
        queue.cancel()
    }

    suspend fun awaitClosed() = terminated.await()

    companion object {
        const val DEFAULT_CAPACITY = 8
    }
}
