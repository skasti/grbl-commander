package no.grblcommander.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.RightSoftKeySlot
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.SoftKeyDefinition
import no.grblcommander.desktop.ui.navigation.SoftKeyModifier

/** In-memory fixture, not a filesystem adapter or an executable machine program. */
data class ExampleFile(val name: String, val contents: String)

internal val exampleFiles = listOf(
    ExampleFile("example-a.nc", "; Example A\n; Preview only"),
    ExampleFile("example-b.nc", "; Example B\n; Preview only"),
)

/** A typed result from the in-memory file chooser. */
sealed interface FileExplorerResult {
    data class Selected(val filename: String) : FileExplorerResult
    data object Cancelled : FileExplorerResult
}

/** The file explorer is the screen; no facade, global navigator or filesystem. */
class FileExplorer(val initialDirectory: String = "/programs") :
    Screen<FileExplorerResult>(FileExplorerResult.Cancelled) {

    val files: List<ExampleFile> =
        exampleFiles

    var selectedFile by mutableStateOf<String?>(null)
        private set
    private var showInfo by mutableStateOf(false)

    override val bottomKeys = mapOf(
        BottomSoftKeySlot.F1 to SoftKeyDefinition(
            "Select", action = ::selectCurrent, enabled = { selectedFile != null },
        ),
        BottomSoftKeySlot.F2 to SoftKeyDefinition(
            "Clear selection", action = { selectedFile = null }, enabled = { selectedFile != null },
        ),
        BottomSoftKeySlot.F8 to SoftKeyDefinition(
            "Cancel",
            action = { cancel() }),
    )
    override val inheritRightKeys = true

    // Do not expose editor navigation actions while the explorer covers it.
    private val editorActionUnavailable: () -> Boolean = { false }

    override val rightKeys = mapOf(
        RightSoftKeySlot.R1 to SoftKeyModifier {
            it.copy(label = "Editor unavailable", enabled = editorActionUnavailable)
        },
        RightSoftKeySlot.R2 to SoftKeyDefinition.Blank,
        RightSoftKeySlot.R7 to SoftKeyDefinition(
            "Info", action = { showInfo = !showInfo }, active = { showInfo },
        ),
    )

    private fun selectCurrent() { selectedFile?.let { selectFile(it) } }

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
                Text(selectedFile?.let { "Selected: $it" } ?: "No file selected · F1 disabled")
                if (showInfo) Text("In-memory fixtures only · Shift+F7 toggles Info")
                files.forEach { file ->
                    Button(onClick = { selectedFile = file.name }) { Text(file.name) }
                }
                Button(onClick = ::selectCurrent, enabled = selectedFile != null) { Text("Select file") }
                Button(onClick = { cancel() }) { Text("Cancel") }
            }
        }
    }
}
