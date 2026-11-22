package no.grblcommander.desktop.mode.mdi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.components.controls.*
import no.grblcommander.desktop.ui.navigation.*
import no.grblcommander.machine.protocol.GrblProtocol

class MdiMode : Screen<Unit>(Unit) {
    private val lines = mutableStateListOf<String>()
    private var selected by mutableStateOf<Int?>(null)
    private var input by mutableStateOf(TextFieldValue())
    private var newLineDraft = TextFieldValue()
    private var error by mutableStateOf<String?>(null)
    private val letters = KeyClusterState(Keymaps.code)
    private val numbers = KeyClusterState(Keymaps.numPlus)

    override val bottomKeys = mapOf(
        BottomSoftKeySlot.F5 to SoftKeyDefinition("↑\nPrevious", enabled = { (selected ?: lines.size) > 0 },
            action = { select((selected ?: lines.size) - 1) }),
        BottomSoftKeySlot.F6 to SoftKeyDefinition("↓\nNext", enabled = { selected != null },
            action = { selected?.let { select((it + 1).takeIf(lines.indices::contains)) } }),
    )

    private fun edit(value: TextFieldValue) {
        input = value
        error = null
    }

    private fun select(index: Int?) {
        if (selected == null) newLineDraft = input
        selected = index
        edit(if (index == null) newLineDraft else {
            val text = lines[index]
            TextFieldValue(text, TextRange(text.length))
        })
    }

    private fun save() {
        val text = input.text
        try {
            GrblProtocol.encodeCommand(text)
        } catch (failure: IllegalArgumentException) {
            error = failure.message
            return
        }
        val index = selected
        if (index == null) {
            lines.add(text)
            edit(TextFieldValue())
        } else {
            lines[index] = text
        }
    }

    @Composable
    override fun Content() {
        val focus = remember { FocusRequester() }
        val list = rememberLazyListState()
        LaunchedEffect(selected, lines.size) {
            focus.requestFocus()
            if (lines.isNotEmpty()) list.scrollToItem(selected ?: lines.lastIndex)
        }
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val keyboardWidth = minOf(400.dp, maxWidth * .40f) + keypadPanelMargin * 2f
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("MDI buffer", style = MaterialTheme.typography.titleMedium)
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                            items(lines.size) { index ->
                                val active = selected == index
                                Row(Modifier.fillMaxWidth()
                                    .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .selectable(selected = active) {
                                        select(if (active) null else index)
                                        focus.requestFocus()
                                    }
                                    .testTag("mdi-line-${index + 1}").padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text((index + 1).toString())
                                    Text(lines[index], fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = input, onValueChange = ::edit, singleLine = true,
                            label = { Text(selected?.let { "Edit line ${it + 1}" } ?: "New line") },
                            isError = error != null,
                            supportingText = error?.let { message -> { Text(message) } },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { save() }),
                            modifier = Modifier.fillMaxWidth().testTag("mdi-input").focusRequester(focus)
                                .onPreviewKeyEvent { event ->
                                    if (event.key != Key.Enter && event.key != Key.NumPadEnter) false
                                    else {
                                        if (event.type == KeyEventType.KeyUp) save()
                                        true
                                    }
                                },
                        )
                    }
                    GCodeKeyboard(
                        letters = letters, numbers = numbers,
                        onAction = { action ->
                            editWithKeypad(input, action, ::edit, ::save)
                            focus.requestFocus()
                        },
                        modifier = Modifier.widthIn(max = keyboardWidth).fillMaxHeight(),
                    )
                }
            }
        }
    }
}
