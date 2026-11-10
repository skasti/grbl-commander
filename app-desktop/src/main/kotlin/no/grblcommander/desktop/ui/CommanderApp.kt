package no.grblcommander.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.ManualModeScreen
import no.grblcommander.desktop.ui.navigation.MdiModeScreen
import no.grblcommander.desktop.ui.navigation.ModeScreenStacks
import no.grblcommander.desktop.ui.navigation.ModeScreenStacksHost

/** Stable UI context identities, separate from GRBL controller execution states. */
private enum class OperatorMode(val label: String) {
    Program("Program"), Manual("Manual"), Mdi("MDI"),
}

@Composable
fun CommanderApp() {
    // One owner per application window. Mode switches only change visibility.
    val modes = remember {
        ModeScreenStacks(OperatorMode.Program) { mode ->
            when (mode) {
                OperatorMode.Program -> WelcomeScreen()
                OperatorMode.Manual -> ManualModeScreen()
                OperatorMode.Mdi -> MdiModeScreen()
            }
        }
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        AppShell(
            state = ShellState.startup().copy(
                title = modes.activeMode.label,
                context = "In-memory UI demonstration · no machine controls",
            ),
            headerControls = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OperatorMode.entries.forEach { mode ->
                        TextButton(onClick = { modes.selectMode(mode) }) {
                            Text(if (modes.activeMode == mode) "• ${mode.label}" else mode.label)
                        }
                    }
                }
            },
        ) {
            ModeScreenStacksHost(modes)
        }
    }
}
