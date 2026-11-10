package no.grblcommander.desktop.ui.navigation

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

/** In-memory fixture, not a filesystem adapter or an executable machine program. */
data class ExampleFile(val name: String, val contents: String)

internal val exampleFiles = listOf(
    ExampleFile("example-a.nc", "; Example A\n; Preview only"),
    ExampleFile("example-b.nc", "; Example B\n; Preview only"),
)

class EditorScreen : Screen<Unit>(Unit) {
    var draft by mutableStateOf("; Navigation example — no machine operations")
    var selectedFile by mutableStateOf<String?>(null)
        private set

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
                Button(onClick = {
                    FileExplorer("/programs").open(this@EditorScreen) { result ->
                        when (result) {
                            is FileExplorerResult.Selected -> loadExampleFile(result.filename)
                            FileExplorerResult.Cancelled -> Unit
                        }
                    }
                }) { Text("Open example file") }
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
