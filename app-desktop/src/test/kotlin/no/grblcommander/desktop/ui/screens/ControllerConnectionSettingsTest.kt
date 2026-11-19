package no.grblcommander.desktop.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import no.grblcommander.core.connection.PostConnectActions
import no.grblcommander.core.connection.description
import no.grblcommander.desktop.ui.rememberCurrentTime
import no.grblcommander.desktop.ui.runAtScreenshotSizes
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
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
import kotlin.math.roundToInt

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
        var commandTimeoutMillis = 5_000L
        val transports = java.util.concurrent.CopyOnWriteArrayList<ConnectionTestTransport>()
        val awaitEntered = AtomicInteger()
        var releaseClose: CompletableDeferred<Unit>? = null
        val owner = ControllerConnection(scope, { _, child ->
            val transport = ConnectionTestTransport().apply { autoStatus = this@Fixture.autoStatus }
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

    private fun ComposeUiTest.open(fixture: Fixture): ControllerConnectionSettings {
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

    private fun ComposeUiTest.activate(fixture: Fixture, slot: SoftKeySlot): Boolean {
        var activated = false
        runOnIdle { activated = activateSoftKey({ fixture.stack }, slot) }
        return activated
    }

    @Test fun `intentional disconnect is neutral while an unexpected connection loss stays visible`() = runAtScreenshotSizes { screenshot ->
        Fixture().use { fixture ->
            runBlocking { fixture.owner.connect(fixture.draft.endpoint()) }
            open(fixture)
            onNodeWithText("Last machine state: Idle").assertExists()
            screenshot("connection-active")
            fixture.releaseClose = CompletableDeferred()
            assertTrue(activate(fixture, BottomSoftKeySlot.F6))
            onNodeWithText("Interrupt current connection?").assertExists()
            screenshot("connection-interruption-confirmation")
            onNodeWithTag("softkey-F1").performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.awaitEntered.get() == 1 }
            onNodeWithText("Disconnecting…").assertExists()
            screenshot("connection-disconnecting")
            onNodeWithText("Last connection error:", substring = true).assertDoesNotExist()
            onNodeWithText("Machine state is stale or unavailable.", substring = true).assertDoesNotExist()
            fixture.releaseClose!!.complete(Unit)
            waitUntil(timeoutMillis = 5_000) { fixture.stack.softKeyDefinition(BottomSoftKeySlot.F6)!!.enabled() }
            onNodeWithText("Disconnected. Machine state is not being updated.").assertExists()
            screenshot("connection-disconnected-observation")
            onNodeWithText("Last connection error:", substring = true).assertDoesNotExist()
            onNodeWithText("Last machine state: Idle").assertExists()
            assertTrue(activate(fixture, BottomSoftKeySlot.F6))
            waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.snapshot.connection == Connection.CONNECTED }
            fixture.transports.last().disconnect()
            waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.snapshot.connection == Connection.FAILED }
            onNodeWithText("Last connection error:", substring = true).assertExists()
            onNodeWithText("Machine state is stale or unavailable.", substring = true).assertExists()
            screenshot("connection-failed")
        }
    }

    @Test fun `Disconnect confirms before cancelling an unfinished Connect and allows explicit reconnect`() = runAtScreenshotSizes { screenshot ->
        Fixture().use { fixture ->
            fixture.autoStatus = false
            open(fixture)
            runOnIdle {
                assertTrue(activateSoftKey({ fixture.stack }, BottomSoftKeySlot.F6))
                // Cancellation also needs confirmation before the queued Connect publishes CONNECTING.
                assertTrue(activateSoftKey({ fixture.stack }, BottomSoftKeySlot.F6))
            }
            waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.snapshot.connection == Connection.CONNECTING }
            onNodeWithText("Interrupt current connection?").assertExists()
            onNodeWithTag("softkey-F8").performClick()
            onNodeWithText("Waiting for controller status…").assertExists()
            screenshot("connection-connecting")
            runOnIdle {
                assertEquals(Connection.CONNECTING, fixture.owner.state.value.snapshot.connection)
                assertFalse(fixture.transports.single().closed)
            }
            assertTrue(activate(fixture, BottomSoftKeySlot.F6))
            onNodeWithTag("softkey-F1").performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.snapshot.connection == Connection.DISCONNECTED }
            waitUntil(timeoutMillis = 5_000) { fixture.stack.softKeyDefinition(BottomSoftKeySlot.F6)!!.enabled() }
            runOnIdle { assertEquals(1, fixture.transports.single().closeCount) }
            fixture.autoStatus = true
            assertTrue(activate(fixture, BottomSoftKeySlot.F6))
            waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.snapshot.connection == Connection.CONNECTED }
            runOnIdle { assertEquals(2, fixture.transports.size) }
        }
    }

    @Test fun `Disconnect is disabled during Save and cannot cancel it through repeated callbacks`() = runAtScreenshotSizes { screenshot ->
        Fixture().use { fixture ->
            runBlocking { fixture.owner.connect(fixture.draft.endpoint()) }
            open(fixture)
            fixture.preferences.blockFlush = true
            assertTrue(activate(fixture, BottomSoftKeySlot.F7))
            waitUntil(timeoutMillis = 5_000) { fixture.preferences.flushEntered.count == 0L }
            assertFalse(activate(fixture, BottomSoftKeySlot.F6))
            onNodeWithTag("softkey-F6").assertIsNotEnabled()
            screenshot("connection-saving")
            runOnIdle {
                repeat(3) { fixture.stack.softKeyDefinition(BottomSoftKeySlot.F6)!!.action!!.invoke() }
                assertFalse(fixture.transports.single().closed)
                assertEquals(0, fixture.awaitEntered.get())
            }
            onNodeWithText("Interrupt current connection?").assertDoesNotExist()
            fixture.preferences.releaseFlush.countDown()
            waitUntil(timeoutMillis = 5_000) { fixture.stack.softKeyDefinition(BottomSoftKeySlot.F7)!!.enabled() }
            onNodeWithText("Settings saved. The current connection is unchanged.").performScrollTo().assertIsDisplayed()
            screenshot("connection-saved")
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
            assertTrue(activate(fixture, BottomSoftKeySlot.F6))
            onNodeWithTag("softkey-F1").performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.awaitEntered.get() == 1 }
            assertFalse(activate(fixture, BottomSoftKeySlot.F6))
            onNodeWithTag("softkey-F6").assertIsNotEnabled()
            runOnIdle {
                repeat(3) { fixture.stack.softKeyDefinition(BottomSoftKeySlot.F6)!!.action!!.invoke() }
                assertFalse(fixture.stack.softKeyDefinition(BottomSoftKeySlot.F7)!!.enabled())
                assertFalse(fixture.stack.softKeyDefinition(BottomSoftKeySlot.F6)!!.enabled())
                assertEquals(1, fixture.awaitEntered.get())
                assertTrue(fixture.owner.state.value.closing)
            }
            onNodeWithText("Interrupt current connection?").assertDoesNotExist()
            fixture.releaseClose!!.complete(Unit)
            waitUntil(timeoutMillis = 5_000) { fixture.stack.softKeyDefinition(BottomSoftKeySlot.F7)!!.enabled() }
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
            assertTrue(activate(fixture, BottomSoftKeySlot.F6))
            onNodeWithText("Interrupt current connection?").assertExists()
            assertFalse(activate(fixture, BottomSoftKeySlot.F6))
            assertFalse(activate(fixture, BottomSoftKeySlot.F6))
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
            assertTrue(activate(fixture, BottomSoftKeySlot.F6))
            onNodeWithTag("softkey-F1").performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.snapshot.connection == Connection.DISCONNECTED }
            waitUntil(timeoutMillis = 5_000) { fixture.stack.softKeyDefinition(BottomSoftKeySlot.F7)!!.enabled() }
            assertTrue(activate(fixture, BottomSoftKeySlot.F6))
            waitUntil(timeoutMillis = 5_000) {
                fixture.transports.size == 2 && fixture.stack.softKeyDefinition(BottomSoftKeySlot.F7)!!.enabled()
            }
            runOnIdle {
                assertTrue(fixture.transports.first().closed)
                assertEquals(fixture.draft.copy(tcpPort = "1234").endpoint(), fixture.owner.state.value.endpoint)
                assertTrue(fixture.transports.all { port -> port.writes.tryReceive().getOrNull() == "?" })
            }
            assertTrue(activate(fixture, BottomSoftKeySlot.F6))
            onNodeWithTag("softkey-F8").performClick()
            runOnIdle { assertFalse(fixture.transports.last().closed) }
            assertTrue(activate(fixture, BottomSoftKeySlot.F6))
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
            assertTrue(activate(fixture, BottomSoftKeySlot.F6))
            waitUntil(timeoutMillis = 5_000) { fixture.preferences.flushEntered.count == 0L }
            runOnIdle { assertEquals(Connection.CONNECTED, fixture.owner.state.value.snapshot.connection) }
            if (loseConnection) {
                fixture.transports.single().disconnect()
                waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.snapshot.connection == Connection.FAILED }
            }
            assertTrue(activate(fixture, BottomSoftKeySlot.F6))
            onNodeWithTag("softkey-F1").performClick()
            assertFalse(activate(fixture, BottomSoftKeySlot.F6))
            runOnIdle { assertEquals(loseConnection, fixture.transports.single().closed) }
            fixture.preferences.releaseFlush.countDown()
            waitUntil(timeoutMillis = 5_000) { fixture.stack.softKeyDefinition(BottomSoftKeySlot.F7)!!.enabled() }
            runOnIdle {
                assertEquals(1, fixture.preferences.flushes.get())
                assertEquals(fixture.draft.endpoint(), fixture.settings.let { runBlocking { it.load().endpoint() } })
                assertEquals(Connection.DISCONNECTED, fixture.owner.state.value.snapshot.connection)
            }
        }
    }

    @Test fun restoreSavedAndDefaultDraft() = runAtScreenshotSizes { screenshot ->
        Fixture().use { fixture ->
            val screen = open(fixture)
            screenshot("connection-tcp-draft")
            // With no saved record, F8 restores blank endpoints and normal defaults.
            onNodeWithTag("softkey-F8").performClick()
            onNodeWithText("Restore saved settings?").assertExists()
            screenshot("connection-restore-confirmation")
            onNodeWithTag("softkey-F1").performClick()
            runOnIdle { assertEquals(ControllerEndpointDraft(), screen.draft) }
            screenshot("connection-serial-defaults")
            onNodeWithText("Device path / port name").performScrollTo().performClick()
            onNodeWithTag("connection-field-input").performTextReplacement("/dev/ttyACM0")
            screenshot("connection-device-input")
            onNodeWithTag("softkey-F8").performClick()
            onNodeWithTag("softkey-F3").performClick()
            onNodeWithText("5️⃣  115200").assertExists()
            screenshot("connection-baud-page-one")
            onNodeWithTag("softkey-F7").performClick()
            onNodeWithText("1️⃣  460800").assertExists()
            screenshot("connection-baud-page-two")
            onNodeWithTag("softkey-F8").performClick()
            onNodeWithText("Baud rate (custom entry)").performScrollTo().performClick()
            onNodeWithTag("connection-field-input").performTextReplacement("250000")
            onNodeWithTag("keypad-Numbers-next").assertDoesNotExist()
            screenshot("connection-custom-baud-input")
            onNodeWithTag("softkey-F8").performClick()
            val saved = fixture.draft.copy(serialPort = "COM42", baudRate = "230400", dtr = false)
            runBlocking { fixture.settings.save(saved) }
            runOnIdle { screen.draft = saved.copy(host = "edited", serialPort = "other") }
            onNodeWithTag("softkey-F1").performClick() // retain both transport fields
            runOnIdle { assertEquals("edited", screen.draft.host); assertEquals("other", screen.draft.serialPort) }
            onNodeWithTag("softkey-F8").performClick()
            onNodeWithTag("softkey-F8").performClick() // keep editing
            runOnIdle { assertEquals("other", screen.draft.serialPort) }
            onNodeWithTag("softkey-F8").performClick()
            onNodeWithTag("softkey-F1").performClick()
            runOnIdle { assertEquals(saved, screen.draft); assertTrue(fixture.transports.isEmpty()) }
        }
    }

    @Test fun tcpFocusAndKeyboardApplyUseFieldOwner() = runAtScreenshotSizes { screenshot ->
        Fixture().use { fixture ->
            val screen = open(fixture)
            onNodeWithTag("softkey-F2").performClick()
            onNodeWithTag("softkey-R7").assertIsNotEnabled()
            onNodeWithText("Controller connection").assertIsDisplayed()
            val field = onNodeWithTag("connection-field-input").assertIsFocused().getUnclippedBoundsInRoot()
            val first = onNodeWithTag("keypad-Address-0").getUnclippedBoundsInRoot()
            val second = onNodeWithTag("keypad-Address-1").getUnclippedBoundsInRoot()
            val nextRow = onNodeWithTag("keypad-Address-10").getUnclippedBoundsInRoot()
            assertTrue(first.left >= field.right)
            val keySize = first.right - first.left
            assertTrue(keySize in 44.dp..72.dp)
            assertEquals(keySize, first.bottom - first.top)
            val gap = (keySize.value * .30f).roundToInt().dp
            assertEquals(gap, second.left - first.right)
            assertEquals(gap, nextRow.top - first.bottom)
            runOnIdle { assertSame(screen, fixture.stack.top) }
            val letterPage = onNodeWithTag("keypad-Address-next")
            val numberPage = onNodeWithTag("keypad-123-next").assertIsDisplayed()
            val digit = onNodeWithTag("keypad-123-0").assertIsDisplayed()
            val digitBounds = digit.getUnclippedBoundsInRoot()
            assertTrue(digitBounds.top >= letterPage.getUnclippedBoundsInRoot().bottom)
            onNodeWithTag("keypad-Address-0").assertTextEquals("q")
            val m = onNodeWithTag("keypad-Address-26").assertTextEquals("m").getUnclippedBoundsInRoot()
            val spaceKey = onNodeWithTag("keypad-Address-27").assertIsDisplayed()
            val space = spaceKey.getUnclippedBoundsInRoot()
            assertEquals(gap, space.left - m.right)
            assertEquals(keySize * 2 + gap, space.right - space.left)
            assertEquals(keySize, space.bottom - space.top)
            screenshot("connection-host-input")

            letterPage.performClick()
            onNodeWithTag("keypad-Address-0").assertTextEquals("Q")
            assertEquals(digitBounds, digit.getUnclippedBoundsInRoot())
            assertEquals(space, spaceKey.getUnclippedBoundsInRoot())
            screenshot("connection-host-input-uppercase")

            onNodeWithTag("keypad-Address-10").assertTextEquals("A").performClick()
            spaceKey.performClick()
            onNodeWithTag("keypad-123-3").performClick() // Remove the space from the hostname draft.
            digit.performClick()
            onNodeWithTag("keypad-123-12").performClick()
            numberPage.performClick()
            onNodeWithTag("keypad-Address-0").assertTextEquals("Q")
            onNodeWithTag("keypad-123-14").assertTextEquals(":")
            screenshot("connection-host-input-symbols")
            letterPage.performClick()
            onNodeWithTag("keypad-Address-24").assertTextEquals("b").performClick()
            onNodeWithTag("connection-field-input").assertIsFocused()
            onNodeWithTag("keypad-123-15").assertIsDisplayed().performClick()
            runOnIdle { assertEquals("A7-b", screen.draft.host) }
            onNodeWithTag("softkey-F3").performClick()
            onNodeWithTag("keypad-Numbers-8").performClick()
            onNodeWithTag("keypad-Numbers-9").performClick()
            onNodeWithTag("keypad-Numbers-next").assertDoesNotExist()
            screenshot("connection-port-input")
            onNodeWithTag("softkey-F7").performClick()
            runOnIdle { assertEquals("12", screen.draft.tcpPort); assertTrue(fixture.transports.isEmpty()) }
            onNodeWithTag("keypad-Numbers-8").assertDoesNotExist()
            onNodeWithTag("softkey-R7").assertIsEnabled()
        }
    }

    @Test fun numericTouchInputAtSmallViewport() = runSkikoComposeUiTest(size = Size(800f, 600f)) {
        Fixture().use { fixture ->
            val screen = open(fixture)
            onNodeWithTag("softkey-F3").performClick()
            onNodeWithTag("keypad-Numbers-8").performClick()
            onNodeWithTag("keypad-Numbers-9").performClick()
            onNodeWithTag("keypad-Numbers-3").performClick()
            onNodeWithTag("keypad-Numbers-15").performClick()
            runOnIdle { assertEquals("1", screen.draft.tcpPort); assertSame(screen, fixture.stack.top) }

            onNodeWithTag("softkey-F3").performClick()
            onNodeWithTag("keypad-Numbers-9").performClick()
            onNodeWithTag("softkey-F8").performClick()
            runOnIdle { assertEquals("1", screen.draft.tcpPort) }
            onNodeWithTag("keypad-Numbers-8").assertDoesNotExist()
        }
    }

    @Test fun validationAndResetConfirmationCaptures() = runAtScreenshotSizes { screenshot ->
        Fixture().use { fixture ->
            val screen = open(fixture)
            runOnIdle { screen.draft = fixture.draft.copy(tcpPort = "0") }
            onNodeWithTag("softkey-F6").performClick()
            onNodeWithText("TCP port must be a number between 1 and 65535").performScrollTo().assertIsDisplayed()
            screenshot("connection-tcp-validation")
            runOnIdle { screen.draft = ControllerEndpointDraft(serialPort = "COM3", baudRate = "0") }
            onNodeWithTag("softkey-F6").performClick()
            onNodeWithText("Baud rate must be a number", substring = true).performScrollTo().assertIsDisplayed()
            screenshot("connection-serial-validation")
            runOnIdle { screen.draft = fixture.draft.copy(postConnect = PostConnectActions(resetController = true)) }
            onNodeWithTag("softkey-F6").performClick()
            onNodeWithText("Reset controller after connecting?").assertExists()
            screenshot("connection-reset-confirmation")
            onNodeWithTag("softkey-F8").performClick()
            runOnIdle { assertTrue(fixture.transports.isEmpty()); assertSame(screen, fixture.stack.top) }
        }
    }

    @Test fun staleConnectedStateCapture() = runAtScreenshotSizes { screenshot ->
        Fixture().use { fixture ->
            runBlocking { fixture.owner.connect(fixture.draft.endpoint()) }
            fixture.transports.single().autoStatus = false
            open(fixture)
            waitUntil(timeoutMillis = 6_000) {
                runCatching { onNodeWithText("Machine state is stale or unavailable.", substring = true).assertExists() }.isSuccess
            }
            runOnIdle { assertEquals(Connection.CONNECTED, fixture.owner.state.value.snapshot.connection) }
            screenshot("connection-stale")
        }
    }

    @Test fun postConnectProgressAndOutcomeCaptures() = runAtScreenshotSizes { screenshot ->
        Fixture().use { fixture ->
            fixture.commandTimeoutMillis = 30_000
            val screen = open(fixture)
            runOnIdle { screen.draft = fixture.draft.copy(postConnect = PostConnectActions()) }
            onNodeWithTag("softkey-F6").performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.postConnectPending && fixture.stack.softKeyDefinition(BottomSoftKeySlot.F7)!!.enabled() }
            val transport = fixture.transports.single()
            runBlocking { assertEquals("\$I\n", transport.nextNonStatusWrite()) }
            onNodeWithText("Running post-connect actions…").performScrollTo().assertIsDisplayed()
            screenshot("connection-post-connect-progress")
            transport.onWrite = { if (it.endsWith('\n')) transport.emit("ok\r\n") }
            runBlocking { transport.emit("error:3\r\n") }
            waitUntil(timeoutMillis = 5_000) { !fixture.owner.state.value.postConnectPending }
            onNodeWithText("\$I rejected: error:3.").performScrollTo().assertIsDisplayed()
            screenshot("connection-post-connect-results")
            onNodeWithTag("softkey-F6").performClick()
            onNodeWithTag("softkey-F1").performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.owner.state.value.snapshot.connection == Connection.DISCONNECTED && fixture.stack.softKeyDefinition(BottomSoftKeySlot.F7)!!.enabled() }
            onNodeWithTag("softkey-F6").performClick()
            waitUntil(timeoutMillis = 5_000) { fixture.transports.size == 2 && fixture.owner.state.value.postConnectPending && fixture.stack.softKeyDefinition(BottomSoftKeySlot.F7)!!.enabled() }
            runBlocking {
                assertEquals("\$I\n", fixture.transports.last().nextNonStatusWrite())
                fixture.transports.last().emit("ALARM:1\r\n")
            }
            waitUntil(timeoutMillis = 5_000) { !fixture.owner.state.value.postConnectPending }
            onNodeWithText("and remaining queries skipped:", substring = true).performScrollTo().assertIsDisplayed()
            screenshot("connection-post-connect-unsynchronized")
            runOnIdle { assertEquals(Connection.CONNECTED, fixture.owner.state.value.snapshot.connection); assertTrue(fixture.owner.state.value.snapshot.resetRequired) }
        }
    }
}
