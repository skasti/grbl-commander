package no.grblcommander.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.connection.ControllerConnection
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.screens.ManualMode
import no.grblcommander.desktop.ui.screens.MdiMode
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost
import no.grblcommander.desktop.ui.screens.Welcome

/** Stable UI context identities, separate from GRBL controller execution states. */
private enum class OperatorMode(val label: String) {
    Program("Program"), Manual("Manual"), Mdi("MDI"),
}

@Composable
fun CommanderApp(connection: ControllerConnection) {
    val connectionState by connection.state.collectAsState()
    // Each application window owns one persistent ScreenStack for each UI mode.
    val stacks = remember {
        mapOf(
            OperatorMode.Program to ScreenStack(Welcome()),
            OperatorMode.Manual to ScreenStack(ManualMode()),
            OperatorMode.Mdi to ScreenStack(MdiMode()),
        )
    }
    var activeMode by remember { mutableStateOf(OperatorMode.Program) }

    CompositionLocalProvider(LocalControllerConnection provides connection) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            AppShell(
                state = ShellState.startup().copy(
                    title = activeMode.label,
                    context = "In-memory UI demonstration · no machine controls",
                    connectionStatus = if (connectionState.closing) "Closing" else connectionState.snapshot.connection.name,
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
}
