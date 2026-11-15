package no.grblcommander.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import no.grblcommander.desktop.connection.ControllerConnection

/** All retained modes share the application-owned command and observation boundary. */
val LocalControllerConnection = staticCompositionLocalOf<ControllerConnection> {
    error("Application controller connection was not provided")
}
