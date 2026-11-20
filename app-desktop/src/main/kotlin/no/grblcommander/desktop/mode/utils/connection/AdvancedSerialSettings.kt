package no.grblcommander.desktop.mode.utils.connection

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.grblcommander.core.connection.ControllerEndpointDraft
import no.grblcommander.desktop.ui.components.dialogs.ModalDialog
import no.grblcommander.desktop.ui.navigation.*
import no.grblcommander.machine.transport.SerialConfiguration

/** Local edits are returned only through F7; the parent owns persistence. */
internal class AdvancedSerialSettings(private val original: ControllerEndpointDraft) : Screen<ControllerEndpointDraft?>(null) {
    private var draft by mutableStateOf(original)
    override val bottomKeys get() = mapOf(
        BottomSoftKeySlot.F1 to SoftKeyDefinition("Data bits: ${draft.dataBits}", action = {
            val current = draft.dataBits.toIntOrNull()?.takeIf { it in 5..8 } ?: 8
            draft = draft.copy(dataBits = (if (current == 8) 5 else current + 1).toString())
        }, repeatable = false),
        BottomSoftKeySlot.F2 to SoftKeyDefinition("Stop bits: ${draft.stopBits}", action = { draft = draft.copy(stopBits = next(SerialConfiguration.StopBits.entries, draft.stopBits)) }, repeatable = false),
        BottomSoftKeySlot.F3 to SoftKeyDefinition("Parity: ${draft.parity}", action = { draft = draft.copy(parity = next(SerialConfiguration.Parity.entries, draft.parity)) }, repeatable = false),
        BottomSoftKeySlot.F4 to SoftKeyDefinition("Flow: ${draft.flowControl}", action = { draft = draft.copy(flowControl = next(SerialConfiguration.FlowControl.entries, draft.flowControl)) }, repeatable = false),
        BottomSoftKeySlot.F5 to SoftKeyDefinition("DTR: ${if (draft.dtr) "On" else "Off"}", action = { draft = draft.copy(dtr = !draft.dtr) }, active = { draft.dtr }, repeatable = false),
        BottomSoftKeySlot.F6 to SoftKeyDefinition("RTS: ${if (draft.rts) "On" else "Off"}", action = { draft = draft.copy(rts = !draft.rts) }, active = { draft.rts }, repeatable = false),
        BottomSoftKeySlot.F7 to SoftKeyDefinition("Save ☑️", action = { finish(draft) }, repeatable = false, tone = SoftKeyTone.POSITIVE),
        BottomSoftKeySlot.F8 to SoftKeyDefinition("Cancel", action = ::cancel, repeatable = false),
    )
    private fun cancel() {
        if (draft == original) finish(null)
        else ModalDialog("Discard serial edits?", "Unsaved advanced settings will be discarded.", false, mapOf(
            BottomSoftKeySlot.F1 to ModalDialog.Choice("Discard", true),
            BottomSoftKeySlot.F8 to ModalDialog.Choice("Keep editing", false),
        )).open(this) { if (it) finish(null) }
    }
    @Composable override fun Content() {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Advanced serial settings", style = MaterialTheme.typography.titleLarge)
                Text("${draft.dataBits} data bits • ${draft.stopBits} stop bits • ${draft.parity} parity")
                Text("Flow control: ${draft.flowControl} • DTR ${draft.dtr} • RTS ${draft.rts}")
                Text("Use F1–F6 to change values. Save applies them to the connection draft.")
                Text("DTR/RTS set the requested levels on open. They do not request a controller reset.")
            }
        }
    }
}

private fun <T> next(values: List<T>, value: T): T = values[(values.indexOf(value) + 1) % values.size]
