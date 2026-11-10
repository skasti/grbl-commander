package no.grblcommander.desktop.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A typed result from the in-memory file chooser. */
sealed interface FileExplorerResult {
    data class Selected(val filename: String) : FileExplorerResult
    data object Cancelled : FileExplorerResult
}

/** The file explorer is the screen; no facade, global navigator or filesystem. */
class FileExplorer(val initialDirectory: String = "/programs") :
    Screen<FileExplorerResult>(FileExplorerResult.Cancelled) {

    val files: List<ExampleFile> = exampleFiles

    fun selectFile(filename: String): Boolean {
        require(files.any { it.name == filename }) { "Select a file from this explorer" }
        return finish(FileExplorerResult.Selected(filename))
    }

    fun cancel(): Boolean = finish(FileExplorerResult.Cancelled)

    @Composable
    override fun Content() {
        Surface(Modifier.padding(32.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose an example file")
                Text(initialDirectory)
                files.forEach { file ->
                    Button(onClick = { selectFile(file.name) }) { Text(file.name) }
                }
                Button(onClick = { cancel() }) { Text("Cancel") }
            }
        }
    }
}
