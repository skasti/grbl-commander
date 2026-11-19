package no.grblcommander.desktop.ui.screens

import androidx.compose.ui.test.*
import kotlinx.coroutines.runBlocking
import no.grblcommander.core.connection.ControllerEndpointDraft
import no.grblcommander.core.connection.PostConnectActions
import no.grblcommander.desktop.ui.runAtScreenshotSizes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ConnectionFormTest {
    @Test fun restoreSavedAndDefaultDraft() = runAtScreenshotSizes { screenshot ->
        ConnectionSettingsFixture().use { fixture ->
            val screen = openConnectionSettings(fixture)
            screenshot("connection-tcp-draft")
            // With no saved record, F8 restores blank endpoints and normal defaults.
            onNodeWithTag("softkey-F8").performClick()
            onNodeWithText("Restore saved settings?").assertExists()
            screenshot("connection-restore-confirmation")
            onNodeWithTag("softkey-F1").performClick()
            runOnIdle { assertEquals(ControllerEndpointDraft(), screen.draft) }
            screenshot("connection-serial-defaults")
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

    @Test fun validationAndResetConfirmationCaptures() = runAtScreenshotSizes { screenshot ->
        ConnectionSettingsFixture().use { fixture ->
            val screen = openConnectionSettings(fixture)
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
}
