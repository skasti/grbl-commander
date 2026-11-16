package no.grblcommander.desktop.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import no.grblcommander.core.connection.ControllerConnection
import no.grblcommander.core.connection.ControllerSettings
import no.grblcommander.desktop.ui.CommanderApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class CommanderAppConnectionTest {
    @Test fun `gear selects Util and invalid form stays on same stack across modes without opening transport`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var opened = 0
        val owner = ControllerConnection(scope, { _, _ -> opened++; error("Invalid form must not open transport") })
        try {
            val settings = ControllerSettings(owner)
            setContent { CommanderApp(owner, settings) }
            onNodeWithContentDescription("Util").performClick().assertIsSelected()
            onNodeWithTag("softkey-F5").performClick()
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithText("TCP / network", substring = true).assertIsEnabled() }.isSuccess
            }
            onNodeWithText("TCP / network", substring = true).performClick()
            onNodeWithText("Hostname / IP address").performTextReplacement("controller.example")
            onNodeWithText("TCP port (1–65535)").performTextReplacement("0")
            onRoot().performKeyInput { pressKey(Key.F2) }
            onNodeWithText("TCP port must be a number between 1 and 65535").assertExists()
            runOnIdle { assertEquals(0, opened); assertNull(owner.state.value.endpoint) }
            onNodeWithText("Program", substring = true).performClick()
            onNodeWithContentDescription("Util").performClick()
            onNodeWithText("controller.example").assertExists()
            onRoot().performKeyInput { pressKey(Key.F7) }
            onNodeWithTag("terminal-prompt").assertExists()
            onNodeWithText("Hostname / IP address").assertDoesNotExist()
            runOnIdle { assertEquals(0, opened) }
        } finally { owner.close(); runBlocking { owner.awaitClosed() }; scope.cancel() }
    }
}
