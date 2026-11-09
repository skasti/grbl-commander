package no.grblcommander.desktop.ui

import androidx.compose.runtime.Composable
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState

@Composable
fun CommanderApp() {
    AppShell(state = ShellState.startup()) {
        WelcomeScreen()
    }
}
