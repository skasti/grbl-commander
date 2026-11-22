package no.grblcommander.desktop.mode.mdi

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost
import kotlin.test.Test

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
}
