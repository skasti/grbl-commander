package no.grblcommander.core.connection

import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import no.grblcommander.machine.protocol.ControllerStatus
import no.grblcommander.machine.session.*
import kotlin.test.*

class ControllerTrafficTest {
    private class Session : MachineSession {
        private val events = MutableSharedFlow<CommunicationEvent>(extraBufferCapacity = 256)
        private val recent = ArrayDeque<CommunicationEvent>()
        override val communication: Flow<CommunicationEvent> = events
        override val recentCommunication: List<CommunicationEvent> get() = recent.toList()
        override val droppedCommunicationEvents = MutableStateFlow(0L)
        override val state = MutableStateFlow(MachineSnapshot())
        override suspend fun connect() { state.value = state.value.copy(connection = MachineSnapshot.Connection.CONNECTED) }
        fun publish(sequence: Long) {
            val event = CommunicationEvent(sequence, Instant.now(), CommunicationEvent.Direction.RX,
                CommunicationEvent.Category.INFORMATION, "line $sequence", CommunicationEvent.Outcome.RECEIVED)
            if (recent.size == 256) recent.removeFirst()
            recent.addLast(event)
            check(events.tryEmit(event))
        }
        override suspend fun executeLine(command: String) = CommandAcknowledgment(null, emptyList())
        override suspend fun executeLine(
            command: String,
            expectedEpoch: Long,
            onUncertainWrite: () -> Unit,
            onSent: () -> Unit,
        ): CommandAcknowledgment = error("This fixture does not send fenced commands")
        override suspend fun sendRealtime(command: RealtimeCommand) = Unit
        override suspend fun reset() = Unit
        override suspend fun queryStatus(): ControllerStatus = error("Not needed")
        override fun close() { state.value = state.value.copy(connection = MachineSnapshot.Connection.CLOSED) }
        override fun prepareSessionTask(block: suspend () -> Unit): Job = error("Post-connect is disabled")
        override suspend fun awaitClosed() = Unit
    }
    private val endpoint = ControllerEndpoint.Tcp("localhost", 1234, no.grblcommander.core.connection.noPostConnectActions())

    @Test fun `slow subscriber never blocks publication diagnostics retain 5000 and cursor exposes dropped events`() = runBlocking<Unit> {
        withTimeout(10_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val source = Session()
            val owner = ControllerConnection(scope, { _, _ -> source }, trafficCapacity = 2)
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val received = mutableListOf<ControllerTraffic>()
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                owner.traffic.collect {
                    received += it
                    entered.complete(Unit)
                    release.await()
                    if (it.event.sequence == 10_000L) finished.complete(Unit)
                }
            }
            try {
                owner.connect(endpoint)
                source.publish(1)
                entered.await()
                val original = owner.diagnosticSnapshot()
                // Publication completes while the consumer is still deliberately stalled.
                withContext(Dispatchers.Default) { for (sequence in 2L..10_000L) source.publish(sequence) }
                val snapshot = owner.diagnosticSnapshot()
                assertEquals(5_000, snapshot.size)
                assertEquals(5_001L, snapshot.first().event.sequence)
                assertEquals(10_000L, snapshot.last().cursor)
                assertEquals(listOf(1L), original.map { it.cursor }) // Earlier explicit snapshots never mutate.
                release.complete(Unit)
                finished.await()
                assertTrue(received.zipWithNext().any { (a, b) -> b.cursor - a.cursor > 1 })
                assertEquals(10_000L, owner.trafficCursor)
            } finally { release.complete(Unit); observer.cancelAndJoin(); owner.close(); owner.awaitClosed(); scope.cancel() }
        }
    }

    @Test fun `global cursor preserves source gaps across sessions without adding unrelated drop counters`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessions = mutableListOf<Session>()
        val owner = ControllerConnection(scope, { _, _ -> Session().also { sessions += it } })
        try {
            owner.connect(endpoint)
            sessions[0].publish(1)
            sessions[0].publish(5) // Three events missed by the core's session observer.
            sessions[0].droppedCommunicationEvents.value = 99 // Other subscriber losses are not terminal losses.
            owner.connect(endpoint)
            sessions[1].publish(3) // Two missing events at the beginning of a new session.
            val snapshot = owner.diagnosticSnapshot()
            assertEquals(listOf(1L, 5L, 8L), snapshot.map { it.cursor })
            assertEquals(listOf(1L, 1L, 2L), snapshot.map { it.sessionId })
            assertEquals(listOf(1L, 5L, 3L), snapshot.map { it.event.sequence })
        } finally { owner.close(); owner.awaitClosed(); scope.cancel() }
    }

    @Test fun `subscribe before snapshot queues concurrent events and session cursor deduplicates overlap`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val source = Session()
        val owner = ControllerConnection(scope, { _, _ -> source })
        try {
            owner.connect(endpoint)
            (1L..3L).forEach(source::publish)
            val received = mutableListOf<ControllerTraffic>()
            var cursor = 0L
            fun accept(event: ControllerTraffic) { if (event.cursor > cursor) { received += event; cursor = event.cursor } }
            owner.traffic.onSubscription {
                source.publish(4) // Registered subscriber, not yet initialized.
                owner.diagnosticSnapshot().forEach(::accept)
                source.publish(5) // Snapshot taken, collection not yet entered.
            }.take(2).collect(::accept)
            assertEquals((1L..5L).toList(), received.map { it.cursor })
            assertEquals(5, received.map { it.sessionId to it.event.sequence }.distinct().size)
        } finally { owner.close(); owner.awaitClosed(); scope.cancel() }
    }
}
