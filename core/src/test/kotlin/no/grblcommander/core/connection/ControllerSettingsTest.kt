package no.grblcommander.core.connection

import java.time.Instant
import java.util.UUID
import java.util.prefs.Preferences
import java.util.prefs.AbstractPreferences
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import no.grblcommander.configuration.ControllerEndpointPreferences
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.MachineSnapshot
import no.grblcommander.machine.transport.SerialConfiguration

class ControllerSettingsTest {
    private class Fixture(val scope: CoroutineScope) {
        val node = Preferences.userRoot().node("no/grblcommander/tests/${UUID.randomUUID()}")
        val preferences = ControllerEndpointPreferences(node)
        var autoStatus = true
        val transports = mutableListOf<ConnectionTestTransport>()
        val connection = ControllerConnection(scope, { _, child ->
            ConnectionTestTransport().apply { autoStatus = this@Fixture.autoStatus }
                .also { transports += it }.let { GrblMachineSession(it, child, handshakeTimeoutMillis = 250) }
        })
        val settings = ControllerSettings(connection, preferences)
        val draft = ControllerEndpointDraft(ControllerEndpointDraft.Transport.TCP, "localhost", "4321")
    }

    private fun fixture(test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        supervisorScope {
            withTimeout(10_000) {
                val fixture = Fixture(this)
                try { fixture.test() } finally {
                    fixture.connection.close()
                    fixture.connection.awaitClosed()
                    fixture.node.removeNode()
                    Preferences.userRoot().flush()
                }
            }
        }
    }

    @Test fun `load and save never create a session or alter an active connection`() = fixture {
        assertEquals(ControllerEndpointDraft(), settings.load())
        settings.save(draft)
        assertEquals(draft, settings.load())
        assertTrue(transports.isEmpty())
        settings.connect(draft)
        val first = transports.single()
        val replacement = draft.copy(tcpPort = "1234")
        settings.save(replacement)
        assertEquals(replacement, settings.load())
        assertEquals(draft.endpoint(), connection.state.value.endpoint)
        assertFalse(first.closed)
        assertEquals(1, transports.size)
        assertEquals("?", first.writes.receive())
        assertTrue(first.drainWrites().all { it == "?" })
    }

    @Test fun `validation and missing confirmation leave current connection intact`() = fixture {
        assertFailsWith<IllegalArgumentException> { settings.connect(draft.copy(tcpPort = "0")) }
        assertTrue(transports.isEmpty())
        settings.connect(draft)
        assertTrue(settings.requiresInterruptionConfirmation)
        assertFailsWith<IllegalStateException> { settings.connect(draft.copy(tcpPort = "1234")) }
        assertFailsWith<IllegalStateException> { settings.disconnect() }
        assertEquals(1, transports.size)
        assertFalse(transports.single().closed)
        settings.connect(draft.copy(tcpPort = "1234"), interruptionConfirmed = true)
        assertTrue(transports.first().closed)
        assertEquals(2, transports.size)
        assertTrue(transports.all { it.writes.receive() == "?" && it.drainWrites().all { write -> write == "?" } })
        settings.disconnect(interruptionConfirmed = true)
        assertEquals(MachineSnapshot.Connection.DISCONNECTED, connection.state.value.snapshot.connection)
        assertTrue(transports.all { it.closed })
    }

    @Test fun `connecting is not connected and cancellation does not save attempted settings`() = fixture {
        settings.save(draft)
        autoStatus = false
        val attempted = draft.copy(tcpPort = "1234")
        val attempt = scope.async { settings.connect(attempted) }
        connection.state.first { it.snapshot.connection == MachineSnapshot.Connection.CONNECTING }
        assertTrue(settings.requiresInterruptionConfirmation)
        assertEquals(draft, settings.load())
        attempt.cancelAndJoin()
        assertTrue(transports.single().closed)
        assertNotEquals(MachineSnapshot.Connection.CONNECTED, connection.state.value.snapshot.connection)
        assertEquals(draft, settings.load())
        settings.disconnect(interruptionConfirmed = true)
        autoStatus = true
        settings.connect(attempted)
        assertEquals(MachineSnapshot.Connection.CONNECTED, connection.state.value.snapshot.connection)
        assertEquals(attempted, settings.load())
    }

    @Test fun `disconnect can interrupt handshake and timeout remains failed without replacing saved endpoint`() = fixture {
        settings.save(draft)
        autoStatus = false
        val attempt = scope.async { runCatching { settings.connect(draft.copy(tcpPort = "1234")) } }
        connection.state.first { it.snapshot.connection == MachineSnapshot.Connection.CONNECTING }
        settings.disconnect(interruptionConfirmed = true)
        assertTrue(attempt.await().isFailure)
        assertEquals(MachineSnapshot.Connection.DISCONNECTED, connection.state.value.snapshot.connection)
        assertFailsWith<TimeoutCancellationException> { settings.connect(draft.copy(tcpPort = "1234")) }
        assertEquals(MachineSnapshot.Connection.FAILED, connection.state.value.snapshot.connection)
        assertEquals(draft, settings.load())
        assertTrue(transports.all { it.closed })
    }

