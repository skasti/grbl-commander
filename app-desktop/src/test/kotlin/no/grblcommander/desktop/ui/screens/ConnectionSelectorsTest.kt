package no.grblcommander.desktop.ui.screens

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import no.grblcommander.core.connection.ControllerEndpointDraft
import no.grblcommander.desktop.ui.runAtScreenshotSizes
import no.grblcommander.machine.transport.DiscoveredSerialPort
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
                serialPortChoice {
                    loads++
                    (1..count).map { DiscoveredSerialPort("Device $it", "") }
                }.open(root) { selected = it }
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
                runOnIdle { assertEquals("Device 7", selected); assertSame(root, stack.top) }
            } else {
                onNodeWithTag("softkey-F7").assertIsNotEnabled()
                onNodeWithTag("softkey-F8").performClick()
                runOnIdle { assertNull(selected); assertSame(root, stack.top) }
            }
        }
    }

    @Test fun discoveryFailureAndNewOpenRefresh() = runComposeUiTest {
        val (stack, root) = hostConnectionDialog()
        runOnIdle { serialPortChoice { error("unavailable") }.open(root) {} }
        onNodeWithText("Discovery unavailable:", substring = true).assertExists()
        onNodeWithTag("softkey-F8").performClick()
        runOnIdle { serialPortChoice { listOf(DiscoveredSerialPort("New device", "")) }.open(root) {} }
        onNodeWithText("1️⃣  New device").assertExists()
        onNodeWithTag("softkey-F8").performClick()
        runOnIdle { assertSame(root, stack.top) }
    }

    @Test fun cancellingPendingDiscoveryCancelsLoadAndRestoresParent() = runComposeUiTest {
        val (stack, root) = hostConnectionDialog()
        val discoveryCancelled = CompletableDeferred<Unit>()
        val results = mutableListOf<String?>()
        runOnIdle {
            serialPortChoice {
                try {
                    awaitCancellation()
                } finally {
                    discoveryCancelled.complete(Unit)
                }
            }.open(root) {
                assertSame(root, stack.top)
                results += it
            }
        }
        onNodeWithText("Loading…").assertIsDisplayed()
        onNodeWithTag("softkey-F1").assertIsNotEnabled()
        onRoot().performKeyInput { pressKey(Key.F8) }
        waitUntil { discoveryCancelled.isCompleted }
        runOnIdle { assertEquals(listOf<String?>(null), results) }
    }

    @Test fun selectorCaptureStates() = runAtScreenshotSizes { screenshot ->
        val (stack, root) = hostConnectionDialog()
        val discovery = CompletableDeferred<List<DiscoveredSerialPort>>()
        var selected: String? = null
        runOnIdle { serialPortChoice { discovery.await() }.open(root) { selected = it } }
        onNodeWithText("Loading…").assertExists()
        screenshot("connection-port-discovery-loading")
        runOnIdle { discovery.complete(serialPorts(7)) }
        onNodeWithText("6️⃣  COM6 — USB serial controller").assertIsDisplayed()
        screenshot("connection-port-page-one")
        onNodeWithTag("softkey-F7").performClick()
        onNodeWithText("1️⃣  COM7 — USB serial controller").assertIsDisplayed()
        onNodeWithTag("softkey-F2").assertIsNotEnabled()
        screenshot("connection-port-page-two")
        onNodeWithTag("softkey-F7").performClick() // wrap without changing the parent draft
        onNodeWithText("1️⃣  COM1 — USB serial controller").assertExists()
        onNodeWithTag("softkey-F8").performClick()
        runOnIdle { assertNull(selected); assertSame(root, stack.top) }
        runOnIdle { serialPortChoice { serialPorts(6) }.open(root) {} }
        onNodeWithText("6️⃣  COM6 — USB serial controller").assertIsDisplayed()
        onNodeWithTag("softkey-F7").assertIsNotEnabled()
        screenshot("connection-port-single-page")
        onNodeWithTag("softkey-F8").performClick()
        runOnIdle { serialPortChoice { emptyList() }.open(root) {} }
        onNodeWithText("No serial ports found.", substring = true).assertExists()
        screenshot("connection-port-discovery-empty")
        onNodeWithTag("softkey-F8").performClick()
        runOnIdle { serialPortChoice { error("Serial discovery unavailable") }.open(root) {} }
        onNodeWithText("Discovery unavailable:", substring = true).assertExists()
        screenshot("connection-port-discovery-failed")
        onNodeWithTag("softkey-F8").performClick()
        runOnIdle { assertSame(root, stack.top) }
    }

    @Test fun commonBaudSelectorPages() = runAtScreenshotSizes { screenshot ->
        ConnectionSettingsFixture().use { fixture ->
            val screen = openConnectionSettings(fixture)
            runOnIdle { screen.draft = ControllerEndpointDraft() }
            onNodeWithTag("softkey-F3").performClick()
            onNodeWithText("5️⃣  115200").assertExists()
            screenshot("connection-baud-page-one")
            onNodeWithTag("softkey-F7").performClick()
            onNodeWithText("1️⃣  460800").assertExists()
            screenshot("connection-baud-page-two")
            onNodeWithText("1️⃣  460800").performTouchInput { click() }
            runOnIdle { assertEquals("460800", screen.draft.baudRate) }
            onNodeWithTag("softkey-F3").performClick()
            onNodeWithTag("softkey-F8").performClick()
            runOnIdle { assertEquals("460800", screen.draft.baudRate) }
        }
    }

    private fun serialPorts(count: Int) =
        (1..count).map { DiscoveredSerialPort("COM$it", "USB serial controller") }
}
