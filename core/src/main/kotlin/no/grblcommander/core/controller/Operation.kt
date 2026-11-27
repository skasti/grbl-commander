package no.grblcommander.core.controller

import java.time.Instant
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
import no.grblcommander.core.commands.Command
import no.grblcommander.core.commands.CommandBuffer
import no.grblcommander.core.commands.CommandResult
import no.grblcommander.core.commands.CommandSource
import no.grblcommander.machine.session.MachineSnapshot

class Operation internal constructor(
    lines: List<String>,
    val source: CommandSource,
    internal val bufferCapacity: Int,
    private val now: () -> Instant,
) {
    constructor(
        lines: List<String>,
        source: CommandSource,
        now: () -> Instant = Instant::now,
    ) : this(lines, source, CommandBuffer.DEFAULT_CAPACITY, now)

    internal constructor(lines: List<String>, source: CommandSource, bufferCapacity: Int) :
        this(lines, source, bufferCapacity, Instant::now)

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
    private var lastSnapshot: MachineSnapshot? = null
    private var holdSequence = -1L
    private var held = false
    private var resumeAfter: Long? = null
    private var finalAckSequence = Long.MAX_VALUE
    private var interruption: String? = null

    internal fun prepare(
        commandBuffer: CommandBuffer,
        parent: CoroutineScope,
        initialSnapshot: MachineSnapshot,
    ) = synchronized(gate) {
        check(state.value.phase == Phase.READY) { "Operation already accepted" }
        commandBuffer.bindReport(::record)
        lastSnapshot = initialSnapshot
        holdSequence = initialSnapshot.lastHoldSequence
        buffer = commandBuffer
        execution = parent.launch(start = CoroutineStart.LAZY) { executor() }
    }

    private suspend fun CoroutineScope.executor() {
        val commandBuffer = synchronized(gate) {
            checkNotNull(buffer) { "Operation has not been prepared" }
        }
        commandBuffer.use {
            synchronized(gate) {
                ensureActive()
                if (held) commandBuffer.`suspend`()
                commandBuffer.start(this)
            }

            for (i in lines.indices) {
                commandBuffer.awaitDispatchEligibility()
                commandBuffer.enqueue(Command(source, i + 1, program.wire(i)))
                state.update { it.copy(enqueued = i + 1) }
            }

            commandBuffer.finishProduction()
            commandBuffer.awaitClosed()
            progress.first { it.phase in terminal }
        }
    }

    internal fun start(onFinished: (operation: Operation) -> Unit) {
        val job = synchronized(gate) {
            check(state.value.phase == Phase.READY) { "Operation has already started" }
            val preparedExecution = checkNotNull(execution) { "Operation has not been prepared" }
            state.value = Progress(Phase.RUNNING)
            preparedExecution
        }
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
                onFinished(this)
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

    internal fun stateHandler(snapshot: MachineSnapshot) {
        val invalidated = synchronized(gate) { !observeSnapshot(snapshot, reevaluateDuplicate = false) }
        if (invalidated) interrupt("Controller session or command correlation lost")
    }

    private fun record(
        command: Command,
        outcome: CommandResult,
        snapshot: MachineSnapshot,
    ) {
        val invalidated = synchronized(gate) {
            val line = checkNotNull(command.line) { "Operation command must have a source line" }
            val sequence = snapshot.reportSequence
            if (outcome == CommandResult.Sent) sentAfterReport[line - 1] = sequence
            if (outcome is CommandResult.Reply && outcome.acknowledgment.accepted && line == lines.size) {
                finalAckSequence = outcome.acknowledgment.reportSequence ?: sequence
            }
            state.update {
                when (outcome) {
                    CommandResult.Sent -> it.copy(sent = line)
                    CommandResult.UncertainWrite -> it.copy(uncertainWrite = line)
                    is CommandResult.Interrupted -> it.copy(
                        phase = Phase.INTERRUPTED, failedLine = line, error = interruption ?: outcome.cause.message,
                    )
                    is CommandResult.Reply -> when {
                        !outcome.acknowledgment.accepted -> it.copy(
                            phase = Phase.FAILED, failedLine = line, error = "error:${outcome.acknowledgment.errorCode}",
                        )
                        line == lines.size -> it.copy(acknowledged = line, phase = Phase.WAITING_FOR_IDLE)
                        else -> it.copy(acknowledged = line)
                    }
                }
            }
            // Re-evaluate after the command result changes progress, even if the flow saw this snapshot first.
            !observeSnapshot(snapshot, reevaluateDuplicate = true)
        }
        if (invalidated) interrupt("Controller session or command correlation lost")
    }

    private fun observeSnapshot(snapshot: MachineSnapshot, reevaluateDuplicate: Boolean): Boolean {
        if (interruption != null) return true
        if (!snapshot.usable()) return false
        if (snapshot == lastSnapshot && !reevaluateDuplicate) return true
        if (snapshot != lastSnapshot) lastSnapshot = snapshot

        if (snapshot.lastHoldSequence > holdSequence) {
            holdSequence = snapshot.lastHoldSequence
            holdDispatch()
        }
        val mode = snapshot.status?.executionState?.substringBefore(':')
        val sequence = snapshot.reportSequence
        val fresh = snapshot.isFresh(now)
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