    @Test fun `startup waits for UI and connects saved endpoint only once`() = fixture {
        preferences.save(ControllerEndpointRecord.encode(draft.endpoint()))
        val startup = scope.async { settings.initialize() }
        settings.configuration.first { it != null }
        assertTrue(transports.isEmpty())
        settings.onUiReady()
        startup.await()
        assertEquals(MachineSnapshot.Connection.CONNECTED, connection.state.value.snapshot.connection)
        settings.onUiReady()
        settings.initialize()
        assertEquals(1, transports.size)
    }

    @Test fun `missing and invalid startup configuration never creates a session`() = fixture {
        settings.onUiReady()
        settings.initialize()
        assertTrue(transports.isEmpty())
        node.put("endpoint", "version=1\ntransport=TCP\nhost=localhost\nport=0")
        ControllerSettings(connection, preferences).apply { onUiReady(); initialize() }
        assertTrue(transports.isEmpty())
    }

    @Test fun `startup failure retains details without retry or additional commands`() = fixture {
        preferences.save(ControllerEndpointRecord.encode(draft.endpoint()))
        autoStatus = false
        settings.onUiReady()
        runCatching { settings.initialize() }
        assertEquals(MachineSnapshot.Connection.FAILED, connection.state.value.snapshot.connection)
        assertNotNull(connection.state.value.snapshot.failure)
        settings.initialize()
        assertEquals(1, transports.size)
        assertTrue(transports.single().drainWrites().all { it == "?" })
    }

    @Test fun `manual operation before UI readiness suppresses startup`() = fixture {
        preferences.save(ControllerEndpointRecord.encode(draft.endpoint()))
        val startup = scope.async { settings.initialize() }
        settings.configuration.first { it != null }
        settings.connect(draft.copy(tcpPort = "5555"))
        settings.onUiReady()
        startup.join()
        settings.initialize()
        assertEquals(1, transports.size)
        assertEquals(5555, (connection.state.value.endpoint as ControllerEndpoint.Tcp).port)
    }

    @Test fun `manual disconnect cancels automatic handshake without competing connection`() = fixture {
        preferences.save(ControllerEndpointRecord.encode(draft.endpoint()))
        autoStatus = false
        settings.onUiReady()
        val startup = scope.async { settings.initialize() }
        connection.state.first { it.snapshot.connection == MachineSnapshot.Connection.CONNECTING }
        settings.disconnect(interruptionConfirmed = true)
        startup.join()
        settings.initialize()
        assertEquals(1, transports.size)
        assertTrue(transports.single().closed)
        assertEquals(MachineSnapshot.Connection.DISCONNECTED, connection.state.value.snapshot.connection)
    }

    @Test fun `direct manual lifecycle intent prevents delayed automatic connection`() = fixture {
        preferences.save(ControllerEndpointRecord.encode(draft.endpoint()))
        val startup = scope.async { settings.initialize() }
        settings.configuration.first { it != null }
        connection.disconnect()
        settings.onUiReady()
        startup.await()
        assertTrue(transports.isEmpty())
        assertEquals(MachineSnapshot.Connection.DISCONNECTED, connection.state.value.snapshot.connection)
    }

    @Test fun `startup storage and session creation failures remain observable without escaping`() = fixture {
        val removed = node.node("removed")
        removed.removeNode()
        ControllerSettings(connection, ControllerEndpointPreferences(removed)).apply { onUiReady(); initialize() }
        assertEquals(MachineSnapshot.Connection.FAILED, connection.state.value.snapshot.connection)
        assertNotNull(connection.state.value.snapshot.failure)
        assertTrue(transports.isEmpty())

        preferences.save(ControllerEndpointRecord.encode(draft.endpoint()))
        var attempts = 0
        val failing = ControllerConnection(scope, { _, _ -> attempts++; error("Endpoint unavailable") })
        try {
            val startup = ControllerSettings(failing, preferences)
            startup.onUiReady()
            startup.initialize()
            startup.initialize()
            assertEquals(1, attempts)
            assertEquals("Endpoint unavailable", failing.state.value.snapshot.failure)
            assertEquals(draft.endpoint(), failing.state.value.endpoint)
        } finally { failing.close(); failing.awaitClosed() }
    }

