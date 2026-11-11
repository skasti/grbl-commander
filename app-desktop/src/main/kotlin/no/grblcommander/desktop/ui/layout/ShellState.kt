package no.grblcommander.desktop.ui.layout

/** Display-only header information; screens and stacks own softkey bindings. */
data class ShellState(
    val title: String,
    val context: String,
    val connectionStatus: String,
) {
    companion object {
        fun startup() = ShellState(
            title = "Startskjerm",
            context = "GRBL Commander / oppstart",
            connectionStatus = "Ingen maskin tilkoblet",
        )
    }
}
