package no.grblcommander.desktop.ui.screens

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
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class UtilModeTest {
    @Test fun `connection stays on persistent Util stack and bank selection restores on return`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = ControllerConnection(scope)
        val node = Preferences.userRoot().node("no/grblcommander/tests/${UUID.randomUUID()}")
        try {
            val root = UtilMode(ControllerSettings(owner, ControllerEndpointPreferences(node)))
            val util = ScreenStack(root)
            val program = ScreenStack(ManualMode())
            var active by mutableStateOf(util)
            setContent { ScreenStackHost(listOf(util, program), active) }
            onNodeWithText("Controller connection").assertExists()
            runOnIdle {
                assertNull(util.softKeyDefinition(BottomSoftKeySlot.F6))
                assertTrue(util.softKeyDefinition(BottomSoftKeySlot.F7)!!.active())
                activateSoftKey({ util }, BottomSoftKeySlot.F8)
                assertIs<ControllerConnectionSettings>(util.top)
                assertNull(util.softKeyDefinition(BottomSoftKeySlot.F6))
                assertTrue(util.softKeyDefinition(BottomSoftKeySlot.F8)!!.active())
                assertFalse(util.softKeyDefinition(BottomSoftKeySlot.F7)!!.active())
                assertTrue(util.effectiveRightKeys.isEmpty())
                assertEquals(2, util.size)
                activateSoftKey({ util }, BottomSoftKeySlot.F8)
                assertEquals(2, util.size)
            }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithText("● Serial / USB").assertIsEnabled() }.isSuccess
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
                activateSoftKey({ util }, BottomSoftKeySlot.F7)
                assertSame(root, util.top)
                assertNull(util.softKeyDefinition(BottomSoftKeySlot.F6))
                assertTrue(util.softKeyDefinition(BottomSoftKeySlot.F7)!!.active())
                assertFalse(util.softKeyDefinition(BottomSoftKeySlot.F8)!!.active())
                assertNull(owner.state.value.endpoint)
            }
        } finally {
            owner.close(); runBlocking { owner.awaitClosed() }; scope.cancel()
            node.removeNode(); Preferences.userRoot().flush()
        }
    }
}
