package no.grblcommander.desktop.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.AbstractPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.grblcommander.core.connection.ConnectionTestTransport
import no.grblcommander.core.connection.ControllerConnection
import no.grblcommander.core.connection.ControllerEndpointDraft
import no.grblcommander.configuration.ControllerEndpointPreferences
import no.grblcommander.core.connection.ControllerSettings
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.RightSoftKeySlot
import no.grblcommander.desktop.ui.navigation.SoftKeySlot
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost
import no.grblcommander.desktop.ui.navigation.activateSoftKey
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.MachineSession
import no.grblcommander.machine.session.MachineSnapshot.Connection
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ControllerConnectionSettingsTest {
    /** A real preferences write paused at flush, without timing assumptions or physical devices. */
    private class Preferences : AbstractPreferences(null, "") {
        private val values = mutableMapOf<String, String>()
        @Volatile var blockFlush = false
        val flushEntered = CountDownLatch(1)
        val releaseFlush = CountDownLatch(1)
        val flushes = AtomicInteger()
        override fun putSpi(key: String, value: String) { values[key] = value }
        override fun getSpi(key: String): String? = values[key]
        override fun removeSpi(key: String) { values.remove(key) }
        override fun keysSpi(): Array<String> = values.keys.toTypedArray()
        override fun childrenNamesSpi(): Array<String> = emptyArray()
        override fun childSpi(name: String): AbstractPreferences = error("No child nodes")
        override fun removeNodeSpi() = Unit
        override fun syncSpi() = Unit
        override fun flushSpi() {
            flushes.incrementAndGet()
            if (blockFlush) {
                flushEntered.countDown()
                check(releaseFlush.await(10, TimeUnit.SECONDS)) { "Test did not release preferences flush" }
            }
        }
    }

    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preferences = Preferences()
        var autoStatus = true
        val transports = java.util.concurrent.CopyOnWriteArrayList<ConnectionTestTransport>()
        val awaitEntered = AtomicInteger()
        var releaseClose: CompletableDeferred<Unit>? = null
        val owner = ControllerConnection(scope, { _, child ->
            val transport = ConnectionTestTransport().apply { autoStatus = this@Fixture.autoStatus }
            transports += transport
            val session = GrblMachineSession(transport, child, handshakeTimeoutMillis = 30_000)
            object : MachineSession by session {
                override suspend fun awaitClosed() {
                    awaitEntered.incrementAndGet()
                    releaseClose?.await()
                    session.awaitClosed()
                }
            }
        })
        val settings = ControllerSettings(owner, ControllerEndpointPreferences(preferences))
        val root = Terminal(settings)
        val stack = ScreenStack(root)
        val draft = ControllerEndpointDraft(ControllerEndpointDraft.Transport.TCP, "localhost", "4321")
        override fun close() {
            preferences.releaseFlush.countDown()
            releaseClose?.complete(Unit)
            owner.close()
            runBlocking { withTimeout(5_000) { owner.awaitClosed() } }
            scope.cancel()
        }
    }

    private fun ComposeUiTest.open(fixture: Fixture): ControllerConnectionSettings {
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), { fixture.stack }) { ScreenStackHost(fixture.stack) }
            }
        }
        runOnIdle { fixture.root.openConnection() }
        waitUntil(timeoutMillis = 5_000) {
            fixture.stack.softKeyDefinition(BottomSoftKeySlot.F1)?.enabled() == true
        }
        return (fixture.stack.top as ControllerConnectionSettings).also { screen ->
            runOnIdle { screen.draft = fixture.draft }
        }
    }

    private fun ComposeUiTest.activate(fixture: Fixture, slot: SoftKeySlot): Boolean {
        var activated = false
        runOnIdle { activated = activateSoftKey({ fixture.stack }, slot) }
        return activated
    }

    @Test fun `intentional disconnect is neutral while an unexpected connection loss stays visible`() = runComposeUiTest {
        Fixture().use { fixture ->
            runBlocking { fixture.owner.connect(fixture.draft.endpoint()) }
            open(fixture)
            fixture.releaseClose = CompletableDeferred()
            assertTrue(activate(fixture, BottomSoftKeySlot.F3))
            onNodeWithTag("softkey-F1").performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.awaitEntered.get() == 1 }
            onNodeWithText("Disconnecting…").assertExists()
            onNodeWithText("Last connection error:", substring = true).assertDoesNotExist()
            onNodeWithText("Machine state is stale or unavailable.", substring = true).assertDoesNotExist()
            fixture.releaseClose!!.complete(Unit)
            waitUntil(timeoutMillis = 5_000) { fixture.stack.softKeyDefinition(BottomSoftKeySlot.F2)!!.enabled() }
            onNodeWithText("Disconnected. Machine state is not being updated.").assertExists()
            onNodeWithText("Last connection error:", substring = true).assertDoesNotExist()
            onNodeWithText("Last machine state: Idle").assertExists()
            assertTrue(activate(fixture, BottomSoftKeySlot.F2))
            waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.snapshot.connection == Connection.CONNECTED }
            fixture.transports.last().disconnect()
            waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.snapshot.connection == Connection.FAILED }
            onNodeWithText("Last connection error:", substring = true).assertExists()
            onNodeWithText("Machine state is stale or unavailable.", substring = true).assertExists()
        }
    }

    @Test fun `Disconnect confirms before cancelling an unfinished Connect and allows explicit reconnect`() = runComposeUiTest {
        Fixture().use { fixture ->
            fixture.autoStatus = false
            open(fixture)
            runOnIdle {
                assertTrue(activateSoftKey({ fixture.stack }, BottomSoftKeySlot.F2))
                // Cancellation also needs confirmation before the queued Connect publishes CONNECTING.
                assertTrue(activateSoftKey({ fixture.stack }, BottomSoftKeySlot.F3))
            }
            waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.snapshot.connection == Connection.CONNECTING }
            onNodeWithText("Interrupt current connection?").assertExists()
            onNodeWithTag("softkey-F8").performClick()
            runOnIdle {
                assertEquals(Connection.CONNECTING, fixture.owner.state.value.snapshot.connection)
                assertFalse(fixture.transports.single().closed)
            }
            assertTrue(activate(fixture, BottomSoftKeySlot.F3))
            onNodeWithTag("softkey-F1").performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.snapshot.connection == Connection.DISCONNECTED }
            waitUntil(timeoutMillis = 5_000) { fixture.stack.softKeyDefinition(BottomSoftKeySlot.F2)!!.enabled() }
            runOnIdle { assertEquals(1, fixture.transports.single().closeCount) }
            fixture.autoStatus = true
            assertTrue(activate(fixture, BottomSoftKeySlot.F2))
            waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.snapshot.connection == Connection.CONNECTED }
            runOnIdle { assertEquals(2, fixture.transports.size) }
        }
    }

    @Test fun `Disconnect is disabled during Save and cannot cancel it through repeated callbacks`() = runComposeUiTest {
        Fixture().use { fixture ->
            runBlocking { fixture.owner.connect(fixture.draft.endpoint()) }
            open(fixture)
            fixture.preferences.blockFlush = true
            assertTrue(activate(fixture, BottomSoftKeySlot.F1))
            waitUntil(timeoutMillis = 5_000) { fixture.preferences.flushEntered.count == 0L }
            assertFalse(activate(fixture, BottomSoftKeySlot.F3))
            onNode(hasText("Disconnect") and hasAnyAncestor(hasScrollAction())).performScrollTo().assertIsNotEnabled()
            runOnIdle {
                repeat(3) { fixture.stack.softKeyDefinition(BottomSoftKeySlot.F3)!!.action!!.invoke() }
                assertFalse(fixture.transports.single().closed)
                assertEquals(0, fixture.awaitEntered.get())
            }
            onNodeWithText("Interrupt current connection?").assertDoesNotExist()
            fixture.preferences.releaseFlush.countDown()
            waitUntil(timeoutMillis = 5_000) { fixture.stack.softKeyDefinition(BottomSoftKeySlot.F1)!!.enabled() }
            onNodeWithText("Settings saved. The current connection is unchanged.").assertExists()
            runOnIdle {
                assertEquals(1, fixture.preferences.flushes.get())
                assertEquals(Connection.CONNECTED, fixture.owner.state.value.snapshot.connection)
                assertFalse(fixture.transports.single().closed)
            }
        }
    }

    @Test fun `repeated Disconnect is disabled until confirmed cleanup completes`() = runComposeUiTest {
        Fixture().use { fixture ->
            runBlocking { fixture.owner.connect(fixture.draft.endpoint()) }
            open(fixture)
            fixture.releaseClose = CompletableDeferred()
            assertTrue(activate(fixture, BottomSoftKeySlot.F3))
            onNodeWithTag("softkey-F1").performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.awaitEntered.get() == 1 }
            assertFalse(activate(fixture, BottomSoftKeySlot.F3))
            onNode(hasText("Disconnect") and hasAnyAncestor(hasScrollAction())).performScrollTo().assertIsNotEnabled()
            runOnIdle {
                repeat(3) { fixture.stack.softKeyDefinition(BottomSoftKeySlot.F3)!!.action!!.invoke() }
                assertFalse(fixture.stack.softKeyDefinition(BottomSoftKeySlot.F1)!!.enabled())
                assertFalse(fixture.stack.softKeyDefinition(BottomSoftKeySlot.F2)!!.enabled())
                assertEquals(1, fixture.awaitEntered.get())
                assertTrue(fixture.owner.state.value.closing)
            }
            onNodeWithText("Interrupt current connection?").assertDoesNotExist()
            fixture.releaseClose!!.complete(Unit)
            waitUntil(timeoutMillis = 5_000) { fixture.stack.softKeyDefinition(BottomSoftKeySlot.F1)!!.enabled() }
            runOnIdle {
                assertEquals(Connection.DISCONNECTED, fixture.owner.state.value.snapshot.connection)
                assertEquals(1, fixture.awaitEntered.get())
                assertEquals(1, fixture.transports.single().closeCount)
            }
        }
    }

    @Test fun `Connect replacement and Disconnect require explicit confirmation and Cancel keeps current session`() = runComposeUiTest {
        Fixture().use { fixture ->
            runBlocking { fixture.owner.connect(fixture.draft.endpoint()) }
            val screen = open(fixture)
            runOnIdle { screen.draft = fixture.draft.copy(tcpPort = "1234") }
            assertTrue(activate(fixture, BottomSoftKeySlot.F2))
            onNodeWithText("Interrupt current connection?").assertExists()
            assertFalse(activate(fixture, BottomSoftKeySlot.F2))
            assertFalse(activate(fixture, BottomSoftKeySlot.F3))
            assertFalse(activate(fixture, RightSoftKeySlot.R7))
            runOnIdle { assertEquals(3, fixture.stack.size) }
            onNodeWithTag("softkey-F8").performClick()
            runOnIdle {
                assertSame(screen, fixture.stack.top)
                assertEquals(2, fixture.stack.size)
                assertEquals("1234", screen.draft.tcpPort)
                assertEquals(1, fixture.transports.size)
                assertFalse(fixture.transports.single().closed)
            }
            assertTrue(activate(fixture, BottomSoftKeySlot.F2))
            onNodeWithTag("softkey-F1").performClick()
            waitUntil(timeoutMillis = 5_000) {
                fixture.transports.size == 2 && fixture.stack.softKeyDefinition(BottomSoftKeySlot.F1)!!.enabled()
            }
            runOnIdle {
                assertTrue(fixture.transports.first().closed)
                assertEquals(fixture.draft.copy(tcpPort = "1234").endpoint(), fixture.owner.state.value.endpoint)
                assertTrue(fixture.transports.all { port -> port.writes.tryReceive().getOrNull() == "?" })
            }
            assertTrue(activate(fixture, BottomSoftKeySlot.F3))
            onNodeWithTag("softkey-F8").performClick()
            runOnIdle { assertFalse(fixture.transports.last().closed) }
            assertTrue(activate(fixture, BottomSoftKeySlot.F3))
            onNodeWithTag("softkey-F1").performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.snapshot.connection == Connection.DISCONNECTED }
            runOnIdle { assertTrue(fixture.transports.all { it.closed }) }
        }
    }

    @Test fun `Disconnect waits for Connect persistence after handshake without cancelling that save`() =
        saveAfterHandshake(loseConnection = false)

    @Test fun `connection loss during Connect persistence does not make that save cancellable`() =
        saveAfterHandshake(loseConnection = true)

    private fun saveAfterHandshake(loseConnection: Boolean) = runComposeUiTest {
        Fixture().use { fixture ->
            open(fixture)
            fixture.preferences.blockFlush = true
            assertTrue(activate(fixture, BottomSoftKeySlot.F2))
            waitUntil(timeoutMillis = 5_000) { fixture.preferences.flushEntered.count == 0L }
            runOnIdle { assertEquals(Connection.CONNECTED, fixture.owner.state.value.snapshot.connection) }
            if (loseConnection) {
                fixture.transports.single().disconnect()
                waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.snapshot.connection == Connection.FAILED }
            }
            assertTrue(activate(fixture, BottomSoftKeySlot.F3))
            onNodeWithTag("softkey-F1").performClick()
            assertFalse(activate(fixture, BottomSoftKeySlot.F3))
            runOnIdle { assertEquals(loseConnection, fixture.transports.single().closed) }
            fixture.preferences.releaseFlush.countDown()
            waitUntil(timeoutMillis = 5_000) { fixture.stack.softKeyDefinition(BottomSoftKeySlot.F1)!!.enabled() }
            runOnIdle {
                assertEquals(1, fixture.preferences.flushes.get())
                assertEquals(fixture.draft.endpoint(), fixture.settings.let { runBlocking { it.load().endpoint() } })
                assertEquals(Connection.DISCONNECTED, fixture.owner.state.value.snapshot.connection)
            }
        }
    }
}
