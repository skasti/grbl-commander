package no.grblcommander.desktop.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
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
        setContent { MaterialTheme { AppShell(ShellState.startup(), { stack }) { ScreenStackHost(stack) } } }
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

    @Test fun advancedDraftWrapApplyAndConfirmedDiscard() = runComposeUiTest {
        val (stack, root) = host()
        var result: ControllerEndpointDraft? = null
        val original = ControllerEndpointDraft(serialPort = "COM3")
        runOnIdle { AdvancedSerialSettings(original).open(root) { result = it } }
        onNodeWithTag("softkey-F1").performClick() // 8 -> 5
        onNodeWithTag("softkey-F5").performClick() // true -> false
        onNodeWithTag("softkey-F8").performClick()
        onNodeWithText("Discard serial edits?").assertExists()
        onNodeWithTag("softkey-F7").assertIsNotEnabled()
        onNodeWithTag("softkey-F8").performClick() // keep edits
        onNodeWithTag("softkey-F7").performClick()
        runOnIdle { assertEquals("5", result!!.dataBits); assertFalse(result!!.dtr); assertSame(root, stack.top) }
        result = original
        runOnIdle { AdvancedSerialSettings(original).open(root) { result = it } }
        onNodeWithTag("softkey-F2").performClick()
        onNodeWithTag("softkey-F8").performClick()
        onNodeWithTag("softkey-F1").performClick() // discard
        runOnIdle { assertNull(result); assertTrue(original.dtr) }
    }

    @Test fun sharedPostConnectDraftCancelAndApply() = runComposeUiTest {
        val (_, root) = host()
        var result: PostConnectActions? = null
        runOnIdle { PostConnectSettings(PostConnectActions()).open(root) { result = it } }
        onNodeWithTag("softkey-F2").performClick() // basic -> extended
        onNodeWithTag("softkey-F1").performClick()
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
}
