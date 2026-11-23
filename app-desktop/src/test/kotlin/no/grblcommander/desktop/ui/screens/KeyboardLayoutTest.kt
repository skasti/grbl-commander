package no.grblcommander.desktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.*
import no.grblcommander.desktop.ui.components.controls.*
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.*

@OptIn(ExperimentalTestApi::class)
class KeyboardLayoutTest {
    @Test fun `stacked keypads stay inside workspace at 1024 by 768`() = verifyLayout(1024f, 768f)
    @Test fun `stacked keypads stay inside workspace at 1024 by 720`() = verifyLayout(1024f, 720f)
    @Test fun `stacked keypads stay inside workspace at 1280 by 720`() = verifyLayout(1280f, 720f)
    @Test fun `stacked keypads stay inside workspace at 1680 by 1050`() = verifyLayout(1680f, 1050f, keyDp = 60)
    @Test fun `stacked keypads stay inside workspace at 1920 by 1080`() = verifyLayout(1920f, 1080f, keyDp = 63)
    @Test fun `stacked keypads stay inside workspace at 2560 by 1440`() = verifyLayout(2560f, 1440f, keyDp = 72)

    @Test fun `72 dp fits both the calculation budget and actual layouts at 1660 by 1220`() =
        verifyLayout(1660f, 1220f, keyDp = 72)

    @Test fun `minimum logical viewport preserves 44 dp keys at fractional display density`() = verifyLayout(1024f, 720f, 1.25f)

    @Test fun `minimum logical viewport preserves minimum targets at 110 percent density`() = verifyLayout(1024f, 720f, 1.1f)

    @Test fun `resizing the shell updates both clusters together`() = runSkikoComposeUiTest(size = Size(1660f, 1220f)) {
        val stack = ScreenStack(KeypadFixture())
        var viewport by mutableStateOf(Size(1024f, 720f))
        setContent {
            MaterialTheme {
                Box(Modifier.size(viewport.width.dp, viewport.height.dp)) {
                    AppShell(ShellState.startup(), { stack }) { ScreenStackHost(stack) }
                }
            }
        }
        fun checkKeys(expected: Int) {
            for (tag in listOf("keypad-CODE-0", "keypad-NUM+-0")) {
                val bounds = onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
                assertEquals(expected.dp, bounds.right - bounds.left)
                assertEquals(expected.dp, bounds.bottom - bounds.top)
            }
        }
        checkKeys(44)
        runOnIdle { viewport = Size(1500f, 1152f) }
        checkKeys(68)
        runOnIdle { viewport = Size(1660f, 1220f) }
        checkKeys(72)
        runOnIdle { viewport = Size(1660f, 1100f) }
        checkKeys(64)
        runOnIdle { viewport = Size(1024f, 720f) }
        checkKeys(44)
    }

