package no.grblcommander.machine.session

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrblMachineSessionRecoveryTest {
    private suspend fun connected(scope: CoroutineScope, transport: InMemoryTransport): GrblMachineSession {
        val session = GrblMachineSession(transport, scope, commandTimeoutMillis = 1_000)
        session.connect()
        assertEquals("?", transport.writes.receive())
        return session
    }

    @Test fun `alarm retains connection code and operator-controlled recovery`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            try {
                transport.emit("ALARM:1\n")
                session.state.first { it.alarmCode == 1 }
                assertEquals(Connection.CONNECTED, session.state.value.connection)
                assertNull(session.state.value.status) // stale Idle report invalidated
                assertNull(session.state.value.failure)
                assertTrue(transport.writes.tryReceive().isFailure) // no implicit unlock/home/reset
                transport.statusReport = "<Alarm|MPos:0,0,0>"
                assertEquals("Alarm", session.queryStatus().executionState)
                assertEquals("?", transport.writes.receive())
                session.sendRealtime(RealtimeCommand.FEED_HOLD)
                assertEquals("!", transport.writes.receive())
                val unlock = async { session.executeLine("\$X") }
                assertEquals("\$X\n", transport.writes.receive())
                transport.emit("ok\n")
                assertTrue(unlock.await().accepted)
                assertEquals(1, session.state.value.alarmCode) // ok is not confirmed state recovery
                transport.statusReport = "<Idle|MPos:0,0,0>"
                session.queryStatus()
                assertNull(session.state.value.alarmCode)
                assertFalse(transport.closed)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `alarm abandons pending and queued lines and drains only the old acknowledgment`() = runBlocking<Unit> {
        withTimeout(5_000) {
            for (lateResponse in listOf("ok\n", "error:9\n")) {
                supervisorScope {
                    val transport = InMemoryTransport()
                    val session = connected(this, transport)
                    try {
                        val first = async { session.executeLine("G4 P0") }
                        assertEquals("G4 P0\n", transport.writes.receive())
                        val queued = async { session.executeLine("\$I") }
                        yield()
                        transport.emit("ALARM:2\n")
                        assertFailsWith<CommandCorrelationException> { first.await() }
                        assertFailsWith<CommandCorrelationException> { queued.await() }
                        assertEquals(Connection.CONNECTED, session.state.value.connection)
                        assertFalse(session.state.value.normalCommandsSynchronized)
                        assertFailsWith<CommandCorrelationException> { session.executeLine("\$X") }
                        transport.statusReport = "<Alarm|MPos:0,0,0>"
                        session.queryStatus()
                        assertEquals("?", transport.writes.receive())
                        assertFalse(session.state.value.normalCommandsSynchronized)
                        session.sendRealtime(RealtimeCommand.FEED_HOLD)
                        assertEquals("!", transport.writes.receive())
                        assertTrue(transport.writes.tryReceive().isFailure)
                        transport.emit(lateResponse)
                        session.state.first { it.normalCommandsSynchronized }
                        val recovery = async { session.executeLine("\$X") }
                        assertEquals("\$X\n", transport.writes.receive())
                        assertFalse(recovery.isCompleted) // old acknowledgment did not satisfy recovery
                        transport.emit("ok\n")
                        assertTrue(recovery.await().accepted)
                        assertTrue(transport.writes.tryReceive().isFailure) // neither failed line replayed
                    } finally { session.close(); session.awaitClosed() }
                }
            }
        }
    }

    @Test fun `alarm before normal transmission does not create a phantom acknowledgment`() = runBlocking<Unit> {
        withTimeout(5_000) {
            supervisorScope {
                val transport = InMemoryTransport()
                val session = connected(this, transport)
                val releaseWrite = CompletableDeferred<Unit>()
                transport.onWrite = { if (it == "!") releaseWrite.await() }
                try {
                    val hold = async { session.sendRealtime(RealtimeCommand.FEED_HOLD) }
                    assertEquals("!", transport.writes.receive())
                    val line = async { session.executeLine("\$I") }
                    yield()
                    transport.emit("ALARM:3\n")
                    session.state.first { it.alarmCode == 3 }
                    releaseWrite.complete(Unit)
                    hold.await()
                    assertFailsWith<CommandCorrelationException> { line.await() }
                    assertTrue(session.state.value.normalCommandsSynchronized)
                    assertTrue(transport.writes.tryReceive().isFailure)
                    val recovery = async { session.executeLine("\$X") }
                    assertEquals("\$X\n", transport.writes.receive())
                    transport.emit("ok\n")
                    assertTrue(recovery.await().accepted)
                } finally { session.close(); session.awaitClosed() }
            }
        }
    }

    @Test fun `cancellation before realtime admission sends nothing and keeps normal correlation`() = runBlocking<Unit> {
        withTimeout(5_000) {
            for (command in listOf(RealtimeCommand.STATUS_QUERY, RealtimeCommand.SOFT_RESET)) {
                val transport = InMemoryTransport()
                val session = connected(this, transport)
                val releaseWrite = CompletableDeferred<Unit>()
                transport.onWrite = { if (it == "\$I\n") releaseWrite.await() }
                try {
                    val line = async { session.executeLine("\$I") }
                    assertEquals("\$I\n", transport.writes.receive())
                    val realtime = launch { session.sendRealtime(command) }
                    yield()
                    realtime.cancelAndJoin()
                    assertEquals(Connection.CONNECTED, session.state.value.connection)
                    assertNull(session.state.value.lastRealtimeFailure)
                    releaseWrite.complete(Unit)
                    session.sendRealtime(RealtimeCommand.FEED_HOLD)
                    assertEquals("!", transport.writes.receive())
                    transport.emit("ok\n")
                    assertTrue(line.await().accepted)
                    assertTrue(transport.writes.tryReceive().isFailure)
                } finally { session.close(); session.awaitClosed() }
            }
        }
    }

    @Test fun `local realtime rejection does not break a pending normal command`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            try {
                val line = async { session.executeLine("\$I") }
                assertEquals("\$I\n", transport.writes.receive())
                for (command in listOf(RealtimeCommand.STATUS_QUERY, RealtimeCommand.SOFT_RESET)) {
                    transport.localWriteFailure = IllegalArgumentException("local rejection")
                    assertFailsWith<IllegalArgumentException> { session.sendRealtime(command) }
                }
                assertEquals(Connection.CONNECTED, session.state.value.connection)
                assertTrue(session.state.value.lastRealtimeFailure!!.contains("local rejection"))
                assertTrue(session.state.value.normalCommandsSynchronized)
                session.sendRealtime(RealtimeCommand.FEED_HOLD)
                assertEquals("!", transport.writes.receive())
                transport.emit("error:20\n")
                assertEquals(20, line.await().errorCode)
                val next = async { session.executeLine("\$G") }
                assertEquals("\$G\n", transport.writes.receive())
                transport.emit("ok\n")
                assertTrue(next.await().accepted)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `local normal-command rejection sends nothing and does not lose correlation`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            try {
                assertFailsWith<IllegalArgumentException> { session.executeLine("bad\nline") }
                transport.localWriteFailure = IllegalArgumentException("local argument rejection before IO")
                assertFailsWith<IllegalArgumentException> { session.executeLine("\$I") }
                assertEquals(Connection.CONNECTED, session.state.value.connection)
                assertTrue(session.state.value.normalCommandsSynchronized)
                assertTrue(transport.writes.tryReceive().isFailure)
                val line = async { session.executeLine("\$G") }
                assertEquals("\$G\n", transport.writes.receive())
                transport.emit("ok\n")
                assertTrue(line.await().accepted)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `known unsent local rejection does not invalidate other queued lines`() = runBlocking<Unit> {
        withTimeout(5_000) {
            supervisorScope {
                val transport = InMemoryTransport()
                val session = connected(this, transport)
                val releaseWrite = CompletableDeferred<Unit>()
                transport.onWrite = { if (it == "!") releaseWrite.await() }
                try {
                    val hold = async { session.sendRealtime(RealtimeCommand.FEED_HOLD) }
                    assertEquals("!", transport.writes.receive())
                    val rejected = async { session.executeLine("\$I") }
                    yield()
                    val queued = async { session.executeLine("\$G") }
                    yield()
                    transport.localWriteFailure = IllegalArgumentException("local rejection before IO")
                    releaseWrite.complete(Unit)
                    hold.await()
                    assertFailsWith<IllegalArgumentException> { rejected.await() }
                    assertEquals("\$G\n", transport.writes.receive())
                    transport.emit("ok\n")
                    assertTrue(queued.await().accepted)
                    assertTrue(session.state.value.normalCommandsSynchronized)
                } finally { session.close(); session.awaitClosed() }
            }
        }
    }

    @Test fun `uncertain realtime error or cancellation preserves further control without replay`() = runBlocking<Unit> {
        withTimeout(5_000) {
            for (cancel in listOf(false, true)) {
                val transport = InMemoryTransport()
                val session = connected(this, transport)
                try {
                    val line = async { session.executeLine("\$I") }
                    assertEquals("\$I\n", transport.writes.receive())
                    transport.onWrite = { if (it == "?") {
                        if (cancel) awaitCancellation() else throw IOException("write outcome uncertain; connection usable")
                    } }
                    if (cancel) {
                        val realtime = launch { session.sendRealtime(RealtimeCommand.STATUS_QUERY) }
                        assertEquals("?", transport.writes.receive())
                        realtime.cancelAndJoin()
                    } else {
                        assertFailsWith<IOException> { session.sendRealtime(RealtimeCommand.STATUS_QUERY) }
                        assertEquals("?", transport.writes.receive())
                    }
                    assertEquals(Connection.CONNECTED, session.state.value.connection)
                    assertNull(session.state.value.failure)
                    assertTrue(session.state.value.lastRealtimeFailure!!.contains("unknown"))
                    assertTrue(session.state.value.normalCommandsSynchronized)
                    transport.onWrite = null
                    session.sendRealtime(RealtimeCommand.FEED_HOLD)
                    assertEquals("!", transport.writes.receive())
                    transport.emit("ok\n")
                    assertTrue(line.await().accepted)
                    assertTrue(transport.writes.tryReceive().isFailure)
                    assertFalse(transport.closed)
                } finally { session.close(); session.awaitClosed() }
            }
        }
    }

    @Test fun `genuine realtime transport loss terminates connection and fails pending lines`() = runBlocking<Unit> {
        withTimeout(5_000) {
            supervisorScope {
                val transport = InMemoryTransport()
                val session = connected(this, transport)
                val line = async { session.executeLine("\$I") }
                assertEquals("\$I\n", transport.writes.receive())
                transport.writeFailure = true
                assertFailsWith<IOException> { session.sendRealtime(RealtimeCommand.FEED_HOLD) }
                assertFailsWith<IOException> { line.await() }
                session.awaitClosed()
                assertEquals(Connection.FAILED, session.state.value.connection)
                assertFalse(transport.isConnected)
                assertFailsWith<IOException> { session.queryStatus() }
            }
        }
    }

    @Test fun `missed status report does not prevent a later hold or status query`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = GrblMachineSession(transport, this, commandTimeoutMillis = 100)
            session.connect()
            assertEquals("?", transport.writes.receive())
            try {
                transport.autoStatus = false
                assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { session.queryStatus() }
                assertEquals("?", transport.writes.receive())
                assertEquals(Connection.CONNECTED, session.state.value.connection)
                session.sendRealtime(RealtimeCommand.FEED_HOLD)
                assertEquals("!", transport.writes.receive())
                transport.autoStatus = true
                assertEquals("Idle", session.queryStatus().executionState)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `unsolicited acknowledgment blocks only normal traffic and status cannot resynchronize it`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            try {
                transport.emit("ok\n")
                session.state.first { !it.normalCommandsSynchronized }
                assertEquals(Connection.CONNECTED, session.state.value.connection)
                assertFailsWith<CommandCorrelationException> { session.executeLine("\$I") }
                session.queryStatus()
                assertFalse(session.state.value.normalCommandsSynchronized)
                assertEquals("?", transport.writes.receive())
                session.sendRealtime(RealtimeCommand.FEED_HOLD)
                assertEquals("!", transport.writes.receive())
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `response collection limit interrupts only the line and continues reading`() = runBlocking<Unit> {
        withTimeout(5_000) {
            supervisorScope {
                val transport = InMemoryTransport()
                val session = connected(this, transport)
                try {
                    val line = async { session.executeLine("\$I") }
                    assertEquals("\$I\n", transport.writes.receive())
                    transport.emit("[MSG:notification]\n".repeat(300))
                    assertFailsWith<CommandCorrelationException> { line.await() }
                    assertEquals(Connection.CONNECTED, session.state.value.connection)
                    session.sendRealtime(RealtimeCommand.FEED_HOLD)
                    assertEquals("!", transport.writes.receive())
                    transport.emit("ok\n")
                    session.state.first { it.normalCommandsSynchronized }
                    assertEquals("Idle", session.queryStatus().executionState)
                } finally { session.close(); session.awaitClosed() }
            }
        }
    }

    @Test fun `uncertain explicitly requested reset still invalidates the session`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            transport.onWrite = { throw IOException("reset may have arrived") }
            assertFailsWith<IOException> { session.sendRealtime(RealtimeCommand.SOFT_RESET) }
            session.awaitClosed()
            assertEquals(Connection.FAILED, session.state.value.connection)
        }
    }
}
