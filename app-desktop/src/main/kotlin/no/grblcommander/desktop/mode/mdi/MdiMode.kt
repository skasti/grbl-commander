package no.grblcommander.desktop.mode.mdi

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.grblcommander.core.connection.BufferState
import no.grblcommander.core.connection.ControllerConnection
import no.grblcommander.core.connection.ControllerConnectionState
import no.grblcommander.core.connection.Operation
import no.grblcommander.desktop.ui.components.dialogs.controllerResetDialog
import no.grblcommander.desktop.ui.rememberCurrentTime
import no.grblcommander.desktop.ui.components.controls.*
import no.grblcommander.desktop.ui.navigation.*
import no.grblcommander.machine.protocol.GrblProtocol
import no.grblcommander.machine.session.MachineSnapshot.Connection
import no.grblcommander.machine.session.RealtimeCommand

private val executionBackground = Color(0xFF20372A)
private val executionMarker = Color(0xFF93D6A3)
private val sentMarker = Color(0xFF8BD7FF)

private val numericWord = Regex("""[A-Za-z][+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)""")
private val numericBlock = Regex(""" *(?:${numericWord.pattern} *)+""")

private fun formatBufferLine(line: String): String =
    if (numericBlock.matches(line)) numericWord.findAll(line).joinToString(" ") { it.value } else line

class MdiMode(private val connection: ControllerConnection) : Screen<Unit>(Unit) {
    private val lines = mutableStateListOf<String>()
    private var selected by mutableStateOf<Int?>(null)
    private var input by mutableStateOf(TextFieldValue())
    private var newLineDraft = TextFieldValue()
    private var error by mutableStateOf<String?>(null)
    private val letters = KeyClusterState(Keymaps.code)
    private val numbers = KeyClusterState(Keymaps.numPlus)
    private var autoFormat by mutableStateOf(true)
    private var showExecutingLine by mutableStateOf(true)
    private var showSentLine by mutableStateOf(true)

    private var operation by mutableStateOf<Operation?>(null)
    private var connectionState by mutableStateOf(connection.state.value)
    private var now by mutableStateOf(Instant.now())
    private var scope: CoroutineScope? = null
    private var controlPending by mutableStateOf(false)
    private var controlError by mutableStateOf<String?>(null)
    private val connected get() = !connectionState.closing && connectionState.snapshot.connection == Connection.CONNECTED

    private val navigationKeys = mapOf(
        BottomSoftKeySlot.F5 to SoftKeyDefinition("↑\nPrevious", enabled = { (selected ?: lines.size) > 0 },
            action = { select((selected ?: lines.size) - 1) }),
        BottomSoftKeySlot.F6 to SoftKeyDefinition("↓\nNext", enabled = { selected != null },
            action = { selected?.let { select((it + 1).takeIf(lines.indices::contains)) } }),
    )

    override val rightKeys = mapOf(
        RightSoftKeySlot.R5 to SoftKeyDefinition(
            "💄\nFormat", action = { autoFormat = !autoFormat }, active = { autoFormat }, repeatable = false,
        ),
        RightSoftKeySlot.R6 to SoftKeyDefinition(
            "•\nExecuting", action = { showExecutingLine = !showExecutingLine },
            active = { showExecutingLine }, repeatable = false,
        ),
        RightSoftKeySlot.R7 to SoftKeyDefinition(
            "↳\nSent", action = { showSentLine = !showSentLine }, active = { showSentLine }, repeatable = false,
        ),
    )

    private val start = SoftKeyDefinition(
        "▶️\nCycle Start", action = ::startOperation, enabled = ::canStartOperation, repeatable = false,
    )
    private val resume = SoftKeyDefinition(
        "▶️\nResume (~)", action = { control { connection.resumeFeedHold() } }, enabled = ::canResume, repeatable = false,
    )
    private val controlKeys = navigationKeys + mapOf(
        BottomSoftKeySlot.F2 to SoftKeyDefinition(
            "⏸️\nFeed Hold (!)", action = { control { connection.sendRealtime(RealtimeCommand.FEED_HOLD) } },
            enabled = { connected && !controlPending && !connectionState.resetPending }, repeatable = false,
        ),
        BottomSoftKeySlot.F3 to SoftKeyDefinition(
            "🔄\nSoft Reset", action = ::confirmReset,
            enabled = { connected && !controlPending && !connectionState.resetPending &&
                !connectionState.snapshot.resetInProgress }, repeatable = false,
        ),
    )
    private val startKeys = controlKeys + (BottomSoftKeySlot.F1 to start)
    private val resumeKeys = controlKeys + (BottomSoftKeySlot.F1 to resume)
    override val bottomKeys get() =
        if (connectionState.snapshot.status?.executionState?.startsWith("Hold:") == true) resumeKeys else startKeys

