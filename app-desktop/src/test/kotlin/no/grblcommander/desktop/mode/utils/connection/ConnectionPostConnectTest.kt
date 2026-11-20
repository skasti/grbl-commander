package no.grblcommander.desktop.mode.utils.connection

import androidx.compose.ui.test.*
import kotlinx.coroutines.runBlocking
import no.grblcommander.core.connection.PostConnectActions
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.runAtScreenshotSizes
import no.grblcommander.machine.session.MachineSnapshot.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ConnectionPostConnectTest {
    @Test fun postConnectProgressAndOutcomeCaptures() = runAtScreenshotSizes { screenshot ->
        ConnectionSettingsFixture().use { fixture ->
            fixture.commandTimeoutMillis = 30_000
            val screen = openConnectionSettings(fixture)
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
