package no.grblcommander.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.RightSoftKeySlot
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.SoftKeyDefinition

class EditorScreen : Screen<Unit>(Unit) {
    var draft by mutableStateOf("; Navigation example — no machine operations")
    var selectedFile by mutableStateOf<String?>(null)
        private set

    override val bottomKeys = mapOf(
        BottomSoftKeySlot.F1 to SoftKeyDefinition("Open file", action = ::openFile),
        BottomSoftKeySlot.F8 to SoftKeyDefinition("Back", action = { finish(Unit) }),
    )
    // In-memory editor actions also demonstrate right-bank inheritance.
    override val rightKeys = mapOf(
        RightSoftKeySlot.R1 to SoftKeyDefinition("Open file", action = ::openFile),
        RightSoftKeySlot.R2 to SoftKeyDefinition("Back", action = { finish(Unit) }),
    )

    private fun openFile() {
        FileExplorer("/programs").open(this) { result ->
            when (result) {
                is FileExplorerResult.Selected -> loadExampleFile(result.filename)
                FileExplorerResult.Cancelled -> Unit
            }
        }
    }

    private fun loadExampleFile(filename: String) {
        val file = exampleFiles.singleOrNull { it.name == filename } ?: return
        selectedFile = file.name
        draft = file.contents
    }

    @Composable
    override fun Content() {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Navigation example · in-memory files only")
                Text(selectedFile ?: "Unsaved draft")
                Button(onClick = ::openFile) { Text("Open example file") }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Editor draft") },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { finish(Unit) }) { Text("Back to welcome") }
            }
        }
    }
}
