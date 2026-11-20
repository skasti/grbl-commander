package no.grblcommander.desktop.mode.utils.connection

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.grblcommander.core.connection.PostConnectActions
import no.grblcommander.desktop.ui.navigation.*

internal class PostConnectSettings(original: PostConnectActions) : Screen<PostConnectActions?>(null) {
    private var draft by mutableStateOf(original)
    override val bottomKeys get() = mapOf(
        BottomSoftKeySlot.F1 to SoftKeyDefinition("Reset: ${if (draft.resetController) "On" else "Off"}", action = { draft = draft.copy(resetController = !draft.resetController) }, active = { draft.resetController }, repeatable = false),
        BottomSoftKeySlot.F2 to SoftKeyDefinition("Firmware: ${draft.firmwareInfo.command ?: "Off"}", action = {
            val values = PostConnectActions.FirmwareInfo.entries
            draft = draft.copy(firmwareInfo = values[(draft.firmwareInfo.ordinal + 1) % values.size])
        }, repeatable = false),
        BottomSoftKeySlot.F3 to SoftKeyDefinition("Parameters: ${if (draft.parameters) "On" else "Off"}", action = { draft = draft.copy(parameters = !draft.parameters) }, active = { draft.parameters }, repeatable = false),
        BottomSoftKeySlot.F4 to SoftKeyDefinition("Parser: ${if (draft.parserState) "On" else "Off"}", action = { draft = draft.copy(parserState = !draft.parserState) }, active = { draft.parserState }, repeatable = false),
        BottomSoftKeySlot.F7 to SoftKeyDefinition("Save & return", action = { finish(draft) }, repeatable = false, tone = SoftKeyTone.POSITIVE),
        BottomSoftKeySlot.F8 to SoftKeyDefinition("Cancel", action = { finish(null) }, repeatable = false),
    )
    @Composable override fun Content() {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Post-connect actions", style = MaterialTheme.typography.titleLarge)
                Text("After status handshake: optional reset, firmware info, parameters, parser state.")
                Text("Reset interrupts controller work and invalidates pending commands. Each manual connection requires confirmation. Startup never resets.", color = MaterialTheme.colorScheme.error)
                Text("Unsupported or timed-out queries remain visible. Queries are never replayed; realtime status stays available.")
                Text("Save applies choices to the parent draft; main Save/Connect persists them.")
            }
        }
    }
}
