package no.grblcommander.desktop.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import no.grblcommander.desktop.ui.controls.KeypadPanel
import no.grblcommander.desktop.ui.controls.keypadPanelMargin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import no.grblcommander.desktop.ui.dialogs.ModalDialog
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.rememberCurrentTime
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.RightSoftKeySlot
import no.grblcommander.desktop.ui.navigation.SoftKeyDefinition
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
    private var confirmation by mutableStateOf<Action?>(null)
    private enum class Action { SAVE, CONNECT, DISCONNECT, RESTORE }
    private data class Operation(val action: Action, val job: Job)
    private val busy get() = !loaded || operation != null
    private val canDisconnect get() = loaded && confirmation == null &&
        (operation == null || operation?.action == Action.CONNECT)

    private enum class Field(val label: String, val numeric: Boolean = false) {
        HOST("Hostname / IP address"), PORT("TCP port (1–65535)", numeric = true),
        DEVICE("Device path / port name"), BAUD("Baud rate (custom entry)", numeric = true),
    }

    private data class Edit(val field: Field, val input: ConnectionTextInput)
    private var edit by mutableStateOf<Edit?>(null)
    private val formFocus = FocusRequester()
    private val editKeys = mapOf(
        BottomSoftKeySlot.F7 to SoftKeyDefinition("Apply", action = ::applyEdit, repeatable = false),
        BottomSoftKeySlot.F8 to SoftKeyDefinition("Cancel", action = ::endEdit, repeatable = false),
    )

    private var connectionPresent by mutableStateOf(false)
    override val bottomKeys get() = if (edit != null) editKeys else formKeys
    private val formKeys get() = buildMap {
        val serial = draft.transport == ControllerEndpointDraft.Transport.SERIAL
        fun key(slot: BottomSoftKeySlot, label: String, action: () -> Unit) {
            put(slot, SoftKeyDefinition(label, action = action, enabled = { !busy && confirmation == null }, repeatable = false))
        }
        key(BottomSoftKeySlot.F1, if (serial) "Serial / TCP" else "TCP / Serial") {
            if (!busy) draft = draft.copy(transport = if (serial) ControllerEndpointDraft.Transport.TCP else ControllerEndpointDraft.Transport.SERIAL)
        }
        key(BottomSoftKeySlot.F2, if (serial) "Select Port 🔗" else "Hostname / IP") { if (serial) selectPort() else beginEdit(Field.HOST) }
        key(BottomSoftKeySlot.F3, if (serial) "Select Baud 📈" else "Port") { if (serial) selectBaud() else beginEdit(Field.PORT) }
        if (serial) key(BottomSoftKeySlot.F4, "Advanced 🎛️") {
            AdvancedSerialSettings(draft).open(this@ControllerConnectionSettings) { it?.let { changed -> draft = changed } }
        }
        key(BottomSoftKeySlot.F5, "Post-Connect Actions") {
            PostConnectSettings(draft.postConnect).open(this@ControllerConnectionSettings) { it?.let { actions -> draft = draft.copy(postConnect = actions) } }
        }
        put(BottomSoftKeySlot.F6, SoftKeyDefinition(if (connectionPresent || operation?.action == Action.CONNECT) "Disconnect" else "Connect",
            action = { request(if (connectionPresent || operation?.action == Action.CONNECT) Action.DISCONNECT else Action.CONNECT) },
            enabled = { if (connectionPresent || operation?.action == Action.CONNECT) canDisconnect else !busy && confirmation == null }, repeatable = false))
        key(BottomSoftKeySlot.F7, "Save", ::save)
        key(BottomSoftKeySlot.F8, "Reset", ::restore)
    }
    private fun fieldValue(field: Field) = when (field) {
        Field.HOST -> draft.host
        Field.PORT -> draft.tcpPort
        Field.DEVICE -> draft.serialPort
        Field.BAUD -> draft.baudRate
    }

    private fun beginEdit(field: Field) {
        if (!busy && confirmation == null && edit == null) {
            edit = Edit(field, ConnectionTextInput(fieldValue(field), field.numeric))
        }
    }

    private fun applyEdit() {
        val current = edit ?: return
        val value = current.input.value.text
        draft = when (current.field) {
            Field.HOST -> draft.copy(host = value)
            Field.PORT -> draft.copy(tcpPort = value)
            Field.DEVICE -> draft.copy(serialPort = value)
            Field.BAUD -> draft.copy(baudRate = value)
        }
        endEdit()
    }

    private fun endEdit() {
        // Move focus before removing the text field so physical softkeys still reach the shell.
        formFocus.requestFocus()
        edit = null
    }

    @Composable
    private fun FormField(field: Field, editable: Boolean) {
        val current = edit
        if (current?.field == field) {
            key(current) { current.input.Field(field.label) }
        } else {
            Entry(field.label, fieldValue(field), editable && current == null) { beginEdit(field) }
        }
    }

    private fun selectPort() {
        if (busy) return
        ConnectionChoice("Select serial port") {
            settings.discoverSerialPorts().getOrThrow().map { it.device to it.label }
        }.open(this) { it?.let { port -> draft = draft.copy(serialPort = port) } }
    }
    private fun selectBaud() {
        if (busy) return
        ConnectionChoice("Select baud rate") {
            listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600).map { it.toString() to it.toString() }
        }.open(this) { it?.let { baud -> draft = draft.copy(baudRate = baud) } }
    }
    private fun restore() {
        if (busy || confirmation != null) return
        val saved = settings.savedDraft.value
        if (draft == saved) return
        confirmation = Action.RESTORE
        ModalDialog("Restore saved settings?", "Unsaved connection edits will be discarded. This does not reset the controller.", false, mapOf(
            BottomSoftKeySlot.F1 to ModalDialog.Choice("Restore", true),
            BottomSoftKeySlot.F8 to ModalDialog.Choice("Keep editing", false),
        )).open(this) { confirmed -> confirmation = null; if (confirmed) draft = saved }
    }
    override val rightKeys = mapOf(
        RightSoftKeySlot.R7 to SoftKeyDefinition(
            "⌨\nTerminal", action = ::close, enabled = { confirmation == null && edit == null }, repeatable = false,
        ),
    )
    // Neither bank inherits: covered Terminal actions remain inactive.

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
            val entered = draft
            confirmation = action
            ModalDialog(
                title = "Interrupt current connection?",
                message = "This closes the current controller session. Pending commands fail and machine execution may continue with an unknown outcome. Nothing is automatically stopped, reset or replayed.",
                cancellationResult = false,
                choices = mapOf(
                    BottomSoftKeySlot.F1 to ModalDialog.Choice(
                        if (action == Action.CONNECT) "Disconnect and connect" else "Disconnect", true,
                    ),
                    BottomSoftKeySlot.F8 to ModalDialog.Choice("Cancel", false),
                ),
            ).open(this) { confirmed ->
                confirmation = null
                if (confirmed) confirmReset(action, entered, true)
            }
        } else confirmReset(action, draft, false)
    }

    private fun confirmReset(action: Action, entered: ControllerEndpointDraft, interrupted: Boolean) {
        if (action != Action.CONNECT || !entered.postConnect.resetController) { execute(action, entered, interrupted); return }
        confirmation = Action.CONNECT
        ModalDialog("Reset controller after connecting?", "Ctrl+X interrupts controller work and invalidates pending commands. Reset does not unlock, home or resume the machine. Confirm this reset for this connection only.", false, mapOf(
            BottomSoftKeySlot.F1 to ModalDialog.Choice("Connect and reset", true),
            BottomSoftKeySlot.F8 to ModalDialog.Choice("Cancel", false),
        )).open(this) { consent -> confirmation = null; if (consent) execute(action, entered, interrupted, true) }
    }

    private fun execute(action: Action, entered: ControllerEndpointDraft, confirmed: Boolean, resetConfirmed: Boolean = false) {
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
            settings.connect(entered, confirmed, resetConfirmed, onHandshakeComplete = { handshakeComplete = true })
            message = "Controller connected and settings saved."
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
        }
        val state by settings.connection.state.collectAsState()
        connectionPresent = state.closing || state.snapshot.connection in setOf(Connection.CONNECTED, Connection.CONNECTING)
        val now = rememberCurrentTime()
        val editable = !busy && confirmation == null
        val disconnecting = state.closing || operation?.action == Action.DISCONNECT
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
                val keypadFraction = if (edit?.field?.numeric == false) .66f else .58f
                val keypadWidth = maxWidth * keypadFraction + keypadPanelMargin * 2f
                Row(Modifier.fillMaxSize().focusRequester(formFocus).focusable(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
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
                        Text(if (draft.transport == ControllerEndpointDraft.Transport.SERIAL) "Serial / USB" else "TCP / network", style = MaterialTheme.typography.titleMedium)
                        if (draft.transport == ControllerEndpointDraft.Transport.TCP) {
                            FormField(Field.HOST, editable)
                            FormField(Field.PORT, editable)
                        } else {
                            FormField(Field.DEVICE, editable)
                            FormField(Field.BAUD, editable)
                            Text("${draft.dataBits} data bits • ${draft.stopBits} stop bits • ${draft.parity} • ${draft.flowControl} • DTR ${draft.dtr} / RTS ${draft.rts}")
                            Text("F2 refreshes discovery. Manual entry works for undiscovered devices.")
                            Text("If connection fails, check device permissions and whether another program is using the port.")
                        }
                        if (state.postConnectPending && state.snapshot.connection == Connection.CONNECTED) Text("Running post-connect actions…")
                        state.postConnectResults.forEach { Text(it) }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        message?.let { Text(it) }
                    }
                    edit?.let { current ->
                        KeypadPanel(
                            current.input.keypads,
                            onAction = { current.input.handle(it, ::applyEdit) },
                            modifier = Modifier.widthIn(max = keypadWidth),
                        )
                    }
                }
            }
        }
    }
}

@Composable private fun Entry(label: String, value: String, enabled: Boolean, edit: () -> Unit) {
    OutlinedButton(onClick = edit, enabled = enabled, modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(min = 56.dp)) {
        Column(Modifier.fillMaxWidth()) { Text(label, style = MaterialTheme.typography.labelMedium); Text(value.ifEmpty { "Tap to enter" }) }
    }
}
