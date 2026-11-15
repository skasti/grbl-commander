package no.grblcommander.machine.session

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import no.grblcommander.machine.session.CommunicationEvent.*
import kotlin.test.*

class CommunicationEventsTest {
    @Test fun `stalled subscriber does not deprive responsive subscriber of recent events or hide final overflow`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val journal = CommunicationEvents(2)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val slowEvents = mutableListOf<Long>()
            val fastEvents = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val slow = launch(start = CoroutineStart.UNDISPATCHED) {
                journal.events.collect {
                    slowEvents += it.sequence
                    if (it.sequence == 1L) { entered.complete(Unit); release.await() }
                }
            }
            val fast = launch(start = CoroutineStart.UNDISPATCHED) {
                journal.events.collect { fastEvents.send(it.sequence) }
            }
            fun publish() = journal.publish(Direction.RX, Category.INFORMATION, "report", Outcome.RECEIVED)
            try {
                publish(); entered.await(); assertEquals(1L, fastEvents.receive())
                for (sequence in 2L..6L) {
                    publish()
                    assertEquals(sequence, fastEvents.receive())
                }
                // Nothing more is published: loss must already be visible while slow is paused.
                assertEquals(3L, journal.dropped.value)
                assertEquals(listOf(5L, 6L), journal.recent.map { it.sequence })
                release.complete(Unit)
                while (slowEvents.lastOrNull() != 6L) yield()
                assertEquals(listOf(1L, 5L, 6L), slowEvents)
                assertEquals(3L, journal.dropped.value)
            } finally { slow.cancelAndJoin(); fast.cancelAndJoin() }
            // Ordinary replay eviction without subscribers is retention, not overflow.
            repeat(10) { publish() }
            assertEquals(3L, journal.dropped.value)
        }
    }

    @Test fun `overflow counts each evicted event once across subscribers and respects replay and cancellation`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val journal = CommunicationEvents(2)
            fun publish() = journal.publish(Direction.RX, Category.INFORMATION, "report", Outcome.RECEIVED)
            repeat(4) { publish() }
            val entered = List(2) { CompletableDeferred<Unit>() }
            val subscribers = List(2) { index ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    journal.events.collect { entered[index].complete(Unit); awaitCancellation() }
                }
            }
            try {
                entered.forEach { it.await() }
                assertEquals(0L, journal.dropped.value) // replay starts at sequence 3
                repeat(3) { publish() } // sequence 4 and 5 evicted before both subscribers
                assertEquals(2L, journal.dropped.value)
                subscribers.forEach { it.cancelAndJoin() }
                repeat(4) { publish() }
                assertEquals(2L, journal.dropped.value)
                assertEquals(listOf(10L, 11L), journal.recent.map { it.sequence })
            } finally { subscribers.forEach { it.cancelAndJoin() } }
        }
    }

    @Test fun `handshake and all coordinated writes and decoded lines are observed without stealing replies`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport().apply { statusReport = "<Alarm:11|MPos:0,0,0>" }
            val session = GrblMachineSession(transport, this)
            try {
                session.connect()
                assertEquals(11, session.state.value.alarmCode)
                val command = async { session.executeLine("\$I") }
                assertEquals("?", transport.writes.receive())
                assertEquals("\$I\n", transport.writes.receive())
                transport.emit("[EXT: Unfamiliar | payload ]\r\nok\r\n")
                assertEquals(listOf("[EXT: Unfamiliar | payload ]"), command.await().responseLines)
                val rejected = async { session.executeLine("BAD") }
                assertEquals("BAD\n", transport.writes.receive())
                transport.emit("error:20\n")
                assertEquals(20, rejected.await().errorCode)
                transport.emit("ALARM:11\n")
                session.state.first { it.lastMessage == "ALARM:11" }
                session.sendRealtime(RealtimeCommand.FEED_HOLD)
                session.sendRealtime(RealtimeCommand.STATUS_QUERY_ALL)
                session.reset()
                session.queryStatus()
                val events = session.recentCommunication
                assertTrue(events.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
                fun contains(direction: Direction, category: Category, text: String) =
                    events.any { it.direction == direction && it.category == category && it.text == text }
                assertTrue(contains(Direction.TX, Category.COMMAND, "\$I\n"))
                assertTrue(contains(Direction.TX, Category.REALTIME, "!"))
                assertTrue(contains(Direction.TX, Category.STATUS, "?"))
                assertTrue(contains(Direction.TX, Category.STATUS, "0x87"))
                assertTrue(contains(Direction.TX, Category.REALTIME, "0x18"))
                assertTrue(contains(Direction.RX, Category.STATUS, "<Alarm:11|MPos:0,0,0>"))
                assertTrue(contains(Direction.RX, Category.ACKNOWLEDGMENT, "ok"))
                assertTrue(contains(Direction.RX, Category.ERROR, "error:20"))
                assertTrue(contains(Direction.RX, Category.ALARM, "ALARM:11"))
                assertTrue(events.any { it.category == Category.STARTUP })
                assertTrue(contains(Direction.RX, Category.INFORMATION, "[EXT: Unfamiliar | payload ]"))
                assertEquals(Outcome.ATTEMPTED, events.first { it.direction == Direction.TX }.outcome)
                assertEquals(0, session.droppedCommunicationEvents.value)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `local admission sends nothing and transport failures remain attempted or uncertain`() = runBlocking<Unit> {
        supervisorScope {
            val transport = InMemoryTransport()
            val session = GrblMachineSession(transport, this)
            try {
                session.connect()
                val before = session.recentCommunication.size
                assertFailsWith<IllegalArgumentException> { session.executeLine("bad\nline") }
                assertEquals(before, session.recentCommunication.size)
                transport.localWriteFailure = IllegalArgumentException("unsent")
                assertFailsWith<IllegalArgumentException> { session.executeLine("\$I") }
                val rejection = session.recentCommunication.last()
                assertEquals(Outcome.LOCAL_REJECTION, rejection.outcome)
                assertEquals(rejection.sequence - 1, rejection.attemptSequence)
                assertTrue(session.state.value.normalCommandsSynchronized)
                transport.localWriteFailure = CancellationException("partial write possible")
                assertFailsWith<CancellationException> { session.executeLine("G4 P0") }
                assertEquals(Outcome.UNCERTAIN, session.recentCommunication.last().outcome)
                assertFalse(session.state.value.normalCommandsSynchronized)
                transport.writeFailure = true
                assertFailsWith<IOException> { session.sendRealtime(RealtimeCommand.FEED_HOLD) }
                assertEquals(Outcome.UNCERTAIN, session.recentCommunication.last().outcome)
                assertEquals(MachineSnapshot.Connection.FAILED, session.state.value.connection)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `a stalled observer cannot delay acks realtime or alarm handling and overflow is visible`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = GrblMachineSession(transport, this, communicationBufferCapacity = 2)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                session.communication.collect { entered.complete(Unit); release.await() }
            }
            try {
                session.connect()
                entered.await()
                val command = async { session.executeLine("\$I") }
                transport.writes.receive() // handshake
                assertEquals("\$I\n", transport.writes.receive())
                transport.emit("ok\nALARM:1\n")
                assertTrue(command.await().accepted)
                session.state.first { it.resetRequired }
                repeat(100) { session.sendRealtime(RealtimeCommand.FEED_HOLD) }
                assertEquals("Idle", session.queryStatus().executionState)
                assertTrue(session.state.value.resetRequired)
                assertTrue(session.droppedCommunicationEvents.value > 0)
                assertTrue(session.recentCommunication.size <= 2)
                assertEquals(MachineSnapshot.Connection.CONNECTED, session.state.value.connection)
            } finally { observer.cancelAndJoin(); session.close(); session.awaitClosed() }
        }
    }
}
