package no.grblcommander.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import no.grblcommander.core.connection.ControllerConnection
import no.grblcommander.core.connection.ControllerSettings
import no.grblcommander.desktop.generated.resources.Res
import no.grblcommander.desktop.generated.resources.grbl_commander_icon
import no.grblcommander.desktop.ui.CommanderApp
import org.jetbrains.compose.resources.painterResource

fun main() {
    val applicationJob = SupervisorJob()
    val scope = CoroutineScope(applicationJob + Dispatchers.Default)
    val connection = ControllerConnection(scope)
    val settings = ControllerSettings(connection)
    scope.launch(start = CoroutineStart.UNDISPATCHED) { settings.initialize() }
    try {
        // Let Compose return so finally can close and await the machine connection.
        application(exitProcessOnExit = false) {
            Window(
                onCloseRequest = ::exitApplication,
                title = "GRBL Commander",
                icon = painterResource(Res.drawable.grbl_commander_icon),
                state = rememberWindowState(width = 1680.dp, height = 1050.dp),
            ) {
                CommanderApp(connection, settings)
            }
        }
    } finally {
        scope.cancel()
        connection.close()
        runBlocking { connection.awaitClosed(); applicationJob.join() }
    }
}