    private fun verifyLayout(width: Float, height: Float, scale: Float = 1f, keyDp: Int = 44) = runSkikoComposeUiTest(
        size = Size(width * scale, height * scale), density = androidx.compose.ui.unit.Density(scale),
    ) {
        val fixture = KeypadFixture()
        val stack = ScreenStack(fixture)
        setContent {
            MaterialTheme { AppShell(ShellState.startup(), { stack }) { ScreenStackHost(stack) } }
        }
        fun SemanticsNodeInteraction.bounds(): androidx.compose.ui.geometry.Rect {
            val rect = fetchSemanticsNode().boundsInRoot
            return androidx.compose.ui.geometry.Rect(rect.left / scale, rect.top / scale, rect.right / scale, rect.bottom / scale)
        }
        val minimumKey = ceil(44f * scale).toInt()
        val expectedKey = (keyDp * scale).roundToInt().coerceAtLeast(minimumKey)
        fun assertStandardKey(tag: String) {
            val bounds = onNodeWithTag("keypad-$tag").assertIsDisplayed().bounds()
            assertEquals(expectedKey / scale, bounds.width, .01f, "$tag width")
            assertEquals(expectedKey / scale, bounds.height, .01f, "$tag height")
        }
        fun check(input: SemanticsNodeInteraction) {
            val bottom = onNodeWithTag("softkey-F1").bounds()
            val right = onNodeWithTag("softkey-R1").bounds()
            val field = input.bounds()
            val lettersFooter = onNodeWithTag("keypad-CODE-next").bounds()
            val footer = onNodeWithTag("keypad-NUM+-next").bounds()
            val first = onNodeWithTag("keypad-CODE-0").bounds()
            val numbersFirst = onNodeWithTag("keypad-NUM+-0").bounds()
            assertTrue(field.height >= 47.99f, "Input remains a usable touch target: $field")
            assertTrue(first.left >= field.right, "Keyboard must stay to the right of the input")
            assertTrue(numbersFirst.top >= lettersFooter.bottom, "Numbers must be below letters")
            val gridHeight = 4 * expectedKey + 3 * (.30f * expectedKey).roundToInt()
            val compactHeight = (2 * gridHeight + 2 * minimumKey) / scale
            val tilePadding = (8f * scale).roundToInt() / scale
            val workspaceHeight = bottom.top - tilePadding - (78f * scale).roundToInt() / scale
            val margin = ((workspaceHeight - 2 * tilePadding - compactHeight) / 2).coerceIn(0f, 30f)
            // The screen and bottom tile each contribute an additional 8 dp.
            assertEquals(2 * tilePadding + margin, bottom.top - footer.bottom, 2f, "Combined panel keeps its adaptive bottom margin")
            if (height == 720f) {
                val budgetHeight = workspaceHeight * .75f
                assertTrue(footer.bottom - first.top > budgetHeight, "The workspace budget must not clip a fitting panel")
            }
            if (height >= 1050f) {
                assertTrue(first.top - 78f > 8f, "Excess height stays above the combined panel")
                assertTrue(numbersFirst.top - lettersFooter.bottom <= 16.1f, "Excess height must not separate clusters")
            }
            for (cluster in listOf("CODE", "NUM+")) {
                for (slot in 0 until 16) {
                    val bounds = onNodeWithTag("keypad-$cluster-$slot").assertHasClickAction().bounds()
                    assertEquals(expectedKey / scale, bounds.width, .01f, "$cluster/$slot width")
                    assertEquals(expectedKey / scale, bounds.height, .01f, "$cluster/$slot height")
                    assertTrue(bounds.top >= 78f && bounds.bottom <= bottom.top - 8f, "Key must stay between header and softkeys")
                    assertTrue(bounds.left >= field.right && bounds.right <= right.left - 39f, "Key must stay beside input, inside workspace")
                    if (slot % 4 > 0) {
                        val previous = onNodeWithTag("keypad-$cluster-${slot - 1}").bounds()
                        val factor = if (cluster == "NUM+" && slot % 4 == 3) .6f else .3f
                        assertEquals(bounds.width * factor, bounds.left - previous.right, .6f, "Configured column spacing")
                    }
                    if (slot >= 4) {
                        val above = onNodeWithTag("keypad-$cluster-${slot - 4}").bounds()
                        assertEquals(bounds.height * .3f, bounds.top - above.bottom, .6f, "Configured row spacing")
                    }
                }
            }
        }
        val prompt = onNodeWithTag("fixture-input")
        check(prompt)
        val letterBounds = onNodeWithTag("keypad-CODE-0").bounds()
        val numberBounds = onNodeWithTag("keypad-NUM+-0").bounds()
        prompt.performClick()
        // The full-sized key is actionable at its edge, away from the label.
        onNodeWithTag("keypad-CODE-2").performTouchInput { click(androidx.compose.ui.geometry.Offset(1f, center.y)) }
        prompt.assertIsFocused().assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("G")))
        onNodeWithTag("keypad-CODE-next").performClick()
        onNodeWithText("CODE 2/2 ▸").assertExists()
        onNodeWithText("NUM+ 1/2 ▸").assertExists()
        assertEquals(letterBounds, onNodeWithTag("keypad-CODE-0").bounds())
        assertEquals(numberBounds, onNodeWithTag("keypad-NUM+-0").bounds())
        onNodeWithTag("keypad-NUM+-next").performClick()
        onNodeWithText("CODE 2/2 ▸").assertExists()
        onNodeWithText("NUM+ 2/2 ▸").assertExists()
        onNodeWithTag("keypad-CODE-next").performClick()
        onNodeWithTag("keypad-NUM+-next").performClick()
        check(prompt)
        runOnIdle { fixture.layouts = listOf(Keymaps.qwerty, Keymaps.numPlus) }
        assertStandardKey("QWERTY-0")
        assertStandardKey("QWERTY-9")
        assertStandardKey("NUM+-0")
        assertStandardKey("NUM+-15")
        runOnIdle { fixture.layouts = listOf(Keymaps.num) }
        assertStandardKey("NUM-0")
        assertStandardKey("NUM-15")
    }

    private class KeypadFixture : Screen<Unit>(Unit) {
        var layouts by mutableStateOf(listOf(Keymaps.code, Keymaps.numPlus))

        @Composable
        override fun Content() {
            var value by remember { mutableStateOf(TextFieldValue()) }
            val clusters = remember(layouts) { layouts.map(::KeyClusterState) }
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(value, { value = it }, Modifier.weight(1f).testTag("fixture-input"))
                KeypadPanel(clusters, { editWithKeypad(value, it, { next -> value = next }, {}) },
                    Modifier.fillMaxHeight())
            }
        }
    }
}