    private fun canStartOperation(): Boolean = !controlPending && startBlockReason(connectionState, now) == null

    private fun startBlockReason(state: ControllerConnectionState, time: Instant): String? =
        state.commandBlockReason(time) ?: when {
            state.snapshot.alarmCode != null -> "Controller alarm blocks Cycle Start."
            state.snapshot.status?.executionState != "Idle" -> "Cycle Start requires the controller to be Idle."
            lines.isEmpty() -> "Press Enter to add a line before starting."
            else -> null
        }

    private fun canResume(): Boolean = !controlPending && connectionState.canCycleStart(now) &&
        connectionState.snapshot.status?.executionState == "Hold:0"

    private fun startOperation() {
        if (screenStack.top !== this || !canStartOperation()) return
        controlError = null
        try {
            operation = connection.startOperation(Operation.Source.MDI, "mdi", lines.toList())
        } catch (failure: Exception) {
            controlError = failure.message ?: "Unable to start MDI"
        }
    }

    private fun confirmReset() {
        if (!connected || screenStack.top !== this) return
        controllerResetDialog().open(this) { confirmed ->
            if (confirmed) control { connection.reset() }
        }
    }

    private fun control(action: suspend () -> Unit) {
        val activeScope = scope ?: return
        if (!connected || controlPending || screenStack.top !== this) return
        controlPending = true
        controlError = null
        // The Skiko composition dispatcher can resume inline on a controller thread.
        activeScope.launch(Dispatchers.Main) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                controlError = failure.message ?: "Controller action failed"
            } finally {
                controlPending = false
            }
        }
    }

    private fun Operation.Progress.isActive(): Boolean =
        phase == Operation.Phase.PREPARED || phase == Operation.Phase.RUNNING || phase == Operation.Phase.HELD

    private fun edit(value: TextFieldValue) {
        input = value
        error = null
    }

    private fun select(index: Int?) {
        if (selected == null) newLineDraft = input
        selected = index
        edit(if (index == null) newLineDraft else {
            val text = lines[index]
            TextFieldValue(text, TextRange(text.length))
        })
    }

    private fun save() {
        if (operation?.state?.value?.isActive() == true) return
        val text = input.text
        try {
            GrblProtocol.encodeCommand(text)
        } catch (failure: IllegalArgumentException) {
            error = failure.message
            return
        }
        val index = selected
        if (index == null) {
            lines.add(text)
            edit(TextFieldValue())
        } else {
            lines[index] = text
            select(null)
        }
    }

    @Composable
    override fun Content() {
        val currentConnection by connection.state.collectAsState(context = Dispatchers.Main)
        val time = rememberCurrentTime()
        SideEffect {
            connectionState = currentConnection
            now = time
        }
        val compositionScope = rememberCoroutineScope()
        DisposableEffect(compositionScope) {
            scope = compositionScope
            onDispose { scope = null }
        }
        val run = operation
        val progress = run?.state?.collectAsState(context = Dispatchers.Main)?.value
        val editingLocked = progress?.isActive() == true
        val executingLine = progress?.executingLineNumber?.takeUnless { currentConnection.reportIsStale(time) }
        val focus = remember { FocusRequester() }
        val list = rememberLazyListState()
        LaunchedEffect(selected, lines.size) {
            focus.requestFocus()
            if (lines.isNotEmpty()) list.scrollToItem(selected ?: lines.lastIndex)
        }
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val keyboardWidth = minOf(400.dp, maxWidth * .40f) + keypadPanelMargin * 2f
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("MDI buffer", style = MaterialTheme.typography.titleMedium)
                        progress?.let {
                            Text(operationStatus(it, executingLine), Modifier.testTag("mdi-operation-status"),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (!editingLocked && currentConnection.snapshot.status?.executionState?.startsWith("Hold:") != true) {
                            startBlockReason(currentConnection, time)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        controlError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth().testTag("mdi-buffer")
                                .pointerInput(Unit) {
                                    detectTapGestures { if (selected != null) select(null) }
                                },
                            state = list,
                        ) {
                            items(lines.size) { index ->
                                val editing = selected == index
                                val executing = showExecutingLine && executingLine == index + 1
                                val sent = showSentLine && progress?.lastSentLineNumber == index + 1 &&
                                    run?.lines?.getOrNull(index) == lines[index]
                                val background = when {
                                    editing -> MaterialTheme.colorScheme.secondaryContainer
                                    executing -> executionBackground
                                    else -> Color.Transparent
                                }
                                Row(Modifier.fillMaxWidth()
                                    .background(background)
                                    .selectable(selected = editing) {
                                        select(if (editing) null else index)
                                        focus.requestFocus()
                                    }
                                    .testTag("mdi-line-${index + 1}").padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.width(16.dp)) {
                                        if (sent) Text("↳", Modifier.testTag("mdi-sent-line"),
                                            color = sentMarker, fontFamily = FontFamily.Monospace)
                                    }
                                    Box(Modifier.width(16.dp)) {
                                        if (executing) Text("•", Modifier.testTag("mdi-executing-line"),
                                            color = executionMarker, fontFamily = FontFamily.Monospace)
                                    }
                                    Text((index + 1).toString())
                                    val line = lines[index]
                                    Text(if (autoFormat) formatBufferLine(line) else line, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = input, onValueChange = { value ->
                                if (operation?.state?.value?.isActive() != true) edit(value)
                            }, singleLine = true, readOnly = editingLocked,
                            label = { Text(selected?.let { "Edit line ${it + 1}" } ?: "New line") },
                            isError = error != null,
                            supportingText = error?.let { message -> { Text(message) } },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { save() }),
                            modifier = Modifier.fillMaxWidth().testTag("mdi-input").focusRequester(focus)
                                .onPreviewKeyEvent { event ->
                                    if (event.key != Key.Enter && event.key != Key.NumPadEnter) false
                                    else {
                                        if (event.type == KeyEventType.KeyUp) save()
                                        true
                                    }
                                },
                        )
                    }
                    GCodeKeyboard(
                        letters = letters, numbers = numbers,
                        onAction = { action ->
                            if (operation?.state?.value?.isActive() != true) editWithKeypad(input, action, ::edit, ::save)
                            focus.requestFocus()
                        },
                        modifier = Modifier.widthIn(max = keyboardWidth).fillMaxHeight(),
                    )
                }
            }
        }
    }

    private fun operationStatus(progress: Operation.Progress, executingLine: Int?): String {
        val position = executingLine?.let { line ->
            val elapsed = progress.activeLineElapsed?.toMillis()?.let { " · ~${it / 1000.0} s" }.orEmpty()
            "Executing line $line$elapsed"
        } ?: "Active line unknown"
        val dispatch = progress.lastSentLineNumber?.let { line ->
            when (progress.reply) {
                BufferState.Reply.Pending -> " · Line $line sent"
                BufferState.Reply.Ok -> " · Line $line accepted"
                else -> ""
            }
        }.orEmpty()
        return when (progress.phase) {
            Operation.Phase.PREPARED -> "Starting MDI"
            Operation.Phase.RUNNING -> "Running · $position$dispatch"
            Operation.Phase.HELD -> "Held · $position$dispatch"
            Operation.Phase.COMPLETED -> "Completed"
            Operation.Phase.FAILED -> {
                val line = progress.lastSentLineNumber
                val error = (progress.reply as? BufferState.Reply.Error)?.code
                "Line $line rejected (error:$error)"
            }
            Operation.Phase.INTERRUPTED -> buildString {
                append("Interrupted · completion unknown")
                progress.uncertainWriteLineNumber?.let { append(" · write uncertain at line $it") }
                progress.detail?.let { append(" · $it") }
            }
        }
    }

}
