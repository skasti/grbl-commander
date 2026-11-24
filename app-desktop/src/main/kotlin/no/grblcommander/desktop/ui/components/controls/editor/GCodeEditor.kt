package no.grblcommander.desktop.ui.components.controls.editor

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.collections.immutable.toPersistentList
import no.grblcommander.desktop.ui.components.controls.KeypadAction
import no.grblcommander.desktop.ui.components.controls.editWithKeypad
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.RightSoftKeySlot
import no.grblcommander.machine.protocol.GrblProtocol

/**
 * Local editor state. [validate] returns an error message for a rejected user edit.
 * By default it checks GRBL line framing. Custom validators can call [validateGrblLine]
 * before applying additional constraints.
 * Caller-supplied source bypasses edit validation and is stored verbatim.
 */
class GCodeEditor(
    initialSource: List<String> = emptyList(),
    private val validate: (String) -> String? = Companion::validateGrblLine,
) {
    // Compare snapshot identities so edits deep in the list skip a full prefix comparison.
    private var lines by mutableStateOf(initialSource.toPersistentList(), referentialEqualityPolicy())

    /** Observable immutable snapshot. Reading it is constant time. */
    val source: List<String> get() = lines

    var readOnly by mutableStateOf(false)
    var selectedLine by mutableStateOf<Int?>(null)
        private set
    var autoFormat by mutableStateOf(true)
        private set
    var showExecutingLine by mutableStateOf(true)
        private set
    var showSentLine by mutableStateOf(true)
        private set

    internal val listState = LazyListState()

    internal var input by mutableStateOf(TextFieldValue())
        private set
    internal var error by mutableStateOf<String?>(null)
        private set
    internal var keypadFocusRequest by mutableStateOf(0)
        private set
    private var newLineDraft = TextFieldValue()

    val canSelectPrevious get() = (selectedLine ?: (lines.size + 1)) > 1
    val canSelectNext get() = selectedLine != null

    val softKeys = GCodeEditorSoftKeys(this)
    val bottomKeys = mapOf(
        BottomSoftKeySlot.F5 to softKeys.previousLine,
        BottomSoftKeySlot.F6 to softKeys.nextLine,
    )
    val rightKeys = mapOf(
        RightSoftKeySlot.R5 to softKeys.format,
        RightSoftKeySlot.R6 to softKeys.executing,
        RightSoftKeySlot.R7 to softKeys.sent,
    )

    /** Loads source from the first row and clears the row selection, draft and validation feedback. */
    fun replaceSource(source: List<String>) {
        lines = source.toPersistentList()
        selectedLine = null
        newLineDraft = TextFieldValue()
        updateDraft(TextFieldValue())
        listState.requestScrollToItem(0)
    }

    /** Selects a one-based source line, or restores the new-line draft when passed null. */
    fun selectLine(lineNumber: Int?) {
        if (lineNumber == selectedLine || (lineNumber != null && lineNumber !in 1..lines.size)) return
        if (selectedLine == null) newLineDraft = input
        selectedLine = lineNumber
        updateDraft(if (lineNumber == null) newLineDraft else {
            val text = lines[lineNumber - 1]
            TextFieldValue(text, TextRange(text.length))
        })
        listState.requestScrollToItem(lineNumber?.minus(1) ?: lines.lastIndex)
    }

    fun previousLine() {
        if (canSelectPrevious) selectLine(selectedLine?.minus(1) ?: lines.size)
    }

    fun nextLine() {
        val line = selectedLine ?: return
        selectLine((line + 1).takeIf { it <= lines.size })
    }

    fun toggleFormat() { autoFormat = !autoFormat }
    fun toggleExecutingLine() { showExecutingLine = !showExecutingLine }
    fun toggleSentLine() { showSentLine = !showSentLine }

    fun onKeypadAction(action: KeypadAction) {
        if (!readOnly) editWithKeypad(input, action, ::edit, ::commit)
        keypadFocusRequest++
    }

    internal fun edit(value: TextFieldValue) {
        if (!readOnly) updateDraft(value)
    }

    internal fun commit() {
        if (readOnly) return
        val text = input.text
        error = validate(text)
        if (error != null) return

        val line = selectedLine
        if (line == null) {
            lines = lines.adding(text)
            updateDraft(TextFieldValue())
            listState.requestScrollToItem(lines.lastIndex)
        } else {
            val index = line - 1
            if (lines[index] != text) lines = lines.replacingAt(index, text)
            selectLine(null)
        }
    }

    private fun updateDraft(value: TextFieldValue) {
        input = value
        error = null
    }

    companion object {
        fun validateGrblLine(line: String): String? = GrblProtocol.commandValidationError(line)
    }
}
