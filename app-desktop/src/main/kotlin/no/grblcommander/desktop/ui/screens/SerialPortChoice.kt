package no.grblcommander.desktop.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import kotlinx.coroutines.CancellationException
import no.grblcommander.desktop.ui.components.dialogs.OptionSelectDialog
import no.grblcommander.desktop.ui.components.dialogs.OptionSelectDialog.Option
import no.grblcommander.desktop.ui.components.dialogs.OptionSelectDialog.Options
import no.grblcommander.machine.transport.DiscoveredSerialPort

/** Each open gets its own discovery request and returns the device path to the form. */
internal fun serialPortChoice(load: suspend () -> List<DiscoveredSerialPort>): OptionSelectDialog<String?> =
    OptionSelectDialog("Select serial port", cancellationResult = null) {
        try {
            Options(load().map { Option(label = it.label, result = it.device) }) {
                Text("No serial ports found. Use manual entry.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failedDiscovery(failure)
        } catch (failure: LinkageError) {
            failedDiscovery(failure)
        }
    }

private fun failedDiscovery(failure: Throwable): Options<Nothing> = Options(emptyList()) {
    Text("Discovery unavailable: ${failure.message}. Use manual entry.", color = MaterialTheme.colorScheme.error)
}
