package no.grblcommander.desktop.ui.components.controls

/** The displayed label is independent of the emitted action/text. */
data class KeypadKey(val label: String, val action: KeypadAction, val columnSpan: Int = 1)
