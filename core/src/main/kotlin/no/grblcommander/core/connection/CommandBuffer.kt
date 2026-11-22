package no.grblcommander.core.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.grblcommander.machine.session.CommandAcknowledgment
import no.grblcommander.machine.session.MachineSession
import no.grblcommander.machine.session.MachineSnapshot
import no.grblcommander.machine.session.MachineSnapshot.Connection

/** Runs ordered batches against one session and reserves normal dispatch until a batch ends. */
internal class CommandBuffer(private val session: MachineSession) {
    sealed interface LineResult {
        data class Acknowledged(val command: String, val acknowledgment: CommandAcknowledgment) : LineResult
        data class Unavailable(val command: String) : LineResult
        data class Interrupted(val command: String, val failure: Exception, val next: String?) : LineResult
    }

    private val mutableState = MutableStateFlow(BufferState())
    val state = mutableState.asStateFlow()

    private var lineNumbers = emptyMap<Int, Int>()
    private var batchEpoch = -1L
    private val sentAfterReport = mutableMapOf<Int, Long>()

    @Synchronized
    fun refreshExecution(snapshot: MachineSnapshot = session.state.value) {
        val number = snapshot.status?.executingLineNumber
        val position = lineNumbers[number]?.takeIf {
            snapshot.reportSequence > (sentAfterReport[it] ?: Long.MAX_VALUE) && batchEpoch == session.normalCommandEpoch &&
                snapshot.status?.executionState?.substringBefore(':') in setOf("Run", "Hold") &&
                !ControllerConnectionState(snapshot = snapshot).reportIsStale() &&
                snapshot.normalCommandsSynchronized && !snapshot.resetRequired && !snapshot.resetInProgress
        }
        mutableState.update { it.copy(executingLineNumber = position) }
    }

    private val normalDispatch = Mutex()
    private var batch: Job? = null

    /** The owner prepares one lazy batch under its admission lock, then starts it after connection. */
    fun prepareBatch(
        prepareCommands: suspend () -> List<String>,
        controllerLineNumbers: Map<Int, Int> = emptyMap(),
        continueAfterError: Boolean = false,
        expectedEpoch: Long? = null,
        beforeLine: suspend () -> Unit = {},
        onResult: (LineResult) -> Unit,
    ): Job {
        check(batch?.isCompleted != false) { "A command batch is already active" }
        synchronized(this) {
            lineNumbers = controllerLineNumbers.toMap()
            sentAfterReport.clear()
            mutableState.value = BufferState()
        }
        return session.prepareSessionTask {
            normalDispatch.withLock {
                val commands = prepareCommands()
                drain(commands, continueAfterError, expectedEpoch, beforeLine, onResult)
            }
        }.also { batch = it }
    }

    suspend fun cancelBatch() {
        batch?.cancelAndJoin()
    }

    suspend fun executeLine(command: String, epoch: Long): CommandAcknowledgment =
        normalDispatch.withLock {
            clearExecution()
            session.executeLine(command, epoch)
        }

    @Synchronized
    private fun clearExecution(uncertainWriteLineNumber: Int? = null) {
        sentAfterReport.clear()
        mutableState.update {
            it.copy(
                executingLineNumber = null,
                uncertainWriteLineNumber = uncertainWriteLineNumber ?: it.uncertainWriteLineNumber,
            )
        }
    }

    private suspend fun drain(
        commands: List<String>,
        continueAfterError: Boolean,
        expectedEpoch: Long?,
        beforeLine: suspend () -> Unit,
        onResult: (LineResult) -> Unit,
    ) {
        val epoch = expectedEpoch ?: session.normalCommandEpoch
        synchronized(this) { batchEpoch = epoch }
        for ((index, command) in commands.withIndex()) {
            currentCoroutineContext().ensureActive()
            beforeLine()
            val snapshot = session.state.value
            if (snapshot.connection != Connection.CONNECTED || !snapshot.normalCommandsSynchronized ||
                snapshot.resetRequired || snapshot.resetInProgress) {
                onResult(LineResult.Unavailable(command))
                return
            }
            val acknowledgment = try {
                session.executeLine(command, epoch, onUncertainWrite = { clearExecution(index + 1) }) {
                    synchronized(this) {
                        if (lineNumbers.isNotEmpty()) sentAfterReport[index + 1] = session.state.value.reportSequence
                        mutableState.update { it.copy(lastSentLineNumber = index + 1, reply = BufferState.Reply.Pending) }
                    }
                }
            } catch (error: Exception) {
                clearExecution()
                mutableState.update {
                    if (it.reply == BufferState.Reply.Pending) it.copy(reply = BufferState.Reply.Unknown)
                    else it
                }
                // A command timeout also throws a CancellationException. Only owner cancellation
                // cancels this worker; a timeout is reported and discards the rest of the batch.
                currentCoroutineContext().ensureActive()
                onResult(LineResult.Interrupted(command, error, commands.getOrNull(index + 1)))
                return
            }
            if (!acknowledgment.accepted && !continueAfterError) clearExecution()
            val reply = acknowledgment.errorCode?.let(BufferState.Reply::Error) ?: BufferState.Reply.Ok
            mutableState.update { it.copy(reply = reply) }
            onResult(LineResult.Acknowledged(command, acknowledgment))
            if (!acknowledgment.accepted && !continueAfterError) return
        }
    }
}
