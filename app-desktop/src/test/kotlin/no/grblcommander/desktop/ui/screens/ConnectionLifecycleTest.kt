package no.grblcommander.desktop.ui.screens

import androidx.compose.ui.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.RightSoftKeySlot
import no.grblcommander.desktop.ui.navigation.activateSoftKey
import no.grblcommander.desktop.ui.runAtScreenshotSizes
import no.grblcommander.machine.session.MachineSnapshot.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ConnectionLifecycleTest {
    @Test fun `intentional disconnect is neutral while an unexpected connection loss stays visible`() = runAtScreenshotSizes { screenshot ->
        ConnectionSettingsFixture().use { fixture ->
            runBlocking { fixture.owner.connect(fixture.draft.endpoint()) }
            openConnectionSettings(fixture)
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
        ConnectionSettingsFixture().use { fixture ->
            fixture.autoStatus = false
            openConnectionSettings(fixture)
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
        ConnectionSettingsFixture().use { fixture ->
            runBlocking { fixture.owner.connect(fixture.draft.endpoint()) }
            openConnectionSettings(fixture)
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
        ConnectionSettingsFixture().use { fixture ->
            runBlocking { fixture.owner.connect(fixture.draft.endpoint()) }
            openConnectionSettings(fixture)
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
        ConnectionSettingsFixture().use { fixture ->
            runBlocking { fixture.owner.connect(fixture.draft.endpoint()) }
            val screen = openConnectionSettings(fixture)
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
        ConnectionSettingsFixture().use { fixture ->
            openConnectionSettings(fixture)
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

    @Test fun staleConnectedStateCapture() = runAtScreenshotSizes { screenshot ->
        ConnectionSettingsFixture().use { fixture ->
            runBlocking { fixture.owner.connect(fixture.draft.endpoint()) }
            fixture.transports.single().autoStatus = false
            openConnectionSettings(fixture)
            waitUntil(timeoutMillis = 6_000) {
                runCatching { onNodeWithText("Machine state is stale or unavailable.", substring = true).assertExists() }.isSuccess
            }
            runOnIdle { assertEquals(Connection.CONNECTED, fixture.owner.state.value.snapshot.connection) }
            screenshot("connection-stale")
        }
    }
}
