package no.grblcommander.core.connection

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.grblcommander.machine.session.CommandAcknowledgment
import no.grblcommander.machine.session.MachineSession
import no.grblcommander.machine.session.MachineSnapshot.Connection

/** Runs ordered batches against one session and reserves normal dispatch until a batch ends. */
internal class CommandBuffer(private val session: MachineSession) {
    sealed interface LineResult {
        data class Acknowledged(val command: String, val acknowledgment: CommandAcknowledgment) : LineResult
        data class Unavailable(val command: String) : LineResult
        data class Interrupted(val command: String, val failure: Exception, val next: String?) : LineResult
    }

    private val normalDispatch = Mutex()
    private var batch: Job? = null

    /** The owner prepares one lazy batch under its admission lock, then starts it after connection. */
    fun prepareBatch(
        prepareCommands: suspend () -> List<String>,
        onResult: (LineResult) -> Unit,
    ): Job {
        check(batch?.isCompleted != false) { "A command batch is already active" }
        return session.prepareSessionTask {
            normalDispatch.withLock {
                val commands = prepareCommands()
                drain(commands, onResult)
            }
        }.also { batch = it }
    }

    suspend fun cancelBatch() {
        batch?.cancelAndJoin()
    }

    suspend fun executeLine(command: String, epoch: Long): CommandAcknowledgment =
        normalDispatch.withLock { session.executeLine(command, epoch) }

    private suspend fun drain(commands: List<String>, onResult: (LineResult) -> Unit) {
        val epoch = session.normalCommandEpoch
        for ((index, command) in commands.withIndex()) {
            currentCoroutineContext().ensureActive()
            val snapshot = session.state.value
            if (snapshot.connection != Connection.CONNECTED || !snapshot.normalCommandsSynchronized ||
                snapshot.resetRequired || snapshot.resetInProgress) {
                onResult(LineResult.Unavailable(command))
                return
            }
            val acknowledgment = try {
                session.executeLine(command, epoch)
            } catch (error: Exception) {
                // A command timeout also throws a CancellationException. Only owner cancellation
                // cancels this worker; a timeout is reported and discards the rest of the batch.
                currentCoroutineContext().ensureActive()
                onResult(LineResult.Interrupted(command, error, commands.getOrNull(index + 1)))
                return
            }
            onResult(LineResult.Acknowledged(command, acknowledgment))
        }
    }
}
