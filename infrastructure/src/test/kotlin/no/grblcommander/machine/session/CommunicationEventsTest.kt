package no.grblcommander.machine.session

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import no.grblcommander.machine.session.CommunicationEvent.*
import kotlin.test.*

class CommunicationEventsTest {
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
                val events = session.communication.replayCache
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
                val before = session.communication.replayCache.size
                assertFailsWith<IllegalArgumentException> { session.executeLine("bad\nline") }
                assertEquals(before, session.communication.replayCache.size)
                transport.localWriteFailure = IllegalArgumentException("unsent")
                assertFailsWith<IllegalArgumentException> { session.executeLine("\$I") }
                val rejection = session.communication.replayCache.last()
                assertEquals(Outcome.LOCAL_REJECTION, rejection.outcome)
                assertEquals(rejection.sequence - 1, rejection.attemptSequence)
                assertTrue(session.state.value.normalCommandsSynchronized)
                transport.localWriteFailure = CancellationException("partial write possible")
                assertFailsWith<CancellationException> { session.executeLine("G4 P0") }
                assertEquals(Outcome.UNCERTAIN, session.communication.replayCache.last().outcome)
                assertFalse(session.state.value.normalCommandsSynchronized)
                transport.writeFailure = true
                assertFailsWith<IOException> { session.sendRealtime(RealtimeCommand.FEED_HOLD) }
                assertEquals(Outcome.UNCERTAIN, session.communication.replayCache.last().outcome)
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
                assertTrue(session.communication.replayCache.size <= 2)
                assertEquals(MachineSnapshot.Connection.CONNECTED, session.state.value.connection)
            } finally { observer.cancelAndJoin(); session.close(); session.awaitClosed() }
        }
    }
}
