package no.grblcommander.desktop.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.controls.*

/** One inline edit, owned by the form until Apply, Enter or Cancel. */
internal class ConnectionTextInput(initial: String, numeric: Boolean) {
    var value by mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    val keyboard = KeyClusterState(if (numeric) KeypadLayout("Numbers", gapsBeforeColumns = mapOf(3 to .60f), pages = listOf(
        listOf("7", "8", "9", "⌫", "4", "5", "6", "", "1", "2", "3", "", "", "0", "", "↵")
            .mapIndexedNotNull { index, text -> text.takeIf { it.isNotEmpty() }?.let { index to key(it) } }.toMap(),
    )) else KeypadLayout("Address", columns = 8, rows = 4, pages = listOf(
        "abcdefghijklmnopqrstuvwxyz.-:/_".map(Char::toString) + "⌫",
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123".map(Char::toString) + listOf("⌫", "↵"),
        "456789[]\\@%+~".map(Char::toString) + listOf("⌫", "↵"),
    ).map { page -> page.mapIndexed { i, text -> i to key(text) }.toMap() }))

    @Composable
    fun Field(label: String) {
        val focus = remember { FocusRequester() }
        val bringIntoView = remember { BringIntoViewRequester() }
        LaunchedEffect(Unit) {
            focus.requestFocus()
            bringIntoView.bringIntoView()
        }
        OutlinedTextField(
            value, { value = it }, singleLine = true,
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth()
                .focusRequester(focus).bringIntoViewRequester(bringIntoView).testTag("connection-field-input"),
            label = { Text(label) },
        )
    }

    fun handle(action: KeypadAction, apply: () -> Unit) {
        editWithKeypad(value, action, { value = it }, apply)
    }

    private fun key(text: String) = KeypadKey(text, when (text) {
        "⌫" -> KeypadAction.Backspace
        "↵" -> KeypadAction.Enter
        else -> KeypadAction.Insert(text)
    })
}
