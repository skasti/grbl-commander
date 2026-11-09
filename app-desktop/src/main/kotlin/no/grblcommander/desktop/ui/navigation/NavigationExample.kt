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

class EditorScreen : Screen {
    var draft by mutableStateOf("; Navigation example — no machine operations")
    var selectedFile by mutableStateOf<String?>(null)
        private set

    fun openFileExplorer(stack: ScreenStack) {
        stack.push(FileExplorerScreen { file ->
            if (file != null) {
                selectedFile = file.name
                draft = file.contents
            }
        })
    }

    @Composable
    override fun Content(stack: ScreenStack) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Navigation example · in-memory files only")
                Text(selectedFile ?: "Unsaved draft")
                Button(onClick = { openFileExplorer(stack) }) { Text("Open example file") }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Editor draft") },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { stack.dismiss(this@EditorScreen) }) { Text("Back to welcome") }
            }
        }
    }
}

class FileExplorerScreen(
    private val onResult: (ExampleFile?) -> Unit,
) : Screen {
    val files = listOf(
        ExampleFile("example-a.nc", "; Example A\n; Preview only"),
        ExampleFile("example-b.nc", "; Example B\n; Preview only"),
    )

    fun select(stack: ScreenStack, file: ExampleFile) {
        require(file in files) { "Select a file from this explorer" }
        finish(stack, file)
    }

    fun cancel(stack: ScreenStack) = finish(stack, null)

    private fun finish(stack: ScreenStack, file: ExampleFile?) {
        // Deliver only once and only while this explorer is topmost.
        if (stack.dismiss(this)) onResult(file)
    }

    @Composable
    override fun Content(stack: ScreenStack) {
        Surface(Modifier.padding(32.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose an example file")
                files.forEach { file ->
                    Button(onClick = { select(stack, file) }) { Text(file.name) }
                }
                Button(onClick = { cancel(stack) }) { Text("Cancel") }
            }
        }
    }
}
