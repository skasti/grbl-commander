package no.grblcommander.desktop.ui.components.controls.editor

import no.grblcommander.desktop.ui.navigation.SoftKeyDefinition

class GCodeEditorSoftKeys internal constructor(editor: GCodeEditor) {
    val previousLine = SoftKeyDefinition(
        "↑\nPrevious", action = editor::previousLine, enabled = { editor.canSelectPrevious },
    )
    val nextLine = SoftKeyDefinition(
        "↓\nNext", action = editor::nextLine, enabled = { editor.canSelectNext },
    )
    val format = SoftKeyDefinition(
        "💄\nFormat", action = editor::toggleFormat, active = { editor.autoFormat }, repeatable = false,
    )
    val executing = SoftKeyDefinition(
        "•\nExecuting", action = editor::toggleExecutingLine, active = { editor.showExecutingLine }, repeatable = false,
    )
    val sent = SoftKeyDefinition(
        "↳\nSent", action = editor::toggleSentLine, active = { editor.showSentLine }, repeatable = false,
    )
}
