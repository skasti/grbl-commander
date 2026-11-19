package no.grblcommander.desktop.ui.screens

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import kotlinx.coroutines.CompletableDeferred
import no.grblcommander.desktop.ui.runAtScreenshotSizes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalTestApi::class)
class ConnectionSelectorsTest {
    @Test fun numberedChoicesAndPagingAtSmallViewport() {
        for (count in listOf(0, 6, 7)) runSkikoComposeUiTest(size = Size(800f, 600f)) {
            val (stack, root) = hostConnectionDialog()
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
        val (stack, root) = hostConnectionDialog()
        runOnIdle { ConnectionChoice("Ports") { error("unavailable") }.open(root) {} }
        onNodeWithText("Discovery unavailable:", substring = true).assertExists()
        onNodeWithTag("softkey-F8").performClick()
        runOnIdle { ConnectionChoice("Ports") { listOf("new" to "New device") }.open(root) {} }
        onNodeWithText("1️⃣  New device").assertExists()
        onNodeWithTag("softkey-F8").performClick()
        runOnIdle { assertSame(root, stack.top) }
    }

    @Test fun selectorCaptureStates() = runAtScreenshotSizes { screenshot ->
        val (stack, root) = hostConnectionDialog()
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
