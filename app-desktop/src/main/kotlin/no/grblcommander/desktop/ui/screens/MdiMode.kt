package no.grblcommander.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

class MdiMode : Screen<Unit>(Unit) {
    @Composable
    override fun Content() {
        var buffer by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("MDI · local text buffer only (not executable)")
            OutlinedTextField(
                value = buffer,
                onValueChange = { buffer = it },
                label = { Text("Unsent text") },
            )
        }
    }
}