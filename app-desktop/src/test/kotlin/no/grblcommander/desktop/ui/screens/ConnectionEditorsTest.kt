package no.grblcommander.desktop.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CompletableDeferred
import no.grblcommander.desktop.ui.runAtScreenshotSizes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import no.grblcommander.core.connection.ControllerEndpointDraft
import no.grblcommander.core.connection.PostConnectActions
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ConnectionEditorsTest {
    private fun ComposeUiTest.host(): Pair<ScreenStack, Screen<Unit>> {
        val root = object : Screen<Unit>(Unit) { @Composable override fun Content() { Text("Root") } }
        val stack = ScreenStack(root)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { AppShell(ShellState.startup(), { stack }) { ScreenStackHost(stack) } } }
        return stack to root
    }

    @Test fun numberedChoicesAndPagingAtSmallViewport() {
        for (count in listOf(0, 6, 7)) runSkikoComposeUiTest(size = Size(800f, 600f)) {
            val (stack, root) = host()
            var selected: String? = null
            var loads = 0
            runOnIdle {
                ConnectionChoice("Ports") { loads++; (1..count).map { it.toString() to "Device $it" } }.open(root) { selected = it }
            }
            waitUntil { loads == 1 }
            waitForIdle()
            if (count == 0) {
                onNodeWithText("No serial ports found.", substring = true).assertExists()
                onNodeWithTag("softkey-F1").assertIsNotEnabled()
            } else {
                onNodeWithText("1️⃣  Device 1").assertIsDisplayed()
                onNodeWithText("6️⃣  Device 6").assertIsDisplayed()
            }
            if (count == 7) {
                onNodeWithTag("softkey-F7").performClick()
                onNodeWithText("1️⃣  Device 7").assertIsDisplayed()
                onNodeWithTag("softkey-F2").assertIsNotEnabled()
                onNodeWithTag("softkey-F1").performKeyInput { pressKey(Key.F1) }
                runOnIdle { assertEquals("7", selected); assertSame(root, stack.top) }
            } else {
                onNodeWithTag("softkey-F7").assertIsNotEnabled()
                onNodeWithTag("softkey-F8").performClick()
                runOnIdle { assertNull(selected); assertSame(root, stack.top) }
            }
        }
    }

    @Test fun discoveryFailureAndNewOpenRefresh() = runComposeUiTest {
        val (stack, root) = host()
        runOnIdle { ConnectionChoice("Ports") { error("unavailable") }.open(root) {} }
        onNodeWithText("Discovery unavailable:", substring = true).assertExists()
        onNodeWithTag("softkey-F8").performClick()
        runOnIdle { ConnectionChoice("Ports") { listOf("new" to "New device") }.open(root) {} }
        onNodeWithText("1️⃣  New device").assertExists()
        onNodeWithTag("softkey-F8").performClick()
        runOnIdle { assertSame(root, stack.top) }
    }

    @Test fun advancedDraftWrapApplyAndConfirmedDiscard() = runAtScreenshotSizes { screenshot ->
        val (stack, root) = host()
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

    @Test fun sharedPostConnectDraftCancelAndApply() = runAtScreenshotSizes { screenshot ->
        val (_, root) = host()
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

    @Test fun numericTouchInputAtSmallViewport() = runSkikoComposeUiTest(size = Size(800f, 600f)) {
        val (_, root) = host()
        var result: String? = null
        runOnIdle { ConnectionTextInput("TCP port", "1234", true).open(root) { result = it } }
        onNodeWithTag("keypad-Numbers-8").performClick() // replace selection with 1
        onNodeWithTag("keypad-Numbers-9").performClick() // 2
        onNodeWithTag("keypad-Numbers-3").performClick() // backspace
        onNodeWithTag("keypad-Numbers-15").performClick()
        runOnIdle { assertEquals("1", result) }
    }

    @Test fun selectorCaptureStates() = runAtScreenshotSizes { screenshot ->
        val (stack, root) = host()
        val discovery = CompletableDeferred<List<Pair<String, String>>>()
        var selected: String? = null
        runOnIdle { ConnectionChoice("Select serial port") { discovery.await() }.open(root) { selected = it } }
        onNodeWithText("Loading…").assertExists()
        screenshot("connection-port-discovery-loading")
        runOnIdle { discovery.complete((1..7).map { "COM$it" to "USB serial controller — COM$it" }) }
        onNodeWithText("6️⃣  USB serial controller — COM6").assertIsDisplayed()
        screenshot("connection-port-page-one")
        onNodeWithTag("softkey-F7").performClick()
        onNodeWithText("1️⃣  USB serial controller — COM7").assertIsDisplayed()
        onNodeWithTag("softkey-F2").assertIsNotEnabled()
        screenshot("connection-port-page-two")
        onNodeWithTag("softkey-F7").performClick() // wrap without changing the parent draft
        onNodeWithText("1️⃣  USB serial controller — COM1").assertExists()
        onNodeWithTag("softkey-F8").performClick()
        runOnIdle { assertNull(selected); assertSame(root, stack.top) }
        runOnIdle { ConnectionChoice("Select serial port") { (1..6).map { "COM$it" to "USB serial controller — COM$it" } }.open(root) {} }
        onNodeWithText("6️⃣  USB serial controller — COM6").assertIsDisplayed()
        onNodeWithTag("softkey-F7").assertIsNotEnabled()
        screenshot("connection-port-single-page")
        onNodeWithTag("softkey-F8").performClick()
        runOnIdle { ConnectionChoice("Select serial port") { emptyList() }.open(root) {} }
        onNodeWithText("No serial ports found.", substring = true).assertExists()
        screenshot("connection-port-discovery-empty")
        onNodeWithTag("softkey-F8").performClick()
        runOnIdle { ConnectionChoice("Select serial port") { error("Serial discovery unavailable") }.open(root) {} }
        onNodeWithText("Discovery unavailable:", substring = true).assertExists()
        screenshot("connection-port-discovery-failed")
        onNodeWithTag("softkey-F8").performClick()
        runOnIdle { assertSame(root, stack.top) }
    }
}
