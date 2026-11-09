package no.grblcommander.desktop

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.BrandAssets
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import no.grblcommander.desktop.ui.CommanderApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "GRBL Commander",
        icon = painterResource(BrandAssets.ICON),
        state = rememberWindowState(width = 1280.dp, height = 760.dp),
    ) {
        CommanderApp()
    }
}
