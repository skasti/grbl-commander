package no.grblcommander.desktop.connection

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.MachineSnapshot.Connection
import no.grblcommander.machine.session.RealtimeCommand
import no.grblcommander.machine.transport.SerialConfiguration
import no.grblcommander.machine.transport.SerialTransport
import no.grblcommander.machine.transport.TcpTransport
import kotlin.test.*

class ControllerConnectionTest {
    private val tcp = ControllerEndpoint.Tcp("localhost", 23)
    private val serial = ControllerEndpoint.Serial(SerialConfiguration("test-port"))

    @Test fun `typed endpoints use existing production transports and serial defaults`() {
        val transport = assertIs<TcpTransport>(controllerTransport(tcp))
        assertEquals(tcp.host, transport.host)
        assertEquals(tcp.port, transport.port)
        transport.close()
        val serialTransport = assertIs<SerialTransport>(controllerTransport(serial))
        assertEquals(serial.configuration, serialTransport.configuration)
        assertEquals(115_200, serialTransport.configuration.baudRate)
        assertFalse(serialTransport.configuration.dtr)
        assertFalse(serialTransport.configuration.rts)
        serialTransport.close()
    }

    @Test fun `shared command boundary history and endpoint replacement use one session at a time`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transports = mutableListOf<ConnectionTestTransport>()
            val owner = ControllerConnection(this, { _, scope ->
                assertTrue(transports.all { it.closed })
                ConnectionTestTransport().also { transports += it }.let { GrblMachineSession(it, scope) }
            }, historyCapacity = 5)
            try {
                owner.connect(tcp)
                val first = transports.single()
                first.writes.receive() // only status, no implicit machine operation
                owner.history.first { it.size >= 2 }
                assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
                assertEquals(tcp, owner.state.value.endpoint)
                val line = async { owner.executeLine("\$I") }
                assertEquals("\$I\n", first.writes.receive())
                first.emit("[VER:test]\nok\n")
                assertTrue(line.await().accepted)
                owner.sendRealtime(RealtimeCommand.FEED_HOLD)
                assertEquals("!", first.writes.receive())
                assertEquals("Idle", owner.queryStatus().executionState)
                first.writes.receive()
                owner.history.first { events -> events.last().event.direction.name == "RX" && events.size == 5 }
                val pending = async { runCatching { owner.executeLine("G4 P0") } }
                assertEquals("G4 P0\n", first.writes.receive())
                owner.connect(serial)
                assertContains(pending.await().exceptionOrNull()?.message.orEmpty(), "outcome unknown")
                assertEquals(1, first.closeCount)
                assertEquals(2, transports.size)
                assertEquals("?", transports.last().writes.receive())
                assertTrue(transports.last().writes.tryReceive().isFailure) // no replay
                owner.history.first { it.last().sessionId == 2L }
                assertTrue(owner.history.value.size <= 5)
                owner.disconnect()
                assertEquals(Connection.DISCONNECTED, owner.state.value.snapshot.connection)
                assertEquals(1, transports.last().closeCount)
                assertFailsWith<IOException> { owner.executeLine("\$I") }
                owner.reconfigure(tcp)
                assertEquals(tcp, owner.state.value.endpoint)
                assertEquals(2, transports.size) // reconfiguration alone opens nothing
                owner.connect(tcp)
                assertEquals(3, transports.size)
            } finally { owner.close(); owner.close(); owner.awaitClosed() }
            assertTrue(transports.all { it.closeCount == 1 })
            assertEquals(Connection.CLOSED, owner.state.value.snapshot.connection)
        }
    }

    @Test fun `disconnect and cancellation can interrupt connecting and reconnect is fresh`() = runBlocking<Unit> {
        supervisorScope {
            withTimeout(5_000) {
                val transports = mutableListOf<ConnectionTestTransport>()
                val owner = ControllerConnection(this, { _, scope ->
                    ConnectionTestTransport().apply { autoStatus = false }.also { transports += it }
                        .let { GrblMachineSession(it, scope) }
                })
                try {
                    val attempt = async { runCatching { owner.connect(tcp) } }
                    owner.state.first { it.snapshot.connection == Connection.CONNECTING }
                    owner.disconnect()
                    assertTrue(attempt.await().isFailure)
                    assertEquals(1, transports.single().closeCount)
                    assertEquals(Connection.DISCONNECTED, owner.state.value.snapshot.connection)
                    val next = async { owner.connect(tcp) }
                    owner.state.first { it.snapshot.connection == Connection.CONNECTING }
                    next.cancelAndJoin()
                    assertEquals(2, transports.size)
                    assertEquals(1, transports.last().closeCount)
                } finally { owner.close(); owner.awaitClosed() }
            }
        }
    }

    @Test fun `failed handshake cleans up and explicit retry observes alarm without recovery commands`() = runBlocking<Unit> {
        supervisorScope {
            withTimeout(5_000) {
                val transports = mutableListOf<ConnectionTestTransport>()
                val owner = ControllerConnection(this, { _, scope ->
                    ConnectionTestTransport().apply {
                        autoStatus = transports.isNotEmpty()
                        statusReport = "<Alarm:11|MPos:0,0,0>"
                    }.also { transports += it }.let { GrblMachineSession(it, scope, handshakeTimeoutMillis = 100) }
                })
                try {
                    assertFailsWith<TimeoutCancellationException> { owner.connect(tcp) }
                    owner.state.first { it.snapshot.connection == Connection.FAILED }
                    assertEquals(1, transports.single().closeCount)
                    owner.connect(tcp)
                    assertEquals(11, owner.state.value.snapshot.alarmCode)
                    assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
                    assertEquals("?", transports.last().writes.receive())
                    assertTrue(transports.last().writes.tryReceive().isFailure)
                    owner.disconnect()
                    assertTrue(owner.history.value.isNotEmpty())
                } finally { owner.close(); owner.awaitClosed() }
                assertTrue(transports.all { it.closeCount == 1 })
            }
        }
    }

    @Test fun `factory failure exposes failure health and still permits explicit retry`() = runBlocking<Unit> {
        var fail = true
        val transport = ConnectionTestTransport()
        val owner = ControllerConnection(this, { _, child ->
            if (fail) throw IOException("factory failed")
            GrblMachineSession(transport, child)
        })
        try {
            assertFailsWith<IOException> { owner.connect(serial) }
            assertEquals(Connection.FAILED, owner.state.value.snapshot.connection)
            assertEquals(serial, owner.state.value.endpoint)
            assertFalse(owner.state.value.closing)
            fail = false
            owner.connect(tcp)
            assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun `parent cancellation closes active connection and history survives shutdown`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val parent = SupervisorJob()
            val scope = CoroutineScope(parent + Dispatchers.Default)
            val transport = ConnectionTestTransport()
            val owner = ControllerConnection(scope, { _, child -> GrblMachineSession(transport, child) })
            owner.connect(tcp)
            parent.cancel()
            owner.awaitClosed()
            assertEquals(1, transport.closeCount)
            assertTrue(owner.history.value.isNotEmpty())
            assertFailsWith<IllegalStateException> { owner.connect(tcp) }
        }
    }
}
