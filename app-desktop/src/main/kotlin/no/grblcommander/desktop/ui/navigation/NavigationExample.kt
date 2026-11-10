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

private val exampleFiles = listOf(
    ExampleFile("example-a.nc", "; Example A\n; Preview only"),
    ExampleFile("example-b.nc", "; Example B\n; Preview only"),
)

class EditorScreen : Screen {
    var draft by mutableStateOf("; Navigation example — no machine operations")
    var selectedFile by mutableStateOf<String?>(null)
        private set

    private fun loadExampleFile(filename: String) {
        val file = exampleFiles.singleOrNull { it.name == filename } ?: return
        selectedFile = file.name
        draft = file.contents
    }

    @Composable
    override fun Content(stack: ScreenStack) {
        val fileExplorer = rememberFileExplorer()
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Navigation example · in-memory files only")
                Text(selectedFile ?: "Unsaved draft")
                Button(onClick = {
                    fileExplorer.open(initialDirectory = "/programs") { result ->
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
                Button(onClick = { stack.dismiss(this@EditorScreen) }) { Text("Back to welcome") }
            }
        }
    }
}

class FileExplorerScreen(val initialDirectory: String) : Screen {
    val files: List<ExampleFile> = exampleFiles
    private var completion: ScreenResultHandle<FileExplorerResult>? = null

    internal fun bind(handle: ScreenResultHandle<FileExplorerResult>) {
        check(completion == null) { "File explorer is already bound" }
        completion = handle
    }

    /** The navigation stack guards top-of-stack identity and at-most-once delivery. */
    fun select(file: ExampleFile): Boolean {
        require(file in files) { "Select a file from this explorer" }
        return requireNotNull(completion) { "Open this screen through FileExplorer" }
            .complete(FileExplorerResult.Selected(file.name))
    }

    fun cancel(): Boolean = requireNotNull(completion) { "Open this screen through FileExplorer" }
        .complete(FileExplorerResult.Cancelled)

    @Composable
    override fun Content(stack: ScreenStack) {
        Surface(Modifier.padding(32.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose an example file")
                Text(initialDirectory)
                files.forEach { file ->
                    Button(onClick = { select(file) }) { Text(file.name) }
                }
                Button(onClick = { cancel() }) { Text("Cancel") }
            }
        }
    }
}
