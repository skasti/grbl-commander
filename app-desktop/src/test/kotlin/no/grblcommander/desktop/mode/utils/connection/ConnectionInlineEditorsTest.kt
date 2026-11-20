package no.grblcommander.desktop.mode.utils.connection

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import no.grblcommander.core.connection.ControllerEndpointDraft
import no.grblcommander.desktop.ui.runAtScreenshotSizes
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ConnectionInlineEditorsTest {
    @Test fun serialDeviceAndCustomBaudEditors() = runAtScreenshotSizes { screenshot ->
        ConnectionSettingsFixture().use { fixture ->
            val screen = openConnectionSettings(fixture)
            runOnIdle { screen.draft = ControllerEndpointDraft() }
            onNodeWithText("Device path / port name").performScrollTo().performClick()
            onNodeWithTag("connection-field-input").performTextReplacement("/dev/ttyACM0")
            onNodeWithTag("keypad-QWERTY-9").assertIsDisplayed()
            onNodeWithTag("keypad-NUM+-next").assertIsDisplayed()
            screenshot("connection-device-input")
            onNodeWithTag("softkey-F8").performClick()

            onNodeWithText("Baud rate (custom entry)").performScrollTo().performClick()
            onNodeWithTag("connection-field-input").performTextReplacement("250000")
            onNodeWithTag("keypad-NUM-next").assertDoesNotExist()
            screenshot("connection-custom-baud-input")
            onNodeWithTag("softkey-F8").performClick()
        }
    }

    @Test fun tcpFocusAndKeyboardApplyUseFieldOwner() = runAtScreenshotSizes { screenshot ->
        ConnectionSettingsFixture().use { fixture ->
            val screen = openConnectionSettings(fixture)
            onNodeWithTag("softkey-F2").performClick()
            onNodeWithTag("softkey-R7").assertIsNotEnabled()
            onNodeWithText("Controller connection").assertIsDisplayed()
            val field = onNodeWithTag("connection-field-input").assertIsFocused().getUnclippedBoundsInRoot()
            val first = onNodeWithTag("keypad-QWERTY-0").getUnclippedBoundsInRoot()
            val second = onNodeWithTag("keypad-QWERTY-1").getUnclippedBoundsInRoot()
            val nextRow = onNodeWithTag("keypad-QWERTY-10").getUnclippedBoundsInRoot()
            assertTrue(first.left >= field.right)
            val keySize = first.right - first.left
            assertTrue(keySize in 44.dp..72.dp)
            assertEquals(keySize, first.bottom - first.top)
            val gap = (keySize.value * .30f).roundToInt().dp
            assertEquals(gap, second.left - first.right)
            assertEquals(gap, nextRow.top - first.bottom)
            runOnIdle { assertSame(screen, fixture.stack.top) }
            val letterPage = onNodeWithTag("keypad-QWERTY-next").assertTextEquals("QWERTY 1/2 ▸")
            val numberPage = onNodeWithTag("keypad-NUM+-next").assertIsDisplayed().assertTextEquals("NUM+ 1/2 ▸")
            val digit = onNodeWithTag("keypad-NUM+-0").assertIsDisplayed()
            val digitBounds = digit.getUnclippedBoundsInRoot()
            assertEquals(keySize, digitBounds.right - digitBounds.left)
            assertEquals(keySize, digitBounds.bottom - digitBounds.top)
            val letterPageBounds = letterPage.getUnclippedBoundsInRoot()
            assertTrue(digitBounds.top >= letterPageBounds.bottom)
            assertEquals(letterPageBounds.right, numberPage.getUnclippedBoundsInRoot().right)
            assertEquals(
                onNodeWithTag("keypad-QWERTY-9").getUnclippedBoundsInRoot().right,
                onNodeWithTag("keypad-NUM+-3").getUnclippedBoundsInRoot().right,
            )
            onNodeWithTag("keypad-QWERTY-0").assertTextEquals("q")
            val m = onNodeWithTag("keypad-QWERTY-26").assertTextEquals("m").getUnclippedBoundsInRoot()
            val spaceKey = onNodeWithTag("keypad-QWERTY-27").assertIsDisplayed()
            val space = spaceKey.getUnclippedBoundsInRoot()
            assertEquals(gap, space.left - m.right)
            assertEquals(keySize * 2 + gap, space.right - space.left)
            assertEquals(keySize, space.bottom - space.top)
            screenshot("connection-host-input")

            letterPage.performClick().assertTextEquals("QWERTY 2/2 ▸")
            onNodeWithTag("keypad-QWERTY-0").assertTextEquals("Q")
            assertEquals(digitBounds, digit.getUnclippedBoundsInRoot())
            assertEquals(space, spaceKey.getUnclippedBoundsInRoot())
            screenshot("connection-host-input-uppercase")

            onNodeWithTag("keypad-QWERTY-10").assertTextEquals("A").performClick()
            spaceKey.performClick()
            onNodeWithTag("keypad-NUM+-3").performClick() // Remove the space from the hostname draft.
            digit.performClick()
            onNodeWithTag("keypad-NUM+-12").performClick()
            numberPage.performClick()
            onNodeWithTag("keypad-QWERTY-0").assertTextEquals("Q")
            onNodeWithTag("keypad-NUM+-14").assertTextEquals(":")
            screenshot("connection-host-input-symbols")
            letterPage.performClick()
            onNodeWithTag("keypad-QWERTY-24").assertTextEquals("b").performClick()
            onNodeWithTag("connection-field-input").assertIsFocused()
            onNodeWithTag("keypad-NUM+-15").assertIsDisplayed().performClick()
            runOnIdle { assertEquals("A7-b", screen.draft.host) }
            onNodeWithTag("softkey-F3").performClick()
            onNodeWithTag("keypad-NUM-8").performClick()
            onNodeWithTag("keypad-NUM-9").performClick()
            onNodeWithTag("keypad-NUM-next").assertDoesNotExist()
            val portKey = onNodeWithTag("keypad-NUM-8").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertEquals(keySize, portKey.right - portKey.left)
            assertEquals(keySize, portKey.bottom - portKey.top)
            screenshot("connection-port-input")
            onNodeWithTag("softkey-F7").performClick()
            runOnIdle { assertEquals("12", screen.draft.tcpPort); assertTrue(fixture.transports.isEmpty()) }
            onNodeWithTag("keypad-NUM-8").assertDoesNotExist()
            onNodeWithTag("softkey-R7").assertIsEnabled()
        }
    }

    @Test fun numericTouchInputAtSmallViewport() = runSkikoComposeUiTest(size = Size(800f, 600f)) {
        ConnectionSettingsFixture().use { fixture ->
            val screen = openConnectionSettings(fixture)
            onNodeWithTag("softkey-F3").performClick()
            onNodeWithTag("keypad-NUM-8").performClick()
            onNodeWithTag("keypad-NUM-9").performClick()
            onNodeWithTag("keypad-NUM-3").performClick()
            onNodeWithTag("keypad-NUM-15").performClick()
            runOnIdle { assertEquals("1", screen.draft.tcpPort); assertSame(screen, fixture.stack.top) }

            onNodeWithTag("softkey-F3").performClick()
            onNodeWithTag("keypad-NUM-9").performClick()
            onNodeWithTag("softkey-F8").performClick()
            runOnIdle { assertEquals("1", screen.draft.tcpPort) }
            onNodeWithTag("keypad-NUM-8").assertDoesNotExist()
        }
    }
}
