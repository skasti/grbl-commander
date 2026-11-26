package no.grblcommander.core.controller

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import no.grblcommander.machine.session.MachineSession
import no.grblcommander.machine.session.MachineSnapshot

class Operation internal constructor(lines: List<String>, val source: String, private val bufferCapacity: Int) {
    constructor(lines: List<String>, source: String) : this(lines, source, CommandBuffer.DEFAULT_CAPACITY)

    init { require(bufferCapacity in 1 until Int.MAX_VALUE) }

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
    private val gate = Any()
    private val sentAfterReport = LongArray(lines.size) { Long.MAX_VALUE }
    private val finished = CompletableDeferred<Unit>()
    private var execution: Job? = null
    private var buffer: CommandBuffer? = null
    private var epoch = -1L
    private var holdSequence = -1L
    private var held = false
    private var resumeAfter: Long? = null
    private var finalAckSequence = Long.MAX_VALUE
    private var interruption: String? = null

    internal fun prepare(
        session: MachineSession,
        parent: CoroutineScope,
        epoch: Long,
        initialHoldSequence: Long,
        isFresh: (MachineSnapshot) -> Boolean,
    ) = synchronized(gate) {
        check(state.value.phase == Phase.READY) { "Operation already accepted" }
        this.epoch = epoch
        holdSequence = initialHoldSequence
        execution = parent.launch(start = CoroutineStart.LAZY) {
            val commands = CommandBuffer(session, this, bufferCapacity, epoch) { command, outcome ->
                record(command, outcome, session, isFresh)
            }
            try {
                synchronized(gate) {
                    ensureActive()
                    buffer = commands
                    if (held) commands.`suspend`()
                    commands.start()
                }
                produce(commands)
                commands.awaitClosed()
                progress.first { it.phase in terminal }
            } finally {
                commands.close()
            }
        }
        state.value = Progress(Phase.RUNNING)
    }

    internal fun start(onFinished: () -> Unit) {
        val job = synchronized(gate) { checkNotNull(execution) }
        job.invokeOnCompletion { cause ->
            synchronized(gate) {
                buffer = null
                execution = null
                state.update {
                    if (it.phase in terminal) it.copy(executing = null)
                    else it.copy(
                        phase = Phase.INTERRUPTED,
                        executing = null,
                        error = interruption ?: cause?.message ?: "Execution ended",
                    )
                }
            }
            try {
                onFinished()
            } finally {
                finished.complete(Unit)
            }
        }
        job.start()
    }

    internal fun interrupt(reason: String) {
        val job = synchronized(gate) {
            if (interruption == null) interruption = reason
            execution
        }
        job?.cancel(CancellationException(reason))
    }

    internal suspend fun awaitFinished() = finished.await()

    internal fun pauseDispatch() = synchronized(gate) {
        holdDispatch()
    }

    internal fun cycleStarted(hold: Long, after: Long) = synchronized(gate) {
        if (interruption == null && holdSequence <= hold) {
            if (holdSequence < hold) {
                holdSequence = hold
                holdDispatch()
            }
            resumeAfter = after
        }
    }

    internal fun observe(session: MachineSession, isFresh: (MachineSnapshot) -> Boolean) {
        val invalidated = synchronized(gate) { !observeSession(session, isFresh) }
        if (invalidated) interrupt("Controller session or command correlation lost")
    }

    private suspend fun produce(buffer: CommandBuffer) {
        for (i in lines.indices) {
            buffer.awaitDispatchEligibility()
            buffer.enqueue(SourceCommand(i + 1, program.wire(i)))
            state.update { it.copy(enqueued = i + 1) }
        }
        buffer.finishProduction()
    }

    private fun record(
        command: SourceCommand,
        outcome: CommandOutcome,
        session: MachineSession,
        isFresh: (MachineSnapshot) -> Boolean,
    ) {
        val invalidated = synchronized(gate) {
            val line = command.position
            val sequence = session.state.value.reportSequence
            if (outcome == CommandOutcome.Sent) sentAfterReport[line - 1] = sequence
            if (outcome is CommandOutcome.Reply && outcome.acknowledgment.accepted && line == lines.size) {
                finalAckSequence = outcome.acknowledgment.reportSequence ?: sequence
            }
            state.update {
                when (outcome) {
                    CommandOutcome.Sent -> it.copy(sent = line)
                    CommandOutcome.UncertainWrite -> it.copy(uncertainWrite = line)
                    is CommandOutcome.Interrupted -> it.copy(
                        phase = Phase.INTERRUPTED, failedLine = line, error = interruption ?: outcome.cause.message,
                    )
                    is CommandOutcome.Reply -> when {
                        !outcome.acknowledgment.accepted -> it.copy(
                            phase = Phase.FAILED, failedLine = line, error = "error:${outcome.acknowledgment.errorCode}",
                        )
                        line == lines.size -> it.copy(acknowledged = line, phase = Phase.WAITING_FOR_IDLE)
                        else -> it.copy(acknowledged = line)
                    }
                }
            }
            // Check the latest status before the worker can dispatch the next line.
            !observeSession(session, isFresh)
        }
        if (invalidated) interrupt("Controller session or command correlation lost")
    }

    private fun observeSession(session: MachineSession, isFresh: (MachineSnapshot) -> Boolean): Boolean {
        if (interruption != null) return true
        val snapshot = session.state.value
        if (!snapshot.usable() || session.normalCommandEpoch != epoch) return false

        if (snapshot.lastHoldSequence > holdSequence) {
            holdSequence = snapshot.lastHoldSequence
            holdDispatch()
        }
        val mode = snapshot.status?.executionState?.substringBefore(':')
        val sequence = snapshot.reportSequence
        val fresh = isFresh(snapshot)
        val after = resumeAfter
        if (after != null && fresh && sequence > after && (mode == "Run" || mode == "Idle")) {
            resumeAfter = null
            held = false
            buffer?.resume()
        }

        val position = program.lineNumbers[snapshot.status?.executingLineNumber]?.takeIf {
            fresh && (mode == "Run" || mode == "Hold") && sequence > sentAfterReport[it - 1]
        }
        state.update {
            val phase = if (it.phase == Phase.WAITING_FOR_IDLE && fresh && mode == "Idle" &&
                !held && sequence > finalAckSequence
            ) Phase.COMPLETED else it.phase
            it.copy(phase = phase, executing = position.takeIf { phase !in terminal })
        }
        return true
    }

    private fun holdDispatch() {
        held = true
        resumeAfter = null
        buffer?.`suspend`()
    }

    private companion object {
        val terminal = setOf(Phase.COMPLETED, Phase.INTERRUPTED, Phase.FAILED)
    }
}
