package no.grblcommander.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.screens.ManualModeScreen
import no.grblcommander.desktop.ui.screens.MdiModeScreen
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost
import no.grblcommander.desktop.ui.screens.WelcomeScreen

/** Stable UI context identities, separate from GRBL controller execution states. */
private enum class OperatorMode(val label: String) {
    Program("Program"), Manual("Manual"), Mdi("MDI"),
}

@Composable
fun CommanderApp() {
    // Each application window owns one persistent ScreenStack for each UI mode.
    val stacks = remember {
        mapOf(
            OperatorMode.Program to ScreenStack(WelcomeScreen()),
            OperatorMode.Manual to ScreenStack(ManualModeScreen()),
            OperatorMode.Mdi to ScreenStack(MdiModeScreen()),
        )
    }
    var activeMode by remember { mutableStateOf(OperatorMode.Program) }

    MaterialTheme(colorScheme = darkColorScheme()) {
        AppShell(
            state = ShellState.startup().copy(
                title = activeMode.label,
                context = "In-memory UI demonstration · no machine controls",
            ),
            currentStack = { stacks.getValue(activeMode) },
            headerControls = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OperatorMode.entries.forEach { mode ->
                        TextButton(onClick = { activeMode = mode }) {
                            Text(if (activeMode == mode) "• ${mode.label}" else mode.label)
                        }
                    }
                }
            },
        ) {
            ScreenStackHost(
                stacks = stacks.values.toList(),
                activeStack = stacks.getValue(activeMode),
            )
        }
    }
}
