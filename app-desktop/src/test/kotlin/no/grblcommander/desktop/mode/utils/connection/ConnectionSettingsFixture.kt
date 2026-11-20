package no.grblcommander.desktop.mode.utils.connection

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import no.grblcommander.configuration.ControllerEndpointPreferences
import no.grblcommander.core.connection.ConnectionTestTransport
import no.grblcommander.core.connection.ControllerConnection
import no.grblcommander.core.connection.ControllerEndpointDraft
import no.grblcommander.core.connection.ControllerSettings
import no.grblcommander.core.connection.description
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost
import no.grblcommander.desktop.ui.navigation.SoftKeySlot
import no.grblcommander.desktop.ui.navigation.activateSoftKey
import no.grblcommander.desktop.ui.rememberCurrentTime
import no.grblcommander.desktop.ui.screens.Terminal
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.MachineSession
import no.grblcommander.machine.session.MachineSnapshot.Connection

/** A real preferences write paused at flush, without timing assumptions or physical devices. */
internal class ConnectionTestPreferences : AbstractPreferences(null, "") {
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

internal class ConnectionSettingsFixture : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val preferences = ConnectionTestPreferences()
    var autoStatus = true
    var commandTimeoutMillis = 5_000L
    val transports = java.util.concurrent.CopyOnWriteArrayList<ConnectionTestTransport>()
    val awaitEntered = AtomicInteger()
    var releaseClose: CompletableDeferred<Unit>? = null
    val owner = ControllerConnection(scope, { _, child ->
        val transport = ConnectionTestTransport().apply { autoStatus = this@ConnectionSettingsFixture.autoStatus }
        transports += transport
        val session = GrblMachineSession(transport, child, handshakeTimeoutMillis = 30_000, commandTimeoutMillis = commandTimeoutMillis)
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
    val draft = ControllerEndpointDraft(ControllerEndpointDraft.Transport.TCP, "localhost", "4321", postConnect = no.grblcommander.core.connection.noPostConnectActions())
    override fun close() {
        preferences.releaseFlush.countDown()
        releaseClose?.complete(Unit)
        owner.close()
        runBlocking { withTimeout(5_000) { owner.awaitClosed() } }
        scope.cancel()
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.openConnectionSettings(fixture: ConnectionSettingsFixture): ControllerConnectionSettings {
    setContent {
        MaterialTheme(colorScheme = darkColorScheme()) {
            val state by fixture.owner.state.collectAsState()
            val now = rememberCurrentTime()
            val status = (if (state.closing) "Closing" else state.snapshot.connection.name) +
                (if (!state.closing && state.snapshot.connection == Connection.CONNECTED && state.reportIsStale(now)) " · stale" else "") +
                (state.snapshot.alarmCode?.let { " · ALARM:$it" } ?: state.snapshot.status?.executionState?.let { " · $it" }.orEmpty())
            AppShell(ShellState("Connection settings", state.endpoint?.description() ?: "No controller endpoint selected", status),
                { fixture.stack }) { ScreenStackHost(fixture.stack) }
        }
    }
    runOnIdle { fixture.root.openConnection() }
    waitUntil(timeoutMillis = 5_000) {
        fixture.stack.softKeyDefinition(BottomSoftKeySlot.F7)?.enabled() == true
    }
    return (fixture.stack.top as ControllerConnectionSettings).also { screen ->
        runOnIdle { screen.draft = fixture.draft }
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.activate(fixture: ConnectionSettingsFixture, slot: SoftKeySlot): Boolean {
    var activated = false
    runOnIdle { activated = activateSoftKey({ fixture.stack }, slot) }
    return activated
}
