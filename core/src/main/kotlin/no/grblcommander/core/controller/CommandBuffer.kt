package no.grblcommander.core.controller

import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import no.grblcommander.machine.session.CommandAcknowledgment
import no.grblcommander.machine.session.MachineSession

internal data class SourceCommand(val position: Int, val text: String)

internal sealed interface CommandOutcome {
    data object Sent : CommandOutcome
    data object UncertainWrite : CommandOutcome
    data class Reply(val acknowledgment: CommandAcknowledgment) : CommandOutcome
    data class Interrupted(val cause: Exception) : CommandOutcome
}

/** Single execution. Capacity excludes the one command held by the session-owned worker. */
internal class CommandBuffer(
    private val session: MachineSession,
    capacity: Int = 8,
    private val admitDispatch: suspend () -> Unit,
    private val report: (SourceCommand, CommandOutcome) -> Unit,
) : Closeable {
    init {
        require(capacity in 1 until Channel.UNLIMITED)
    }

    private val epoch = session.normalCommandEpoch
    private val queue = Channel<SourceCommand>(capacity)
    private val terminated = CompletableDeferred<Unit>()
    private val worker = session.prepareSessionTask {
        for (command in queue) {
            val reply = try {
                admitDispatch()
                currentCoroutineContext().ensureActive()
                session.executeLine(command.text, epoch,
                    onUncertainWrite = { report(command, CommandOutcome.UncertainWrite) },
                    onSent = { report(command, CommandOutcome.Sent) },
                )
            } catch (error: Exception) {
                report(command, CommandOutcome.Interrupted(error))
                if (error is CancellationException) throw error
                break
            }
            report(command, CommandOutcome.Reply(reply))
            if (!reply.accepted) break
        }
    }.also { task ->
        task.invokeOnCompletion {
            queue.cancel()
            terminated.complete(Unit)
        }
    }

    fun start() = check(worker.start()) { "Buffer has already started or closed" }
    suspend fun enqueue(command: SourceCommand) = queue.send(command)
    fun finishProduction() { queue.close() }

    override fun close() {
        worker.cancel()
        queue.cancel()
    }

    suspend fun awaitClosed() = terminated.await()
}
