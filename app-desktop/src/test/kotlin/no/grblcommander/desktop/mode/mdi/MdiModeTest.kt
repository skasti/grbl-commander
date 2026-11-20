package no.grblcommander.desktop.mode.mdi

import androidx.compose.material3.MaterialTheme
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
}
