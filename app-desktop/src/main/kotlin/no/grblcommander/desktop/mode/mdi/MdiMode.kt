package no.grblcommander.desktop.mode.mdi

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import no.grblcommander.desktop.ui.components.controls.KeyCluster
import no.grblcommander.desktop.ui.components.controls.KeyClusterState
import no.grblcommander.desktop.ui.components.controls.Keymaps
import no.grblcommander.desktop.ui.components.controls.editor.GCodeEditor
import no.grblcommander.desktop.ui.components.controls.editor.GCodeEditorState
import no.grblcommander.desktop.ui.components.controls.editor.GCodeLineDecorations
import no.grblcommander.desktop.ui.layout.LocalGridUnit
import no.grblcommander.desktop.ui.layout.ShellColors
import no.grblcommander.desktop.ui.navigation.*
import no.grblcommander.machine.protocol.GrblProtocol
import no.grblcommander.machine.session.MachineSnapshot.Connection
import no.grblcommander.machine.session.RealtimeCommand

class MdiMode(private val connection: ControllerConnection) : Screen<Unit>(Unit) {
    private val editor = GCodeEditorState(validate = { line ->
        try {
            GrblProtocol.encodeCommand(line)
            null
        } catch (failure: IllegalArgumentException) {
            failure.message ?: "Invalid MDI command"
        }
    })
    private val letters = KeyClusterState(Keymaps.code)
    private val numbers = KeyClusterState(Keymaps.numPlus)

    private var operation by mutableStateOf<Operation?>(null)
    private var connectionState by mutableStateOf(connection.state.value)
    private var now by mutableStateOf(Instant.now())
    private var scope: CoroutineScope? = null
    private var controlPending by mutableStateOf(false)
    private var controlError by mutableStateOf<String?>(null)
    private val connected get() = !connectionState.closing && connectionState.snapshot.connection == Connection.CONNECTED

    override val rightKeys = editor.rightKeys

    private val start = SoftKeyDefinition(
        "▶️\nCycle Start", action = ::startOperation, enabled = ::canStartOperation, repeatable = false,
    )
    private val resume = SoftKeyDefinition(
        "▶️\nResume (~)", action = { control { connection.resumeFeedHold() } }, enabled = ::canResume, repeatable = false,
    )
    private val controlKeys = editor.bottomKeys + mapOf(
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
            editor.source.isEmpty() -> "Press Enter to add a line before starting."
            else -> null
        }

    private fun canResume(): Boolean = !controlPending && connectionState.canCycleStart(now) &&
        connectionState.snapshot.status?.executionState == "Hold:0"

    private fun startOperation() {
        if (screenStack.top !== this || !canStartOperation()) return
        controlError = null
        try {
            val run = connection.startOperation(Operation.Source.MDI, "mdi", editor.source)
            operation = run
            editor.readOnly = run.state.value.isActive()
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

    @Composable
    override fun Content() {
        val currentConnection by connection.state.collectAsState(context = Dispatchers.Main)
        val time = rememberCurrentTime()
        SideEffect {
            connectionState = currentConnection
            now = time
            editor.readOnly = operation?.state?.value?.isActive() == true
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
        val sentLine = progress?.lastSentLineNumber?.takeIf { line ->
            val index = line - 1
            val sentSource = run.lines.getOrNull(index)
            sentSource != null && sentSource == editor.source.getOrNull(index)
        }
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().testTag("mdi-workspace")) {
                val droHeight = maxHeight * .40f
                val droWidth = droHeight * (4f / 3f)
                val gridUnit = LocalGridUnit.current
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
                        GCodeEditor(
                            editor,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            decorations = GCodeLineDecorations(executingLine, sentLine),
                        )
                    }
                    Column(
                        Modifier.fillMaxHeight(),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(
                            Modifier.size(droWidth, droHeight).testTag("mdi-dro")
                                .border(1.dp, ShellColors.accent, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("DRO", style = MaterialTheme.typography.titleMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(gridUnit)) {
                            KeyCluster(letters, editor::onKeypadAction)
                            KeyCluster(numbers, editor::onKeypadAction)
                        }
                    }
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
