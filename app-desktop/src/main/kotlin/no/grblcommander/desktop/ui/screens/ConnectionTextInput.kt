package no.grblcommander.desktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.controls.*
import no.grblcommander.desktop.ui.navigation.*

/** Explicit field input owner using the shared caret-aware keyboard renderer. */
internal class ConnectionTextInput(private val title: String, initial: String, numeric: Boolean = false) : Screen<String?>(null) {
    private var value by mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    private val keyboard = KeyClusterState(if (numeric) KeypadLayout("Numbers", pages = listOf(
        listOf("7", "8", "9", "⌫", "4", "5", "6", "", "1", "2", "3", "", "", "0", "", "↵")
            .mapIndexedNotNull { index, text -> text.takeIf { it.isNotEmpty() }?.let { index to key(it) } }.toMap(),
    )) else KeypadLayout("Address", columns = 8, rows = 4, spacing = .1f, pages = listOf(
        "abcdefghijklmnopqrstuvwxyz.-:/_".map(Char::toString) + "⌫",
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123".map(Char::toString) + listOf("⌫", "↵"),
        "456789[]\\@%+~".map(Char::toString) + listOf("⌫", "↵"),
    ).map { page -> page.mapIndexed { i, text -> i to key(text) }.toMap() }))
    override val bottomKeys = mapOf(
        BottomSoftKeySlot.F7 to SoftKeyDefinition("Apply", action = { finish(value.text) }, repeatable = false),
        BottomSoftKeySlot.F8 to SoftKeyDefinition("Cancel", action = { finish(null) }, repeatable = false),
    )
    @Composable override fun Content() {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value, { value = it }, singleLine = true, modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().focusRequester(focus), label = { Text(title) })
                KeyCluster(keyboard, 44.dp, onAction = { action ->
                    editWithKeypad(value, action, { value = it }, { finish(value.text) })
                })
            }
        }
    }
    private fun key(text: String) = KeypadKey(text, when (text) {
        "⌫" -> KeypadAction.Backspace
        "↵" -> KeypadAction.Enter
        else -> KeypadAction.Insert(text)
    })
}
