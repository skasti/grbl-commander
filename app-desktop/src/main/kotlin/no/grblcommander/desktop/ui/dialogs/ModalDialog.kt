package no.grblcommander.desktop.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
