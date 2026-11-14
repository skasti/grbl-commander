package no.grblcommander.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import no.grblcommander.desktop.generated.resources.Res
import no.grblcommander.desktop.generated.resources.grbl_commander_icon
import no.grblcommander.desktop.ui.CommanderApp
import org.jetbrains.compose.resources.painterResource

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "GRBL Commander",
        icon = painterResource(Res.drawable.grbl_commander_icon),
        state = rememberWindowState(width = 1280.dp, height = 760.dp),
    ) {
        CommanderApp()
    }
}