    @Test fun `manual disconnect during configuration IO suppresses delayed startup`() = fixture {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val record = ControllerEndpointRecord.encode(draft.endpoint())
        val blockedPreferences = object : AbstractPreferences(null, "") {
            override fun getSpi(key: String): String? {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return record
            }
            override fun putSpi(key: String, value: String) = Unit
            override fun removeSpi(key: String) = Unit
            override fun removeNodeSpi() = Unit
            override fun keysSpi() = arrayOf("endpoint")
            override fun childrenNamesSpi() = emptyArray<String>()
            override fun childSpi(name: String): AbstractPreferences = error("No children")
            override fun syncSpi() = Unit
            override fun flushSpi() = Unit
        }
        val startupSettings = ControllerSettings(connection, ControllerEndpointPreferences(blockedPreferences))
        startupSettings.onUiReady()
        val startup = scope.async { startupSettings.initialize() }
        try {
            withContext(Dispatchers.IO) { check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            val manual = scope.async(start = CoroutineStart.UNDISPATCHED) { startupSettings.disconnect() }
            release.countDown()
            manual.await()
            startup.join()
            assertTrue(transports.isEmpty())
            assertEquals(MachineSnapshot.Connection.DISCONNECTED, connection.state.value.snapshot.connection)
        } finally { release.countDown(); startup.cancelAndJoin() }
    }

    @Test fun `direct manual connect replaces automatic handshake and cannot be overwritten by its cleanup`() = fixture {
        preferences.save(ControllerEndpointRecord.encode(draft.endpoint()))
        autoStatus = false
        settings.onUiReady()
        val startup = scope.async { settings.initialize() }
        connection.state.first { it.snapshot.connection == MachineSnapshot.Connection.CONNECTING }
        autoStatus = true
        val manualEndpoint = draft.copy(tcpPort = "5555").endpoint()
        connection.connect(manualEndpoint)
        startup.join()
        assertEquals(2, transports.size)
        assertTrue(transports.first().closed)
        assertFalse(transports.last().closed)
        assertEquals(manualEndpoint, connection.state.value.endpoint)
        assertEquals(MachineSnapshot.Connection.CONNECTED, connection.state.value.snapshot.connection)
    }

    @Test fun `freshness expires without a new state emission and disconnect invalidates retained report`() {
        val now = Instant.parse("2026-11-15T10:00:00Z")
        val state = ControllerConnectionState(snapshot = MachineSnapshot(
            connection = MachineSnapshot.Connection.CONNECTED, reportedAt = now,
        ))
        assertFalse(state.reportIsStale(now.plusSeconds(2)))
        assertTrue(state.reportIsStale(now.plusSeconds(4)))
        assertTrue(state.copy(snapshot = state.snapshot.copy(connection = MachineSnapshot.Connection.DISCONNECTED)).reportIsStale(now))
        assertTrue(ControllerConnectionState().reportIsStale(now))
    }

    @Test fun `legacy preference record loads into core configuration and survives an application restart`() = fixture {
        node.put("endpoint", "version=1\ntransport=SERIAL\ndevice=COM8\nbaud=230400\nbits=7\nstop=TWO\nparity=EVEN\nflow=RTS_CTS\ndtr=true\nrts=false")
        val legacy = ControllerEndpoint.Serial(SerialConfiguration(
            "COM8", 230400, 7, SerialConfiguration.StopBits.TWO, SerialConfiguration.Parity.EVEN,
            SerialConfiguration.FlowControl.RTS_CTS, dtr = true, rts = false,
        ))
        assertEquals(legacy, settings.load().endpoint())
        assertEquals(legacy, settings.configuration.value)
        settings.save(draft)
        assertEquals(draft.endpoint(), settings.configuration.value)
        val restartedConnection = ControllerConnection(scope, { _, _ -> error("Loading settings must not connect") })
        try {
            val restarted = ControllerSettings(restartedConnection, ControllerEndpointPreferences(
                Preferences.userRoot().node(node.absolutePath()),
            ))
            assertNull(restarted.configuration.value)
            assertEquals(draft, restarted.load())
            assertEquals(draft.endpoint(), restarted.configuration.value)
            assertNull(restartedConnection.state.value.endpoint)
        } finally { restartedConnection.close(); restartedConnection.awaitClosed() }
        assertTrue(transports.isEmpty())
    }

    @Test fun `invalid loaded data clears configured endpoint and invalid edits leave saved configuration intact`() = fixture {
        settings.save(draft)
        val record = node.get("endpoint", null)
        assertFailsWith<IllegalArgumentException> { settings.save(draft.copy(tcpPort = "0")) }
        assertEquals(draft.endpoint(), settings.configuration.value)
        assertEquals(record, node.get("endpoint", null))
        node.put("endpoint", "version=1\ntransport=TCP\nhost=localhost\nport=0")
        assertEquals(ControllerEndpointDraft(), settings.load())
        assertNull(settings.configuration.value)
        assertTrue(transports.isEmpty())
    }
}
