package no.grblcommander.core.connection

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import no.grblcommander.machine.session.MachineSession
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

    @Test fun `intentional disconnect clears close diagnostics while unexpected loss remains a failure`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transports = mutableListOf<ConnectionTestTransport>()
            val owner = ControllerConnection(this, { _, child ->
                ConnectionTestTransport().also { transports += it }.let { GrblMachineSession(it, child) }
            })
            try {
                owner.connect(tcp)
                val report = owner.state.value.snapshot.status
                owner.disconnect()
                assertEquals(Connection.DISCONNECTED, owner.state.value.snapshot.connection)
                assertNull(owner.state.value.snapshot.failure)
                assertEquals(report, owner.state.value.snapshot.status)
                assertTrue(owner.state.value.reportIsStale())
                owner.connect(tcp)
                transports.last().disconnect()
                owner.state.first { it.snapshot.connection == Connection.FAILED }
                assertNotNull(owner.state.value.snapshot.failure)
                owner.disconnect() // Explicit acknowledgement clears the displayed error.
                assertNull(owner.state.value.snapshot.failure)
            } finally { owner.close(); owner.awaitClosed() }
            assertNull(owner.state.value.snapshot.failure)
        }
    }

    @Test fun `background status reports remain live while a normal command awaits acknowledgment`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = ConnectionTestTransport()
            val owner = ControllerConnection(this, { _, child -> GrblMachineSession(transport, child) })
            try {
                owner.connect(tcp)
                assertEquals("?", transport.writes.receive())
                val command = async { owner.executeLine("G4 P0") }
                assertEquals("G4 P0\n", transport.nextNonStatusWrite())
                val sequence = owner.state.value.snapshot.reportSequence
                owner.state.first { it.snapshot.reportSequence > sequence }
                assertFalse(command.isCompleted)
                assertNotNull(owner.state.value.snapshot.reportedAt)
                assertFalse(owner.state.value.reportIsStale())
                transport.emit("ok\n")
                assertTrue(command.await().accepted)
                owner.disconnect()
                var polls = 0
                while (true) {
                    val write = transport.writes.tryReceive().getOrNull() ?: break
                    assertEquals("?", write)
                    polls++
                }
                assertTrue(polls > 0)
            } finally { owner.close(); owner.awaitClosed() }
        }
    }

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
                assertEquals("\$I\n", first.nextNonStatusWrite())
                first.emit("[VER:test]\nok\n")
                assertTrue(line.await().accepted)
                owner.sendRealtime(RealtimeCommand.FEED_HOLD)
                assertEquals("!", first.nextNonStatusWrite())
                assertEquals("Idle", owner.queryStatus().executionState)
                first.writes.receive()
                owner.history.first { events -> events.last().event.direction.name == "RX" && events.size == 5 }
                val pending = async { runCatching { owner.executeLine("G4 P0") } }
                assertEquals("G4 P0\n", first.nextNonStatusWrite())
                owner.connect(serial)
                assertContains(pending.await().exceptionOrNull()?.message.orEmpty(), "outcome unknown")
                assertEquals(1, first.closeCount)
                assertEquals(2, transports.size)
                assertEquals("?", transports.last().writes.receive())
                assertTrue(transports.last().drainWrites().all { it == "?" }) // no replay
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
                    assertTrue(transports.last().drainWrites().all { it == "?" })
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

    @Test fun `cancelled switch reconfigure and disconnect finish disposal and permit another connection`() = runBlocking<Unit> {
        withTimeout(10_000) {
            for (action in listOf("connect", "reconfigure", "disconnect")) {
                val disposing = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val transports = mutableListOf<ConnectionTestTransport>()
                val owner = ControllerConnection(this, { _, child ->
                    val transport = ConnectionTestTransport().also { transports += it }
                    val session = GrblMachineSession(transport, child)
                    if (transports.size != 1) session else object : MachineSession by session {
                        override suspend fun awaitClosed() {
                            disposing.complete(Unit)
                            release.await()
                            session.awaitClosed()
                        }
                    }
                })
                try {
                    owner.connect(tcp)
                    val switching = launch {
                        when (action) {
                            "connect" -> owner.connect(serial)
                            "reconfigure" -> owner.reconfigure(serial)
                            else -> owner.disconnect()
                        }
                    }
                    disposing.await()
                    assertTrue(owner.state.value.closing)
                    switching.cancel()
                    release.complete(Unit)
                    switching.join()
                    assertEquals(1, transports.size)
                    assertEquals(1, transports.single().closeCount)
                    assertFalse(owner.state.value.closing, action)
                    assertEquals(Connection.DISCONNECTED, owner.state.value.snapshot.connection, action)
                    assertEquals(tcp, owner.state.value.endpoint)
                    assertFailsWith<IOException> { owner.queryStatus() }
                    owner.connect(serial)
                    assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
                    assertEquals(2, transports.size)
                } finally { release.complete(Unit); owner.close(); owner.awaitClosed() }
            }
        }
    }

    @Test fun `shutdown during disposal cannot create a replacement or restore stale connected health`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val disposing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val transport = ConnectionTestTransport()
            var created = 0
            val owner = ControllerConnection(this, { _, child ->
                created++
                val session = GrblMachineSession(transport, child)
                object : MachineSession by session {
                    override suspend fun awaitClosed() {
                        disposing.complete(Unit)
                        release.await()
                        session.awaitClosed()
                    }
                }
            })
            try {
                owner.connect(tcp)
                val switching = async { runCatching { owner.connect(serial) } }
                disposing.await()
                owner.close()
                release.complete(Unit)
                assertTrue(switching.await().isFailure)
                owner.awaitClosed()
                assertEquals(1, created)
                assertEquals(1, transport.closeCount)
                assertFalse(owner.state.value.closing)
                assertEquals(Connection.CLOSED, owner.state.value.snapshot.connection)
                assertFailsWith<IllegalStateException> { owner.connect(serial) }
            } finally { release.complete(Unit); owner.close(); owner.awaitClosed() }
        }
    }

    @Test fun `shutdown interrupts a pending handshake and closes once`() = runBlocking<Unit> {
        withTimeout(5_000) {
            for (cancelParent in listOf(false, true)) {
                val parent = SupervisorJob()
                val firstStatusWrite = CompletableDeferred<Unit>()
                val transport = ConnectionTestTransport().apply {
                    autoStatus = false
                    // Hold a known in-flight handshake write instead of racing its admission.
                    onWrite = { firstStatusWrite.complete(Unit); awaitCancellation() }
                }
                val owner = ControllerConnection(CoroutineScope(parent + Dispatchers.Default), { _, child ->
                    GrblMachineSession(transport, child)
                })
                try {
                    val connecting = async { runCatching { owner.connect(tcp) } }
                    owner.state.first { it.snapshot.connection == Connection.CONNECTING }
                    firstStatusWrite.await()
                    if (cancelParent) parent.cancel() else owner.close()
                    owner.awaitClosed()
                    assertTrue(connecting.await().isFailure)
                    assertEquals(1, transport.closeCount)
                    assertFalse(owner.state.value.closing)
                    assertEquals(Connection.CLOSED, owner.state.value.snapshot.connection)
                } finally { owner.close(); owner.awaitClosed(); parent.cancel() }
            }
        }
    }

    @Test fun `stalled terminal cannot starve app history and sampled snapshots are immutable with final flush`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = ConnectionTestTransport()
            lateinit var session: MachineSession
            val owner = ControllerConnection(this, { _, child ->
                GrblMachineSession(transport, child, communicationBufferCapacity = 4).also { session = it }
            }, historyCapacity = 5)
            val release = CompletableDeferred<Unit>()
            var terminal: Job? = null
            try {
                owner.connect(tcp)
                val original = owner.history.first { it.isNotEmpty() }
                val originalValues = original.toList()
                val entered = CompletableDeferred<Unit>()
                terminal = launch(start = CoroutineStart.UNDISPATCHED) {
                    session.communication.collect { entered.complete(Unit); release.await() }
                }
                entered.await()
                // Burst larger than both buffers; no requirement for a snapshot per line.
                repeat(40) { transport.emit("[EXT:$it]\n") }
                session.state.first { it.lastMessage == "[EXT:39]" }
                owner.history.first { it.lastOrNull()?.event?.text == "[EXT:39]" }
                owner.droppedCommunicationEvents.first { it > 0 }
                assertEquals(originalValues, original)
                assertEquals(5, owner.history.value.size)
                assertTrue(owner.history.value.zipWithNext().all { (a, b) -> a.event.sequence < b.event.sequence })
                // No traffic follows this final line; disconnect must flush the unsampled tail.
                transport.emit("[EXT:final]\n")
                session.state.first { it.lastMessage == "[EXT:final]" }
                owner.disconnect()
                val final = owner.history.value
                assertEquals("[EXT:final]", final.last().event.text)
                assertEquals(final.map { it.event.sequence }.distinct().size, final.size)
                assertTrue(final.all { it.sessionId == 1L && it.endpoint == tcp })
                assertEquals(session.droppedCommunicationEvents.value, owner.droppedCommunicationEvents.value)
            } finally { terminal?.cancelAndJoin(); owner.close(); owner.awaitClosed() }
        }
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
