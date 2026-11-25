package no.grblcommander.core.controller

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class Operation(lines: List<String>, val source: String) {
    private val program = LineProgram(lines)
    val lines get() = program.source

    enum class Phase { READY, RUNNING, WAITING_FOR_IDLE, COMPLETED, FAILED, INTERRUPTED }
    data class Progress(
        val phase: Phase = Phase.READY,
        val enqueued: Int? = null,
        val sent: Int? = null,
        val acknowledged: Int? = null,
        val uncertainWrite: Int? = null,
        val executing: Int? = null,
        val failedLine: Int? = null,
        val error: String? = null,
    )

    private val state = MutableStateFlow(Progress())
    val progress = state.asStateFlow()
    private var finalAckSequence = Long.MAX_VALUE

    internal fun reserve() = state.compareAndSet(Progress(), Progress(Phase.RUNNING))

    internal suspend fun produce(buffer: CommandBuffer) {
        for (i in lines.indices) {
            buffer.awaitDispatchEligibility()
            buffer.enqueue(SourceCommand(i + 1, program.wire(i)))
            state.update { it.copy(enqueued = i + 1) }
        }
        buffer.finishProduction()
    }

    @Synchronized internal fun outcome(command: SourceCommand, outcome: CommandOutcome, sequence: Long) {
        val line = command.position
        state.update {
            when (outcome) {
                CommandOutcome.Sent -> it.copy(sent = line)
                CommandOutcome.UncertainWrite -> it.copy(uncertainWrite = line)
                is CommandOutcome.Interrupted -> it.copy(phase = Phase.INTERRUPTED, failedLine = line, error = outcome.cause.message)
                is CommandOutcome.Reply -> when {
                    !outcome.acknowledgment.accepted -> it.copy(phase = Phase.FAILED, failedLine = line, error = "error:${outcome.acknowledgment.errorCode}")
                    line == lines.size -> {
                        finalAckSequence = outcome.acknowledgment.reportSequence ?: sequence
                        it.copy(acknowledged = line, phase = Phase.WAITING_FOR_IDLE)
                    }
                    else -> it.copy(acknowledged = line)
                }
            }
        }
    }

    @Synchronized internal fun observe(number: Int?, sequence: Long, idle: Boolean) {
        state.update {
            val phase = if (it.phase == Phase.WAITING_FOR_IDLE && idle && sequence > finalAckSequence)
                Phase.COMPLETED else it.phase
            it.copy(phase = phase, executing = program.lineNumbers[number])
        }
    }

    internal fun interrupt(reason: String) {
        state.update {
            if (it.phase in setOf(Phase.RUNNING, Phase.WAITING_FOR_IDLE))
                it.copy(phase = Phase.INTERRUPTED, error = reason) else it
        }
    }
}
