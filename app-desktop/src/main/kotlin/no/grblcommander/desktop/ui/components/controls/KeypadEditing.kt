package no.grblcommander.desktop.ui.components.controls

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Caret-aware edits shared by inputs, never by the keyboard renderer. Enter is explicitly bound. */
fun editWithKeypad(
    value: TextFieldValue,
    action: KeypadAction,
    onValueChange: (TextFieldValue) -> Unit,
    onEnter: () -> Unit,
) {
    if (action == KeypadAction.Enter) { onEnter(); return }
    val start = value.selection.min
    val end = value.selection.max
    val from = if (action == KeypadAction.Backspace && start == end && start > 0)
        Character.offsetByCodePoints(value.text, start, -1) else start
    val inserted = (action as? KeypadAction.Insert)?.text.orEmpty()
    if (from == end && inserted.isEmpty()) return
    val text = value.text.replaceRange(from, end, inserted)
    // Finalize any IME composition when applying an explicit external edit.
    onValueChange(TextFieldValue(text, TextRange(from + inserted.length)))
}
