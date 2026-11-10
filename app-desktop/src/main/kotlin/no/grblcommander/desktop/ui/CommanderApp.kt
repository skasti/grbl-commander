package no.grblcommander.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState

@Composable
fun CommanderApp() {
    val stack = remember { ScreenStack(WelcomeScreen()) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        AppShell(state = ShellState.startup()) {
            ScreenStackHost(stack)
        }
    }
}

