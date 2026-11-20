package no.grblcommander.desktop.mode.utils.connection

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import no.grblcommander.core.connection.ControllerConnection
import no.grblcommander.core.connection.ControllerSettings
import no.grblcommander.configuration.ControllerEndpointPreferences
import no.grblcommander.desktop.ui.navigation.*
import no.grblcommander.desktop.ui.screens.ManualMode
import no.grblcommander.desktop.ui.screens.Terminal
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class UtilModeTest {
    @Test fun `connection stays on persistent Util stack and bank selection restores on return`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = ControllerConnection(scope)
        val node = Preferences.userRoot().node("no/grblcommander/tests/${UUID.randomUUID()}")
        try {
            val root = Terminal(ControllerSettings(owner, ControllerEndpointPreferences(node)))
            val util = ScreenStack(root)
            val program = ScreenStack(ManualMode())
            var active by mutableStateOf(util)
            setContent { ScreenStackHost(listOf(util, program), active) }
            onNodeWithTag("terminal-prompt").assertExists()
            runOnIdle {
                assertNull(util.softKeyDefinition(BottomSoftKeySlot.F6))
                assertEquals("Filters", util.softKeyDefinition(BottomSoftKeySlot.F7)!!.label)
                activateSoftKey({ util }, RightSoftKeySlot.R7)
                assertIs<ControllerConnectionSettings>(util.top)
                assertNotNull(util.softKeyDefinition(BottomSoftKeySlot.F6))
                assertEquals("Post-Connect Actions", util.softKeyDefinition(BottomSoftKeySlot.F5)!!.label)
                assertEquals("Save", util.softKeyDefinition(BottomSoftKeySlot.F7)!!.label)
                assertEquals(setOf(RightSoftKeySlot.R7), util.effectiveRightKeys.keys)
                assertEquals("⌨\nTerminal", util.softKeyDefinition(RightSoftKeySlot.R7)!!.label)
                assertEquals(2, util.size)
            }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithText("Serial / USB").assertExists() }.isSuccess
            }
            runOnIdle {
                (util.top as ControllerConnectionSettings).draft =
                    (util.top as ControllerConnectionSettings).draft.copy(host = "unsaved.example")
                active = program
            }
            onNodeWithText("Manual mode · navigation demonstration only").assertExists()
            runOnIdle {
                active = util
                assertEquals("unsaved.example", (util.top as ControllerConnectionSettings).draft.host)
            }
            onNodeWithText("Controller connection").assertExists()
            runOnIdle {
                activateSoftKey({ util }, RightSoftKeySlot.R7)
                assertSame(root, util.top)
                assertNull(util.softKeyDefinition(BottomSoftKeySlot.F6))
                assertEquals("Filters", util.softKeyDefinition(BottomSoftKeySlot.F7)!!.label)
                assertNull(util.softKeyDefinition(BottomSoftKeySlot.F5))
                assertNull(owner.state.value.endpoint)
            }
        } finally {
            owner.close(); runBlocking { owner.awaitClosed() }; scope.cancel()
            node.removeNode(); Preferences.userRoot().flush()
        }
    }
}
