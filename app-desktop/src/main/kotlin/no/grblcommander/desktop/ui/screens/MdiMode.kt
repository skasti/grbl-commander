package no.grblcommander.desktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import no.grblcommander.desktop.ui.components.controls.*
import no.grblcommander.desktop.ui.navigation.Screen

class MdiMode : Screen<Unit>(Unit) {
    @Composable
    override fun Content() {
        var buffer by remember { mutableStateOf(TextFieldValue()) }
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val keyboardWidth = minOf(400.dp, maxWidth * .40f) + keypadPanelMargin * 2f
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("MDI · local text buffer only (not executable)")
                        OutlinedTextField(
                            value = buffer,
                            onValueChange = { buffer = it },
                            label = { Text("Unsent text") },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                    GCodeKeyboard(
                        onAction = { action ->
                            editWithKeypad(buffer, action, { buffer = it }, {
                                editWithKeypad(buffer, KeypadAction.Insert("\n"), { buffer = it }, {})
                            })
                        },
                        modifier = Modifier.widthIn(max = keyboardWidth).fillMaxHeight(),
                    )
                }
            }
        }
    }
}
