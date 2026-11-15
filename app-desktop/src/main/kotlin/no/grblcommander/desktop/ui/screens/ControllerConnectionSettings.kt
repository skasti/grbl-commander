package no.grblcommander.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import no.grblcommander.core.connection.ControllerEndpointDraft
import no.grblcommander.core.connection.ControllerSettings
import no.grblcommander.core.connection.description
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.rememberCurrentTime
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.SoftKeyDefinition
import no.grblcommander.machine.transport.DiscoveredSerialPort
import no.grblcommander.machine.transport.SerialConfiguration
import no.grblcommander.machine.session.MachineSnapshot.Connection

/** Form and screen-local confirmations; all lifecycle actions use the shared application owner. */
class ControllerConnectionSettings(private val settings: ControllerSettings) : Screen<Unit>(Unit) {
    internal var draft by mutableStateOf(ControllerEndpointDraft())
    private var loaded by mutableStateOf(false)
    private var operation by mutableStateOf<Operation?>(null)
    private var handshakeComplete = false
    private var scope: CoroutineScope? = null
    private var message by mutableStateOf<String?>(null)
    private var error by mutableStateOf<String?>(null)
    private var advanced by mutableStateOf(false)
    private var ports by mutableStateOf<List<DiscoveredSerialPort>>(emptyList())
    private var discoveryError by mutableStateOf<String?>(null)
    private var refreshing by mutableStateOf(false)
    private var confirmation by mutableStateOf<Action?>(null)
    private var confirmedDraft: ControllerEndpointDraft? = null
    private enum class Action { SAVE, CONNECT, DISCONNECT }
    private data class Operation(val action: Action, val job: Job)
    private val busy get() = !loaded || operation != null
    private val canDisconnect get() = loaded && confirmation == null &&
        (operation == null || operation?.action == Action.CONNECT)

    override val bottomKeys = mapOf(
        BottomSoftKeySlot.F1 to SoftKeyDefinition("Save", action = ::save, enabled = { !busy && confirmation == null }),
        BottomSoftKeySlot.F2 to SoftKeyDefinition("Connect", action = { request(Action.CONNECT) }, enabled = { !busy && confirmation == null }),
        BottomSoftKeySlot.F3 to SoftKeyDefinition("Disconnect", action = { request(Action.DISCONNECT) }, enabled = { canDisconnect }),
        BottomSoftKeySlot.F7 to SoftKeyDefinition("⌨\nUtil", action = ::close, enabled = { confirmation == null }),
        BottomSoftKeySlot.F8 to SoftKeyDefinition("🔗\nConnection", action = {}, active = { true }),
    )
    // No right-key inheritance: future Terminal traffic filters are inactive while this screen is topmost.

    private fun close() { if (confirmation == null) finish(Unit) }

    private fun perform(action: Action, block: suspend () -> Unit) {
        if (operation != null && !(action == Action.DISCONNECT && canDisconnect)) return
        if (action == Action.CONNECT) handshakeComplete = false
        error = null
        message = null
        val job = scope?.launch(start = CoroutineStart.LAZY) {
            try { block() }
            catch (failure: TimeoutCancellationException) {
                error = "Controller handshake timed out. Check the endpoint and controller firmware/status response. ${failure.message.orEmpty()}"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = when (failure) {
                    is UnknownHostException, is UnresolvedAddressException -> "Host not found. Check the hostname or IP address."
                    is ConnectException -> "TCP connection failed. Check the controller address, port and network. ${failure.message.orEmpty()}"
                    else -> failure.message ?: failure.javaClass.simpleName
                }
            } catch (failure: LinkageError) {
                error = "Serial driver unavailable. Check the jSerialComm native library and device drivers. ${failure.message.orEmpty()}"
            } finally { if (operation?.job === currentCoroutineContext()[Job]) operation = null }
        }
        operation = job?.let { Operation(action, it) }
        job?.start()
    }

    private fun save() {
        if (busy || confirmation != null) return
        val entered = draft
        perform(Action.SAVE) { settings.save(entered); message = "Settings saved. The current connection is unchanged." }
    }

    private fun request(action: Action) {
        if (!loaded || confirmation != null || (busy && action == Action.CONNECT) ||
            (action == Action.DISCONNECT && !canDisconnect)) return
        if (action == Action.CONNECT) {
            try { draft.endpoint() } catch (failure: IllegalArgumentException) { error = failure.message; return }
        }
        if (settings.requiresInterruptionConfirmation || operation?.action == Action.CONNECT) {
            confirmedDraft = draft
            confirmation = action
        } else execute(action, draft, false)
    }

    private fun execute(action: Action, entered: ControllerEndpointDraft, confirmed: Boolean) {
        if (action == Action.DISCONNECT) {
            if (!canDisconnect) return
            // Disconnect must be able to interrupt a pending connect/handshake rather than queue behind it.
            val pending = operation?.job
            perform(Action.DISCONNECT) {
                // After handshake, Connect may be saving. Let that save finish without cancellation.
                if (handshakeComplete)
                    pending?.join()
                else pending?.cancelAndJoin()
                settings.disconnect(confirmed)
                message = "Disconnected."
            }
        } else perform(Action.CONNECT) {
            settings.connect(entered, confirmed, onHandshakeComplete = { handshakeComplete = true })
            message = "Controller connected and settings saved."
        }
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        scope?.launch {
            try {
                settings.discoverSerialPorts().fold(
                    onSuccess = { ports = it; discoveryError = null },
                    onFailure = { ports = emptyList(); discoveryError = "Port discovery unavailable: ${it.message}. Enter the device manually." },
                )
            } finally { refreshing = false }
        }
    }

