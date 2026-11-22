package no.grblcommander.core.connection

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Job
import no.grblcommander.machine.session.MachineSnapshot

/** Tracks one operation under the connection's admission lock. */
internal class OperationRun(val operation: Operation, val epoch: Long) {
    lateinit var batch: Job
    var finalReplySequence: Long? = null
    private var recoverySequence: Long? = null
    private var awaitingSynchronization = false

    fun canRelease(snapshot: MachineSnapshot, freshIdle: Boolean, resetPending: Boolean): Boolean {
        if (!finished || !batch.isCompleted) return false
        if (recoverySequence == null || snapshot.connection != MachineSnapshot.Connection.CONNECTED) return true
        if (resetPending || !snapshot.normalCommandsSynchronized || snapshot.resetRequired || snapshot.resetInProgress) {
            awaitingSynchronization = true
            return false
        }
        if (awaitingSynchronization) {
            recoverySequence = snapshot.reportSequence
            awaitingSynchronization = false
        }
        return freshIdle && snapshot.reportSequence > checkNotNull(recoverySequence)
    }

    private var lastSample: Sample? = null
    private var elapsed: Duration? = null

    private data class Sample(val sequence: Long, val line: Int, val at: Instant, val held: Boolean)

    val finished: Boolean
        get() = operation.state.value.phase in setOf(
            Operation.Phase.COMPLETED, Operation.Phase.FAILED, Operation.Phase.INTERRUPTED,
        )

    fun publish(
        buffer: BufferState,
        snapshot: MachineSnapshot,
        fresh: Boolean,
    ) {
        val phase = operation.state.value.phase
        val mode = snapshot.status?.executionState?.substringBefore(':')
        val held = mode == "Hold"
        val at = snapshot.reportedAt.takeIf { fresh }
        val line = buffer.executingLineNumber.takeIf { fresh && !finished }
        observeLine(line, at, snapshot.reportSequence, held)

        val next = when {
            finished -> phase
            fresh && mode == "Idle" && batch.isCompleted &&
                finalReplySequence?.let { snapshot.reportSequence > it } == true -> Operation.Phase.COMPLETED
            !fresh && phase == Operation.Phase.HELD -> phase
            fresh && held -> Operation.Phase.HELD
            batch.isActive || buffer.lastSentLineNumber != null -> Operation.Phase.RUNNING
            else -> phase
        }
        operation.publish(operation.state.value.copy(
            phase = next,
            lastSentLineNumber = buffer.lastSentLineNumber,
            reply = buffer.reply,
            executingLineNumber = line,
            observedAt = at.takeUnless { finished },
            activeLineElapsed = elapsed,
        ))
    }

    fun finish(phase: Operation.Phase, detail: String, reportSequence: Long, buffer: BufferState) {
        if (finished) return
        recoverySequence = reportSequence
        awaitingSynchronization = phase == Operation.Phase.INTERRUPTED
        operation.publish(operation.state.value.copy(
            phase = phase, lastSentLineNumber = buffer.lastSentLineNumber, reply = buffer.reply,
            executingLineNumber = null, activeLineElapsed = null,
            observedAt = null, detail = detail,
        ))
    }

    private fun observeLine(line: Int?, at: Instant?, sequence: Long, held: Boolean) {
        if (line == null || at == null) {
            lastSample = null
            elapsed = null
            return
        }
        val previous = lastSample
        if (previous?.sequence == sequence) return
        elapsed = when {
            previous == null || previous.line != line || previous.sequence + 1 != sequence ||
                at.isBefore(previous.at) -> Duration.ZERO
            previous.held || held -> elapsed
            else -> checkNotNull(elapsed).plus(Duration.between(previous.at, at))
        }
        lastSample = Sample(sequence, line, at, held)
    }
}
