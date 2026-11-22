package no.grblcommander.desktop.mode.mdi

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import no.grblcommander.desktop.mode.ControllerFixture
import no.grblcommander.desktop.ui.CommanderApp
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost
import no.grblcommander.desktop.ui.runAtScreenshotSizes

@OptIn(ExperimentalTestApi::class)
class MdiModeTest {
    @Test fun `MDI accepts its first mouse letter without a prior hover or input focus`() = runComposeUiTest {
        val stack = ScreenStack(MdiMode())
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { stack }) { ScreenStackHost(stack) }
            }
        }
        onNodeWithTag("keypad-CODE-2").performMouseInput { click() }
        onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("G")),
        )
    }

    @Test fun `invalid replacement preserves the saved row and draft`() = runComposeUiTest {
        val stack = ScreenStack(MdiMode())
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { stack }) { ScreenStackHost(stack) }
            }
        }
        val input = onNodeWithTag("mdi-input")
        onNodeWithTag("softkey-F5").assertIsNotEnabled()
        onNodeWithTag("softkey-F6").assertIsNotEnabled()
        input.performTextInput("N20 G0 X1")
        input.performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("mdi-line-1").performClick().assertIsSelected()
        onNodeWithTag("softkey-F5").assertIsNotEnabled()
        input.performTextReplacement("!")
        onNodeWithTag("keypad-NUM+-15").performClick()
        onNodeWithTag("mdi-line-1").assertTextContains("N20 G0 X1")
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("!")))
        onNodeWithText("Use one ASCII line without embedded real-time commands").assertExists()
        onNodeWithTag("softkey-F6").performClick()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        onNodeWithTag("softkey-F5").performClick()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("N20 G0 X1")))
    }

    @Test fun `browsing and deselecting return to the unfinished new line and caret`() = runComposeUiTest {
        val stack = ScreenStack(MdiMode())
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { stack }) { ScreenStackHost(stack) }
            }
        }
        val input = onNodeWithTag("mdi-input")
        for (line in listOf("G90", "M2")) {
            input.performTextInput(line)
            input.performKeyInput { pressKey(Key.Enter) }
        }
        input.performTextInput("G X1")
        input.performKeyInput { pressKey(Key.Home); pressKey(Key.DirectionRight) }
        onNodeWithTag("softkey-F5").performClick()
        onNodeWithTag("softkey-F5").performClick()
        onNodeWithTag("softkey-F6").performClick()
        onNodeWithTag("softkey-F6").performClick()
        input.performTextInput("1")
        onNodeWithTag("mdi-line-2").performClick()
        onNodeWithTag("mdi-line-1").performClick().performClick()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("G1 X1")))
        input.performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("mdi-line-3").assertTextContains("G1 X1")
        onNodeWithTag("mdi-line-3").performClick().performClick()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun `physical and keypad Enter save to the local buffer`() = runAtScreenshotSizes { screenshot ->
        ControllerFixture().use { fixture ->
            fixture.connect()
            setContent { CommanderApp(fixture.owner, fixture.settings) }
            onNode(hasText("MDI") and hasClickAction()).performClick()
            onNodeWithTag("mdi-input").performClick().performTextInput("G0 X1")
            onNodeWithTag("keypad-NUM+-15").assertIsDisplayed().performClick()
            onNodeWithTag("mdi-input").performTextInput("M2")
            onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("M2")))
            screenshot("mdi")
            onNodeWithTag("keypad-CODE-next").performClick()
            onNodeWithTag("keypad-NUM+-next").assertIsDisplayed().performClick()
            onNodeWithText("CODE 2/2 ▸").assertExists()
            onNodeWithText("NUM+ 2/2 ▸").assertExists()
            onNodeWithTag("mdi-input").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("softkey-F5").performClick()
            onNodeWithTag("mdi-line-2").assertIsSelected()
            onNodeWithTag("mdi-input").performTextReplacement("M30")
            onNodeWithTag("keypad-NUM+-15").performClick()
            onNodeWithTag("mdi-line-2").assertTextContains("M30")
            onNodeWithTag("mdi-line-3").assertDoesNotExist()
            screenshot("mdi-page-2")
        }
    }
}