    @Composable override fun Content() {
        val compositionScope = rememberCoroutineScope()
        DisposableEffect(compositionScope) {
            scope = compositionScope
            onDispose { scope = null }
        }
        LaunchedEffect(Unit) {
            try { draft = settings.load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "Could not load settings: ${failure.message}" }
            finally { loaded = true }
            refresh()
        }
        val state by settings.connection.state.collectAsState()
        val now = rememberCurrentTime()
        val editable = !busy && confirmation == null
        val disconnecting = state.closing || operation?.action == Action.DISCONNECT
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Controller connection", style = MaterialTheme.typography.headlineSmall)
                Text(if (disconnecting) "Closing connection…" else "Controller: ${state.snapshot.connection}")
                Text("Current endpoint: ${state.endpoint?.description() ?: "None"}")
                if (!disconnecting) state.snapshot.failure?.let { Text("Last connection error: $it", color = MaterialTheme.colorScheme.error) }
                state.snapshot.status?.let { Text("Last machine state: ${it.executionState}") }
                if (disconnecting) Text("Disconnecting…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else when (state.snapshot.connection) {
                    Connection.DISCONNECTED, Connection.CLOSED -> Text(
                        "Disconnected. Machine state is not being updated.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Connection.CONNECTING -> Text("Waiting for controller status…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> if (state.reportIsStale(now)) Text(
                        "Machine state is stale or unavailable. Do not treat retained values as live.", color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (busy) CircularProgressIndicator()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ControllerEndpointDraft.Transport.entries.forEach { transport ->
                        TextButton(onClick = { draft = draft.copy(transport = transport) }, enabled = editable) {
                            Text((if (draft.transport == transport) "● " else "") + if (transport == ControllerEndpointDraft.Transport.TCP) "TCP / network" else "Serial / USB")
                        }
                    }
                }
                if (draft.transport == ControllerEndpointDraft.Transport.TCP) {
                    Field("Hostname / IP address", draft.host, editable) { draft = draft.copy(host = it) }
                    Field("TCP port (1–65535)", draft.tcpPort, editable) { draft = draft.copy(tcpPort = it) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = ::refresh, enabled = !refreshing && editable) { Text(if (refreshing) "Refreshing…" else "Refresh serial ports") }
                        Text("Discovery is optional; manual entry always works.")
                    }
                    discoveryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!refreshing && ports.isEmpty() && discoveryError == null) Text("No serial ports found. Enter a port or device path below.")
                    ports.forEach { port ->
                        TextButton(onClick = { draft = draft.copy(serialPort = port.device) }, enabled = editable) { Text(port.label) }
                    }
                    Field("Device path / port name (COM3, /dev/ttyACM0…)", draft.serialPort, editable) { draft = draft.copy(serialPort = it) }
                    Field("Baud rate", draft.baudRate, editable) { draft = draft.copy(baudRate = it) }
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced serial options" else "Advanced serial options") }
                    if (advanced) {
                        Field("Data bits (5–8)", draft.dataBits, editable) { draft = draft.copy(dataBits = it) }
                        Choices("Stop bits", SerialConfiguration.StopBits.entries, draft.stopBits, editable) { draft = draft.copy(stopBits = it) }
                        Choices("Parity", SerialConfiguration.Parity.entries, draft.parity, editable) { draft = draft.copy(parity = it) }
                        Choices("Flow control", SerialConfiguration.FlowControl.entries, draft.flowControl, editable) { draft = draft.copy(flowControl = it) }
                        Row {
                            Checkbox(draft.dtr, onCheckedChange = { draft = draft.copy(dtr = it) }, enabled = editable); Text("DTR")
                            Checkbox(draft.rts, onCheckedChange = { draft = draft.copy(rts = it) }, enabled = editable); Text("RTS")
                        }
                        Text("Control lines use only these explicit values when connecting. Some USB/serial hardware may reset on opening; verify its behavior.")
                    }
                    Text("If connection fails, check that the device exists, is not in use and your account has serial-port permission.")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                message?.let { Text(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = ::save, enabled = editable) { Text("Save") }
                    Button(onClick = { request(Action.CONNECT) }, enabled = editable) { Text("Connect") }
                    Button(onClick = { request(Action.DISCONNECT) }, enabled = canDisconnect) { Text("Disconnect") }
                    TextButton(onClick = ::close, enabled = confirmation == null) { Text("Close") }
                }
            }
        }
        confirmation?.let { action ->
            AlertDialog(
                onDismissRequest = { confirmation = null },
                title = { Text("Interrupt current connection?") },
                text = { Text("This closes the current controller session. Pending commands fail and machine execution may continue with an unknown outcome. Nothing is automatically stopped, reset or replayed.") },
                confirmButton = { TextButton(onClick = {
                    val entered = confirmedDraft ?: draft
                    confirmation = null
                    execute(action, entered, true)
                }) { Text(if (action == Action.CONNECT) "Disconnect and connect" else "Disconnect") } },
                dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable private fun Field(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable private fun <T> Choices(label: String, values: List<T>, selected: T, enabled: Boolean, change: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label)
        values.forEach { value -> TextButton(onClick = { change(value) }, enabled = enabled) { Text("${if (selected == value) "● " else ""}$value") } }
    }
}
