package no.grblcommander.desktop.ui.components.controls

/** Editing intentions only. The input owns focus, validation and Enter semantics. */
sealed interface KeypadAction {
    data class Insert(val text: String) : KeypadAction
    data object Backspace : KeypadAction
    data object Enter : KeypadAction
}
