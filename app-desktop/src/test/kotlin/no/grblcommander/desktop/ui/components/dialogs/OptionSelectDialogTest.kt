package no.grblcommander.desktop.ui.components.dialogs

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import no.grblcommander.desktop.ui.components.dialogs.OptionSelectDialog.Option
import no.grblcommander.desktop.ui.components.dialogs.OptionSelectDialog.Options
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@OptIn(ExperimentalTestApi::class)
class OptionSelectDialogTest {
    @Test fun touchReturnsTypedOptionAndF8ReturnsTypedCancellation() = runComposeUiTest {
        val root = object : Screen<Unit>(Unit) {
            @Composable override fun Content() { Text("Parent") }
        }
        val stack = ScreenStack(root)
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), { stack }) { ScreenStackHost(stack) }
            }
        }

        val results = mutableListOf<Int>()
        val dialog = OptionSelectDialog("Select preset", cancellationResult = -1) {
            Options(listOf(Option("Favorite", result = 42, icon = "★")))
        }
        val onResult: (Int) -> Unit = {
            assertSame(root, stack.top)
            results += it
        }

        runOnIdle { dialog.open(root, onResult) }
        onNodeWithText("1️⃣  ★  Favorite").performTouchInput { click() }
        runOnIdle {
            assertEquals(listOf(42), results)
            dialog.open(root, onResult)
        }
        onRoot().performKeyInput { pressKey(Key.F8) }
        runOnIdle { assertEquals(listOf(42, -1), results) }
    }
}
