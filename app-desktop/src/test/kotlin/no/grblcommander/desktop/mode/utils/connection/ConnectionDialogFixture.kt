package no.grblcommander.desktop.mode.utils.connection

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.hostConnectionDialog(): Pair<ScreenStack, Screen<Unit>> {
    val root = object : Screen<Unit>(Unit) { @Composable override fun Content() { Text("Root") } }
    val stack = ScreenStack(root)
    setContent { MaterialTheme(colorScheme = darkColorScheme()) { AppShell(ShellState.startup(), { stack }) { ScreenStackHost(stack) } } }
    return stack to root
}
