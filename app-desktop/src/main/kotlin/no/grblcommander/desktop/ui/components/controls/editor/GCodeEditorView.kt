package no.grblcommander.desktop.ui.components.controls.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** One-based source positions resolved by the caller. */
data class GCodeLineDecorations(val executingLine: Int? = null, val sentLine: Int? = null)

private val executionBackground = Color(0xFF20372A)
private val executionMarker = Color(0xFF93D6A3)
private val sentMarker = Color(0xFF8BD7FF)

private val numericWord = Regex("""[A-Za-z][+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)""")
private val numericBlock = Regex(""" *(?:${numericWord.pattern} *)+""")

private fun formatLine(line: String): String =
    if (numericBlock.matches(line)) numericWord.findAll(line).joinToString(" ") { it.value } else line

@Composable
fun GCodeEditor.Render(
    modifier: Modifier = Modifier,
    decorations: GCodeLineDecorations = GCodeLineDecorations(),
) {
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val lines = source
    val selected = selectedLine
    LaunchedEffect(this, selected, lines.size) {
        if (lines.isNotEmpty()) list.scrollToItem(selected?.minus(1) ?: lines.lastIndex)
    }
    LaunchedEffect(this, selected, lines.size, keypadFocusRequest) {
        // Let the text field lay out its updated draft before starting a focus session.
        withFrameNanos { }
        focus.requestFocus()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("gcode-buffer")
                .pointerInput(this@Render) { detectTapGestures { selectLine(null) } },
            state = list,
        ) {
            items(lines.size) { index ->
                val lineNumber = index + 1
                val editing = selected == lineNumber
                val executing = showExecutingLine && decorations.executingLine == lineNumber
                val sent = showSentLine && decorations.sentLine == lineNumber
                val background = when {
                    editing -> MaterialTheme.colorScheme.secondaryContainer
                    executing -> executionBackground
                    else -> Color.Transparent
                }
                Row(
                    Modifier.fillMaxWidth().background(background)
                        .selectable(selected = editing) {
                            selectLine(if (editing) null else lineNumber)
                            focus.requestFocus()
                        }
                        .testTag("gcode-line-$lineNumber").padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Reserve the first column for future breakpoint markers.
                    Spacer(Modifier.width(16.dp))
                    Box(Modifier.width(16.dp)) {
                        when {
                            executing -> Text("•", Modifier.testTag("gcode-executing-line"),
                                color = executionMarker, fontFamily = FontFamily.Monospace)
                            sent -> Text("↳", Modifier.testTag("gcode-sent-line"),
                                color = sentMarker, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Text(lineNumber.toString())
                    val line = lines[index]
                    Text(if (autoFormat) formatLine(line) else line, fontFamily = FontFamily.Monospace)
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = ::edit,
            singleLine = true,
            readOnly = readOnly,
            label = { Text(selected?.let { "Edit line $it" } ?: "New line") },
            isError = error != null,
            supportingText = error?.let { message -> { Text(message) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.fillMaxWidth().testTag("gcode-input").focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Enter && event.key != Key.NumPadEnter) false
                    else {
                        if (event.type == KeyEventType.KeyUp) commit()
                        true
                    }
                },
        )
    }
}
