package no.grblcommander.desktop.ui.layout

/**
 * Presentation-only placeholders for the persistent shell.
 *
 * The slots intentionally have no actions: a machine-control command must never be
 * implied by a decorative button. A typed action model belongs to later UI work.
 */
data class SoftkeySlot(val shortcut: String, val label: String = "—")

data class ShellState(
    val title: String,
    val context: String,
    val connectionStatus: String,
    val bottomKeys: List<SoftkeySlot>,
    val rightKeys: List<SoftkeySlot>,
) {
    companion object {
        fun startup() = ShellState(
            title = "Startskjerm",
            context = "GRBL Commander / oppstart",
            connectionStatus = "Ingen maskin tilkoblet",
            bottomKeys = (1..8).map { SoftkeySlot("F$it") },
            rightKeys = (1..7).map { SoftkeySlot("R$it") },
        )
    }
}
