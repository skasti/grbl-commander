package no.grblcommander.core.connection

import kotlinx.coroutines.*
import no.grblcommander.configuration.ControllerEndpointPreferences
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.MachineSnapshot.Connection
import no.grblcommander.machine.session.RealtimeCommand
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class PostConnectActionsTest {
    private fun scenario(test: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10_000) { supervisorScope { test() } }
    }
    @Test fun orderedQueriesAndUnsupportedExtension() = scenario {
        val transport = ConnectionTestTransport().apply {
            onWrite = { line -> if (line.endsWith('\n')) emit(if (line == "\$I+\n") "error:3\r\n" else "[MSG:metadata]\r\nok\r\n") }
        }
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope, commandTimeoutMillis = 500) })
        try {
            owner.connect(ControllerEndpoint.Tcp("localhost", 1234, PostConnectActions(firmwareInfo = PostConnectActions.FirmwareInfo.EXTENDED)))
            assertEquals(listOf("\$I+\n", "$#\n", "\$G\n"), transport.drainWrites().filter { it.endsWith('\n') })
            assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
            assertTrue(owner.state.value.postConnectResults.first().contains("error:3"))
            assertTrue(owner.state.value.snapshot.normalCommandsSynchronized)
            assertFalse(owner.state.value.postConnectPending)
            assertTrue(owner.diagnosticSnapshot().any { it.event.text.contains("error:3") })
        } finally { owner.close(); owner.awaitClosed() }
    }
    @Test fun timeoutPreservesConnectionAndStatus() = scenario {
        val transport = ConnectionTestTransport()
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope, commandTimeoutMillis = 60) })
        try {
            owner.connect(ControllerEndpoint.Tcp("localhost", 1234))
            assertEquals(listOf("\$I\n"), transport.drainWrites().filter { it.endsWith('\n') })
            assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
            assertFalse(owner.state.value.snapshot.normalCommandsSynchronized)
            assertTrue(owner.state.value.postConnectResults.any { it.contains("skipped") })
            owner.sendRealtime(RealtimeCommand.STATUS_QUERY)
            assertEquals("?", transport.writes.receive())
            assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
        } finally { owner.close(); owner.awaitClosed() }
    }
    @Test fun manualResetRequiresConsentAndPrecedesQueries() = scenario {
        val transport = ConnectionTestTransport().apply { onWrite = { if (it.endsWith('\n')) emit("ok\r\n") } }
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope) })
        val endpoint = ControllerEndpoint.Tcp("localhost", 1234, PostConnectActions(resetController = true))
        try {
            assertFailsWith<IllegalStateException> { owner.connect(endpoint) }
            assertEquals(0, transport.connectCount)
            owner.connect(endpoint, resetConfirmed = true)
            val writes = transport.drainWrites()
            assertEquals(1, writes.count { it == "\u0018" })
            assertTrue(writes.indexOf("\u0018") < writes.indexOf("\$I\n"))
            assertTrue(owner.state.value.snapshot.normalCommandsSynchronized)
        } finally { owner.close(); owner.awaitClosed() }
    }
    @Test fun startupSkipsResetAndRunsQueries() = scenario {
        val transport = ConnectionTestTransport().apply { onWrite = { if (it.endsWith('\n')) emit("ok\r\n") } }
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope) })
        val node = Preferences.userRoot().node("no/grblcommander/tests/" + UUID.randomUUID())
        try {
            ControllerEndpointPreferences(node).save(ControllerEndpointRecord.encode(ControllerEndpoint.Tcp("localhost", 1234, PostConnectActions(resetController = true))))
            val settings = ControllerSettings(owner, ControllerEndpointPreferences(node))
            settings.onUiReady()
            settings.initialize()
            val writes = transport.drainWrites()
            assertFalse(writes.contains("\u0018"))
            assertEquals(listOf("\$I\n", "$#\n", "\$G\n"), writes.filter { it.endsWith('\n') })
            assertTrue(owner.state.value.postConnectResults.first().contains("skipped"))
        } finally { owner.close(); owner.awaitClosed(); node.removeNode() }
    }
    @Test fun criticalAlarmSkipsQueriesAndRetainsRealtimeControl() = scenario {
        val transport = ConnectionTestTransport().apply {
            onWrite = { if (it == "\$I\n") emit("ALARM:1\r\n") }
        }
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope) })
        try {
            owner.connect(ControllerEndpoint.Tcp("localhost", 1234))
            assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
            assertTrue(owner.state.value.snapshot.resetRequired)
            assertEquals(listOf("\$I\n"), transport.drainWrites().filter { it.endsWith('\n') })
            assertTrue(owner.state.value.postConnectResults.any { it.contains("skipped") })
            owner.sendRealtime(RealtimeCommand.STATUS_QUERY)
            assertEquals("?", transport.writes.receive())
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun failedResetIsVisibleAndNeverRetriesOrRunsQueries() = scenario {
        val transport = ConnectionTestTransport().apply { autoReset = false }
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope, handshakeTimeoutMillis = 500) })
        try {
            owner.connect(ControllerEndpoint.Tcp("localhost", 1234, PostConnectActions(resetController = true)), resetConfirmed = true)
            val writes = transport.drainWrites()
            assertEquals(1, writes.count { it == "\u0018" })
            assertTrue(writes.none { it.endsWith('\n') })
            assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
            assertTrue(owner.state.value.postConnectResults.single().contains("reset failed"))
            owner.sendRealtime(RealtimeCommand.STATUS_QUERY)
            assertEquals("?", transport.writes.receive())
        } finally { owner.close(); owner.awaitClosed() }
    }
}
