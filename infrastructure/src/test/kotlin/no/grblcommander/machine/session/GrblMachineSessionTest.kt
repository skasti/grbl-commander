package no.grblcommander.machine.session

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import no.grblcommander.machine.session.MachineSnapshot.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GrblMachineSessionTest {
    private suspend fun connected(scope: CoroutineScope, transport: InMemoryTransport): GrblMachineSession {
        val session = GrblMachineSession(transport, scope, commandTimeoutMillis = 1_000)
        session.connect()
        assertEquals("?", transport.writes.receive())
        return session
    }

    @Test fun `handshake status identification and rejection preserve acknowledgment semantics`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            try {
                val sent = CompletableDeferred<Unit>()
                val finishWrite = CompletableDeferred<Unit>()
                transport.onWrite = { if (it == "\$I\n") finishWrite.await() }
                val identification = async {
                    session.executeLine("\$I", session.normalCommandEpoch, onSent = { sent.complete(Unit) })
                }
                assertEquals("\$I\n", transport.writes.receive())
                assertFalse(sent.isCompleted)
                finishWrite.complete(Unit)
                sent.await()
                assertFalse(identification.isCompleted)
                transport.emit("[VER:1.1f:grblHAL]\r\n[OPT:VN,35,1024]\r\nok\r\n")
                assertEquals(listOf("[VER:1.1f:grblHAL]", "[OPT:VN,35,1024]"), identification.await().responseLines)
                val rejected = async { session.executeLine("INVALID") }
                assertEquals("INVALID\n", transport.writes.receive())
                transport.emit("error:20\r\n")
                assertEquals(20, rejected.await().errorCode)
                assertEquals(Connection.CONNECTED, session.state.value.connection)
                assertEquals("Idle", session.queryStatus().executionState)
                assertNotNull(session.state.value.reportedAt)
                // Acknowledgment does not infer Run, Idle or execution completion.
                assertEquals("Idle", session.state.value.status?.executionState)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `realtime traffic bypasses queued lines and outstanding acknowledgment`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            try {
                val first = async { session.executeLine("G4 P0") }
                assertEquals("G4 P0\n", transport.writes.receive())
                val second = async { session.executeLine("\$I") }
                yield()
                session.sendRealtime(RealtimeCommand.FEED_HOLD)
                assertEquals("!", transport.writes.receive())
                val status = async { session.queryStatus() }
                assertEquals("?", transport.writes.receive())
                assertEquals("Idle", status.await().executionState)
                assertFalse(first.isCompleted)
                assertFalse(second.isCompleted)
                transport.emit("ok\n")
                assertTrue(first.await().accepted)
                assertEquals("\$I\n", transport.writes.receive())
                transport.emit("[VER:test]\nok\n")
                assertEquals(listOf("[VER:test]"), second.await().responseLines)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `disconnect fails admitted and queued commands without replay`() = runBlocking<Unit> {
        withTimeout(5_000) {
            supervisorScope {
                val transport = InMemoryTransport()
                val session = connected(this, transport)
                val first = async { session.executeLine("G4 P0") }
                assertEquals("G4 P0\n", transport.writes.receive())
                val second = async { session.executeLine("\$I") }
                transport.disconnect()
                assertFailsWith<IOException> { first.await() }
                assertFailsWith<IOException> { second.await() }
                session.awaitClosed()
                assertEquals(Connection.FAILED, session.state.value.connection)
                assertNotNull(session.state.value.status) // retained observation, explicitly not live
                assertTrue(transport.writes.tryReceive().isFailure)
                assertFailsWith<IllegalStateException> { session.connect() }
                session.close()
                assertEquals(Connection.FAILED, session.state.value.connection)
            }
        }
    }

    @Test fun `cancelled queued command sends nothing and keeps healthy session`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            try {
                val first = async { session.executeLine("\$I") }
                assertEquals("\$I\n", transport.writes.receive())
                val queued = launch { session.executeLine("G4 P0") }
                yield()
                queued.cancelAndJoin()
                transport.emit("ok\n")
                assertTrue(first.await().accepted)
                assertEquals(Connection.CONNECTED, session.state.value.connection)
                assertTrue(transport.writes.tryReceive().isFailure)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `admitted cancellation drains its response without closing realtime access`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            val admitted = launch { session.executeLine("\$I") }
            assertEquals("\$I\n", transport.writes.receive())
            try {
                admitted.cancelAndJoin()
                assertEquals(Connection.CONNECTED, session.state.value.connection)
                assertFalse(session.state.value.normalCommandsSynchronized)
                assertFalse(transport.closed)
                assertFailsWith<CommandCorrelationException> { session.executeLine("G4 P0") }
                session.sendRealtime(RealtimeCommand.FEED_HOLD)
                assertEquals("!", transport.writes.receive())
                transport.emit("ok\n")
                session.state.first { it.normalCommandsSynchronized }
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `missing acknowledgment blocks normal lines but keeps status and realtime access`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = GrblMachineSession(transport, this, commandTimeoutMillis = 30)
            session.connect()
            try {
                assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { session.executeLine("\$I") }
                assertEquals("?", transport.writes.receive())
                assertEquals("\$I\n", transport.writes.receive())
                assertEquals(Connection.CONNECTED, session.state.value.connection)
                assertFalse(session.state.value.normalCommandsSynchronized)
                assertFailsWith<CommandCorrelationException> { session.executeLine("\$G") }
                assertEquals("Idle", session.queryStatus().executionState)
                assertFalse(session.state.value.normalCommandsSynchronized) // status is not an acknowledgment
                assertEquals("?", transport.writes.receive())
                transport.emit("error:9\n")
                session.state.first { it.normalCommandsSynchronized }
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `handshake timeout disposes reader and transport`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport().apply { autoStatus = false }
            val session = GrblMachineSession(transport, this, handshakeTimeoutMillis = 30)
            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { session.connect() }
            session.awaitClosed()
            assertTrue(transport.closed)
        }
    }

    @Test fun `external restart requires explicit recovery without closing the transport`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            try {
                transport.emit("GrblHAL 1.1f ['$' for help]\n")
                session.state.first { it.resetRequired }
                assertEquals(Connection.CONNECTED, session.state.value.connection)
                assertFalse(transport.closed)
                assertFailsWith<CommandCorrelationException> { session.executeLine("\$I") }
                session.sendRealtime(RealtimeCommand.SOFT_RESET)
                assertEquals(Connection.CONNECTED, session.state.value.connection)
                assertTrue(session.state.value.normalCommandsSynchronized)
                assertFalse(session.state.value.resetRequired)
                assertEquals(1, transport.connectCount)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `parent cancellation and explicit close dispose all owned work`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val parent = SupervisorJob()
            val transport = InMemoryTransport()
            val session = connected(CoroutineScope(coroutineContext + parent), transport)
            parent.cancelAndJoin()
            session.awaitClosed()
            assertTrue(transport.closed)
            assertTrue(session.state.value.connection in setOf(Connection.CLOSED, Connection.FAILED))
            session.close()
            session.close()
        }
    }

    @Test fun `startup diagnostics precede readiness without being command responses`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport().apply { autoStatus = false }
            val session = GrblMachineSession(transport, this)
            try {
                val connection = async { session.connect() }
                assertEquals("?", transport.writes.receive())
                transport.emit("error:7\r\nGrblHAL 1.1f ['$' for help]\r\nALARM:1\r\nok\r\n<Alarm|MPos:0,0,0>\r\n")
                connection.await()
                assertEquals(Connection.CONNECTED, session.state.value.connection)
                assertEquals("Alarm", session.state.value.status?.executionState)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `startup invalidates a report received during the handshake write`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport().apply { autoStatus = false }
            val releaseWrite = CompletableDeferred<Unit>()
            var firstWrite = true
            transport.onWrite = {
                if (firstWrite) {
                    firstWrite = false
                    releaseWrite.await()
                }
            }
            val session = GrblMachineSession(transport, this)
            try {
                val connection = async { session.connect() }
                assertEquals("?", transport.writes.receive())
                transport.emit("<Idle|MPos:0,0,0>\n")
                session.state.first { it.reportSequence == 1L }
                transport.emit("GrblHAL 1.1f ['$' for help]\n")
                session.state.first { it.lastMessage?.startsWith("GrblHAL") == true }
                releaseWrite.complete(Unit)
                assertEquals("?", transport.writes.receive())
                assertFalse(connection.isCompleted)
                transport.emit("<Idle|MPos:1,2,3>\n")
                connection.await()
                assertEquals(listOf(1.0, 2.0, 3.0), session.state.value.status?.machinePosition)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `explicit close rejects both admitted and queued work`() = runBlocking<Unit> {
        withTimeout(5_000) {
            supervisorScope {
                val transport = InMemoryTransport()
                val session = connected(this, transport)
                val admitted = async { session.executeLine("\$I") }
                assertEquals("\$I\n", transport.writes.receive())
                val queued = async { session.executeLine("\$G") }
                yield()
                session.close()
                assertFailsWith<IOException> { admitted.await() }
                assertFailsWith<IOException> { queued.await() }
                session.awaitClosed()
                assertEquals(Connection.CLOSED, session.state.value.connection)
                assertTrue(transport.writes.tryReceive().isFailure)
            }
        }
    }

    @Test fun `write errors terminate session`() = runBlocking<Unit> {
        val transport = InMemoryTransport()
        val session = connected(this, transport)
        transport.writeFailure = true
        assertFailsWith<IOException> { session.executeLine("\$I") }
        session.awaitClosed()
        assertEquals(Connection.FAILED, session.state.value.connection)
    }
}
