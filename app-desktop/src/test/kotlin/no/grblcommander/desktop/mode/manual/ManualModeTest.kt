package no.grblcommander.desktop.mode.manual

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost
import kotlin.test.Test
import kotlin.test.assertSame

@OptIn(ExperimentalTestApi::class)
class ManualModeTest {
    @Test fun `details retain local notes when switching modes`() = runComposeUiTest {
        val manual = ManualMode()
        val manualStack = ScreenStack(manual)
        val otherStack = ScreenStack(object : Screen<Unit>(Unit) {
            @Composable override fun Content() { Text("Other mode") }
        })
        val stacks = listOf(manualStack, otherStack)
        var activeStack by mutableStateOf(manualStack)
        setContent { ScreenStackHost(stacks, activeStack) }

        onNodeWithText("Open manual details").performClick()
        onNodeWithText("Temporary notes").performTextInput("Keep this note")
        runOnIdle { activeStack = otherStack }
        onNodeWithText("Other mode").assertExists()
        runOnIdle { activeStack = manualStack }
        onNodeWithText("Keep this note").assertExists()

        onNodeWithText("Back to Manual").performClick()
        runOnIdle { assertSame(manual, manualStack.top) }
    }
}
