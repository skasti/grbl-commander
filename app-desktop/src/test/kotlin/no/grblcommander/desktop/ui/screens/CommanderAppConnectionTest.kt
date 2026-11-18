package no.grblcommander.desktop.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import no.grblcommander.core.connection.ControllerConnection
import no.grblcommander.core.connection.ControllerSettings
import no.grblcommander.desktop.ui.CommanderApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import java.util.UUID
import java.util.prefs.Preferences
import no.grblcommander.configuration.ControllerEndpointPreferences

@OptIn(ExperimentalTestApi::class)
class CommanderAppConnectionTest {
    @Test fun `automatic failure changes header only and details remain available in settings`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val node = Preferences.userRoot().node("no/grblcommander/tests/${UUID.randomUUID()}")
        val preferences = ControllerEndpointPreferences(node)
        preferences.save("version=1\ntransport=TCP\nhost=localhost\nport=1234")
        var attempts = 0
        val owner = ControllerConnection(scope, { _, _ -> attempts++; error("Startup endpoint unavailable") })
        val settings = ControllerSettings(owner, preferences)
        val initialization = scope.launch { settings.initialize() }
        try {
            setContent { CommanderApp(owner, settings) }
            waitUntil(timeoutMillis = 5_000) { owner.state.value.snapshot.failure != null }
            onNodeWithText("FAILED").assertExists()
            onNodeWithText("Startup endpoint unavailable", substring = true).assertDoesNotExist()
            onNodeWithTag("terminal-prompt").assertDoesNotExist() // Program mode was not replaced.
            onNodeWithContentDescription("Util").performClick()
            onNodeWithTag("softkey-R7").performClick()
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithText("Startup endpoint unavailable", substring = true).assertExists() }.isSuccess
            }
            assertEquals(1, attempts)
            assertNotNull(owner.state.value.snapshot.failure)
        } finally {
            scope.cancel(); owner.close()
            runBlocking { initialization.join(); owner.awaitClosed() }
            node.removeNode()
        }
    }

    @Test fun `gear selects Util and invalid form stays on same stack across modes without opening transport`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var opened = 0
        val owner = ControllerConnection(scope, { _, _ -> opened++; error("Invalid form must not open transport") })
        try {
            val settings = ControllerSettings(owner)
            setContent { CommanderApp(owner, settings) }
            onNodeWithContentDescription("Util").performClick().assertIsSelected()
            onNodeWithTag("softkey-R7").performClick()
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithTag("softkey-F7").assertIsEnabled() }.isSuccess
            }
            onNodeWithTag("softkey-F1").performClick()
            onNodeWithTag("softkey-F2").performClick()
            onNodeWithTag("connection-field-input").performTextReplacement("controller.example")
            onNodeWithTag("softkey-F7").performClick()
            onNodeWithTag("softkey-F3").performClick()
            onNodeWithTag("connection-field-input").performTextReplacement("0")
            onNodeWithTag("softkey-F7").performClick()
            onNodeWithText("0").assertExists()
            onRoot().performKeyInput { pressKey(Key.F6) }
            onNodeWithText("TCP port must be a number between 1 and 65535").assertExists()
            runOnIdle { assertEquals(0, opened); assertNull(owner.state.value.endpoint) }
            onNodeWithText("Program", substring = true).performClick()
            onNodeWithContentDescription("Util").performClick()
            onNodeWithText("controller.example").assertExists()
            onRoot().performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.F7); keyUp(Key.ShiftLeft) }
            onNodeWithTag("terminal-prompt").assertExists()
            onNodeWithText("Hostname / IP address").assertDoesNotExist()
            runOnIdle { assertEquals(0, opened) }
        } finally { owner.close(); runBlocking { owner.awaitClosed() }; scope.cancel() }
    }
}
