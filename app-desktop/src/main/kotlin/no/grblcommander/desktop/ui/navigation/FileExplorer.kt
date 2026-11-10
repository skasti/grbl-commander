package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Result contract owned by the file explorer, not a generic navigation result. */
sealed interface FileExplorerResult {
    data class Selected(val filename: String) : FileExplorerResult
    data object Cancelled : FileExplorerResult
}

/**
 * Screen-specific opener captured from the calling screen's host composition.
 * No Navigator/ScreenStack is passed at the UI call site. Each host has its own context.
 */
class FileExplorer internal constructor(private val navigation: ScreenNavigationContext) {
    fun open(
        initialDirectory: String = "/programs",
        onResult: (FileExplorerResult) -> Unit,
    ) {
        val screen = FileExplorerScreen(initialDirectory)
        // The UI thread completes the handler before the next Compose frame observes the push.
        screen.bind(navigation.open(screen, FileExplorerResult.Cancelled, onResult))
    }
}

@Composable
fun rememberFileExplorer(): FileExplorer {
    val navigation = LocalScreenNavigationContext.current
    return remember(navigation) { FileExplorer(navigation) }
}
