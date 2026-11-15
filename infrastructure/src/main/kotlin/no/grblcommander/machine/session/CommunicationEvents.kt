package no.grblcommander.machine.session

import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded multicast; dropping diagnostics must never suspend protocol I/O. */
internal class CommunicationEvents(capacity: Int) {
    init { require(capacity > 0) }
    private val mutableEvents = MutableSharedFlow<CommunicationEvent>(replay = capacity)
    val events = mutableEvents.asSharedFlow()
    private val mutableDropped = MutableStateFlow(0L)
    val dropped = mutableDropped.asStateFlow()
    private var sequence = 0L

    @Synchronized
    fun publish(
        direction: CommunicationEvent.Direction,
        category: CommunicationEvent.Category,
        text: String,
        outcome: CommunicationEvent.Outcome,
        attemptSequence: Long? = null,
        detail: String? = null,
    ): Long {
        val event = CommunicationEvent(++sequence, Instant.now(), direction, category, text, outcome, attemptSequence, detail)
        if (!mutableEvents.tryEmit(event)) mutableDropped.value++
        return event.sequence
    }
}
