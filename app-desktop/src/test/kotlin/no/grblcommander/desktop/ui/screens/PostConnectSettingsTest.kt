package no.grblcommander.desktop.ui.screens

import androidx.compose.ui.test.*
import no.grblcommander.core.connection.PostConnectActions
import no.grblcommander.desktop.ui.runAtScreenshotSizes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PostConnectSettingsTest {
    @Test fun sharedPostConnectDraftCancelAndApply() = runAtScreenshotSizes { screenshot ->
        val (_, root) = hostConnectionDialog()
        var result: PostConnectActions? = null
        runOnIdle { PostConnectSettings(PostConnectActions()).open(root) { result = it } }
        onNodeWithTag("softkey-F2").performClick() // basic -> extended -> off
        onNodeWithTag("softkey-F2").performClick()
        onNodeWithTag("softkey-F3").performClick()
        onNodeWithTag("softkey-F4").performClick()
        onNodeWithText("Firmware: Off").assertExists()
        onNodeWithTag("softkey-F1").assertIsNotSelected()
        onNodeWithTag("softkey-F3").assertIsNotSelected()
        onNodeWithTag("softkey-F4").assertIsNotSelected()
        screenshot("connection-post-connect-all-off")
        onNodeWithTag("softkey-F1").performClick()
        onNodeWithTag("softkey-F2").performClick() // off -> basic
        onNodeWithTag("softkey-F3").performClick()
        onNodeWithTag("softkey-F4").performClick()
        onNodeWithText("Reset: On").assertExists()
        onNodeWithTag("softkey-F1").assertIsSelected()
        onNodeWithTag("softkey-F3").assertIsSelected()
        onNodeWithTag("softkey-F4").assertIsSelected()
        screenshot("connection-post-connect-all-on")
        onNodeWithTag("softkey-F2").performClick() // basic -> extended
        onNodeWithText("Firmware: \$I+").assertExists()
        screenshot("connection-post-connect-extended")
        onNodeWithTag("softkey-F3").performClick()
        onNodeWithTag("softkey-F7").performClick()
        runOnIdle {
            assertEquals(PostConnectActions.FirmwareInfo.EXTENDED, result!!.firmwareInfo)
            assertTrue(result!!.resetController)
            assertFalse(result!!.parameters)
        }
        runOnIdle { PostConnectSettings(result!!).open(root) { result = it } }
        onNodeWithTag("softkey-F4").performClick()
        onNodeWithTag("softkey-F8").performClick()
        runOnIdle { assertNull(result) }
    }
}
