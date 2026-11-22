package no.grblcommander.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import no.grblcommander.core.connection.ControllerConnection
import no.grblcommander.core.connection.ControllerSettings
import no.grblcommander.core.connection.description
import no.grblcommander.machine.session.MachineSnapshot.Connection
import no.grblcommander.desktop.mode.utils.terminal.Terminal
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.mode.manual.ManualMode
import no.grblcommander.desktop.mode.mdi.MdiMode
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost
import no.grblcommander.desktop.mode.program.Welcome

/** Stable UI context identities, separate from GRBL controller execution states. */
private enum class OperatorMode(val label: String) {
    Program("Program"), Manual("Manual"), Mdi("MDI"), Util("Util"),
}

@Composable
fun CommanderApp(connection: ControllerConnection, settings: ControllerSettings) {
    LaunchedEffect(settings) { settings.onUiReady() }
    val connectionState by connection.state.collectAsState(context = Dispatchers.Main)
    val now = rememberCurrentTime()
    // Each application window owns one persistent ScreenStack for each UI mode.
    val stacks = remember(connection) {
        mapOf(
            OperatorMode.Program to ScreenStack(Welcome()),
            OperatorMode.Manual to ScreenStack(ManualMode()),
            OperatorMode.Mdi to ScreenStack(MdiMode(connection)),
            OperatorMode.Util to ScreenStack(Terminal(settings)),
        )
    }
    var activeMode by remember { mutableStateOf(OperatorMode.Program) }

    CompositionLocalProvider(LocalControllerConnection provides connection) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            AppShell(
                state = ShellState.startup().copy(
                    title = if (activeMode == OperatorMode.Util) "Terminal / Connection" else activeMode.label,
                    context = connectionState.endpoint?.description() ?: "No controller endpoint selected",
                    connectionStatus = (if (connectionState.closing) "Closing" else connectionState.snapshot.connection.name) +
                        (if (!connectionState.closing && connectionState.snapshot.connection == Connection.CONNECTED &&
                            connectionState.reportIsStale(now)) " · stale" else "") +
                        (connectionState.snapshot.alarmCode?.let { " · ALARM:$it" }
                            ?: connectionState.snapshot.status?.executionState?.let { " · $it" }.orEmpty()),
                ),
                currentStack = { stacks.getValue(activeMode) },
                headerControls = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OperatorMode.entries.forEach { mode ->
                            TextButton(
                                onClick = { activeMode = mode },
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                    selected = activeMode == mode
                                    if (mode == OperatorMode.Util) contentDescription = "Util"
                                },
                            ) {
                                Text(if (mode == OperatorMode.Util) "⚙" else if (activeMode == mode) "• ${mode.label}" else mode.label,
                                    color = if (activeMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
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
