package no.grblcommander.desktop.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import no.grblcommander.desktop.connection.ControllerSettings
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.SoftKeyDefinition

/** Empty utility root; the communication terminal is owned by issue #22. */
class UtilMode(private val settings: ControllerSettings) : Screen<Unit>(Unit) {
    override val bottomKeys = mapOf(
        BottomSoftKeySlot.F6 to SoftKeyDefinition("⌨\nUtil", action = {}, active = { true }),
        BottomSoftKeySlot.F7 to SoftKeyDefinition("🔗\nConnection", action = ::openConnection),
    )

    fun openConnection() {
        if (screenStack.top === this) ControllerConnectionSettings(settings).open(this)
    }

    @Composable override fun Content() {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = ::openConnection) { Text("Controller connection") }
            }
        }
    }
}
