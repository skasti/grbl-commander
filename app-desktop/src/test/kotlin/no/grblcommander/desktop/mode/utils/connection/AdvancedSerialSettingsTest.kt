package no.grblcommander.desktop.mode.utils.connection

import androidx.compose.ui.test.*
import no.grblcommander.core.connection.ControllerEndpointDraft
import no.grblcommander.desktop.ui.runAtScreenshotSizes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AdvancedSerialSettingsTest {
    @Test fun advancedDraftWrapApplyAndConfirmedDiscard() = runAtScreenshotSizes { screenshot ->
        val (stack, root) = hostConnectionDialog()
        var result: ControllerEndpointDraft? = null
        val original = ControllerEndpointDraft(serialPort = "COM3")
        runOnIdle { AdvancedSerialSettings(original).open(root) { result = it } }
        onNodeWithTag("softkey-F5").performClick() // Both control lines off; 8/N/1/no flow.
        onNodeWithText("DTR: Off").assertExists()
        onNodeWithText("RTS: Off").assertExists()
        screenshot("connection-advanced-lines-off")
        onNodeWithTag("softkey-F1").performClick() // 8 -> 5
        onNodeWithTag("softkey-F2").performClick() // ONE -> TWO
        onNodeWithTag("softkey-F3").performClick() // NONE -> ODD
        onNodeWithTag("softkey-F4").performClick() // NONE -> RTS_CTS
        onNodeWithTag("softkey-F5").performClick()
        onNodeWithTag("softkey-F6").performClick()
        onNodeWithText("DTR: On").assertExists()
        onNodeWithText("RTS: On").assertExists()
        onNodeWithText("Stop bits: TWO").assertExists()
        onNodeWithText("Parity: ODD").assertExists()
        onNodeWithText("Flow: RTS_CTS").assertExists()
        screenshot("connection-advanced-lines-on")
        onNodeWithTag("softkey-F1").performClick() // 5 -> 6
        onNodeWithTag("softkey-F3").performClick() // ODD -> EVEN
        onNodeWithText("Data bits: 6").assertExists()
        screenshot("connection-advanced-six-even")
        onNodeWithTag("softkey-F1").performClick() // 6 -> 7
        onNodeWithTag("softkey-F3").performClick() // EVEN -> NONE
        onNodeWithText("Data bits: 7").assertExists()
        screenshot("connection-advanced-seven-none")
        onNodeWithTag("softkey-F8").performClick()
        onNodeWithText("Discard serial edits?").assertExists()
        onNodeWithTag("softkey-F7").assertIsNotEnabled()
        screenshot("connection-advanced-discard")
        onNodeWithTag("softkey-F8").performClick() // keep edits
        onNodeWithTag("softkey-F7").performClick()
        runOnIdle { assertEquals("7", result!!.dataBits); assertTrue(result!!.dtr); assertTrue(result!!.rts); assertSame(root, stack.top) }
        result = original
        runOnIdle { AdvancedSerialSettings(original).open(root) { result = it } }
        onNodeWithTag("softkey-F2").performClick()
        onNodeWithTag("softkey-F8").performClick()
        onNodeWithTag("softkey-F1").performClick() // discard
        runOnIdle { assertNull(result); assertTrue(original.dtr) }
    }
}
