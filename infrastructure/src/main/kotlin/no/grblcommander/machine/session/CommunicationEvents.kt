package no.grblcommander.machine.session

import java.time.Instant
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded multicast; dropping diagnostics must never suspend protocol I/O. */
internal class CommunicationEvents(private val capacity: Int) {
    init { require(capacity > 0) }
    private val mutableEvents = MutableSharedFlow<CommunicationEvent>(
        replay = capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private class Subscriber(var nextSequence: Long)
    private val subscribers = mutableSetOf<Subscriber>()
    // DROP_OLDEST always accepts tryEmit; one cursor per subscriber makes overflow
    // visible immediately, even if a subscriber never resumes and traffic stops.
    val recent: List<CommunicationEvent> get() = mutableEvents.replayCache
    val events = flow {
        val subscriber = synchronized(this@CommunicationEvents) {
            Subscriber(oldestSequence()).also { subscribers.add(it) }
        }
        try {
            mutableEvents.collect { event ->
                val deliver = synchronized(this@CommunicationEvents) {
                    // A collector can be preempted between taking a SharedFlow
                    // value and recording its cursor. Do not hand out an evicted
                    // value that has already been counted as lost.
                    (event.sequence >= oldestSequence()).also {
                        if (it) subscriber.nextSequence = event.sequence + 1
                    }
                }
                if (deliver) emit(event)
            }
        } finally {
            synchronized(this@CommunicationEvents) { subscribers.remove(subscriber) }
        }
    }
    private val mutableDropped = MutableStateFlow(0L)
    val dropped = mutableDropped.asStateFlow()
    private var sequence = 0L
    private fun oldestSequence() = maxOf(1L, sequence - capacity + 1)

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
        val evicted = event.sequence - capacity
        if (subscribers.any { it.nextSequence <= evicted }) mutableDropped.value++
        mutableEvents.tryEmit(event)
        return event.sequence
    }
}
