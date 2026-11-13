package no.grblcommander.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.navigation.Screen

/** UI fixture only: no jog controls, transport connection or controller commands. */
class ManualMode : Screen<Unit>(Unit) {
    @Composable
    override fun Content() {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Manual mode · navigation demonstration only")
            Button(onClick = { ManualDetails().open(this@ManualMode) }) {
                Text("Open manual details")
            }
        }
    }
}

class ManualDetails : Screen<Unit>(Unit) {
    @Composable
    override fun Content() {
        var notes by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Manual details · no machine operations")
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Temporary notes") },
            )
            Button(onClick = { finish(Unit) }) { Text("Back to Manual") }
        }
    }
}
