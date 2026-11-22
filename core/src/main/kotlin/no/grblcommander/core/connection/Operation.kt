package no.grblcommander.core.connection

import java.time.Duration
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A single request for machine work. The handle retains its outcome across reconnects. */
class Operation internal constructor(
    val id: Long,
    val sessionId: Long,
    val purpose: Purpose,
    val sourceId: String,
    lines: List<String>,
) {
    val lines: List<String> = Collections.unmodifiableList(lines.toList())

    enum class Purpose { MDI, PROGRAM }

    enum class Phase { PREPARED, RUNNING, HELD, COMPLETED, FAILED, INTERRUPTED }

    /** Positions in [Operation.lines] are one-based. An absent progress value is unknown. */
    data class Progress(
        val phase: Phase = Phase.PREPARED,
        val lastSentLineNumber: Int? = null,
        val reply: BufferState.Reply = BufferState.Reply.Empty,
        val executingLineNumber: Int? = null,
        val observedAt: Instant? = null,
        val activeLineElapsed: Duration? = null,
        val detail: String? = null,
    )

    private val mutableState = MutableStateFlow(Progress())
    val state = mutableState.asStateFlow()

    internal fun publish(progress: Progress) {
        mutableState.value = progress
    }
}
