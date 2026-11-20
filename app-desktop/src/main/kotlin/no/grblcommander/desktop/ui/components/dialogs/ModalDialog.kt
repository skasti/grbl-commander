package no.grblcommander.desktop.ui.components.dialogs

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.SoftKeyDefinition

/** Reusable workspace modal. The existing stack owns input isolation and result delivery. */
class ModalDialog<R>(
    private val title: String,
    private val message: String,
    cancellationResult: R,
    choices: Map<BottomSoftKeySlot, Choice<R>>,
) : Screen<R>(cancellationResult) {
    data class Choice<R>(val label: String, val result: R)

    init {
        require(choices.isNotEmpty()) { "A modal needs at least one softkey choice" }
        require(choices.values.all { it.label.isNotBlank() }) { "Modal choices need visible labels" }
    }

    // Neither bank inherits: actions behind a modal must not remain available.
    override val bottomKeys = choices.mapValues { (_, choice) ->
        SoftKeyDefinition(choice.label, action = { finish(choice.result) })
    }

    @Composable override fun Content() {
        BaseDialog(title) {
            Text(message)
        }
    }
}
