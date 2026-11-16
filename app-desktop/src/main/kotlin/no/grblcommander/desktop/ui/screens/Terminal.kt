package no.grblcommander.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.datatransfer.StringSelection
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.onSubscription
import androidx.compose.runtime.snapshots.Snapshot
import no.grblcommander.core.connection.ControllerSettings
import no.grblcommander.core.connection.ControllerTraffic
import no.grblcommander.core.connection.description
import no.grblcommander.desktop.ui.controls.*
import no.grblcommander.desktop.ui.dialogs.ModalDialog
import no.grblcommander.desktop.ui.navigation.*
import no.grblcommander.desktop.ui.rememberCurrentTime
import no.grblcommander.machine.protocol.GrblProtocol
import no.grblcommander.machine.session.CommunicationEvent
import no.grblcommander.machine.session.MachineSnapshot.Connection
import no.grblcommander.machine.session.RealtimeCommand

/** Persistent Util root, observing the application connection rather than owning I/O. */
class Terminal(private val settings: ControllerSettings) : Screen<Unit>(Unit) {
    private val connection get() = settings.connection
    internal val filters = TerminalFilters()
    internal var prompt by mutableStateOf(TextFieldValue())
    internal var localError by mutableStateOf<String?>(null)
        private set
    private var submitting by mutableStateOf(false)
    private var follow by mutableStateOf(true)
    private var selectedText by mutableStateOf("")
    private var clearedThrough = 0L
    internal val history = TerminalHistory()
    internal val commandHistory = TerminalCommandHistory()
    private val commands get() = commandHistory
    private var historyIndex by mutableIntStateOf(0)
    private var draft = TextFieldValue()
    private var scope: CoroutineScope? = null
    private val realtimeBusy = mutableStateMapOf<BottomSoftKeySlot, Boolean>()
    private var observedState by mutableStateOf(connection.state.value)
    private var now by mutableStateOf(java.time.Instant.now())
    private val connected get() = observedState.let { !it.closing && it.snapshot.connection == Connection.CONNECTED }

    private var filtersOpen by mutableStateOf(false)
    private var copySelection by mutableStateOf<(() -> Unit)?>(null)
    override val rightKeys = mapOf(
        RightSoftKeySlot.R6 to SoftKeyDefinition("⏬\nFollow", action = { follow = !follow }, active = { follow }, repeatable = false),
        RightSoftKeySlot.R7 to SoftKeyDefinition("🔗\nConnection", action = ::openConnection, repeatable = false),
    )
    private val filterKeys = mapOf(
        BottomSoftKeySlot.F1 to SoftKeyDefinition("📊\nStatus RX", action = { filters.status = !filters.status }, active = { filters.status }),
        BottomSoftKeySlot.F2 to SoftKeyDefinition("❔\nPoll TX", action = { filters.polling = !filters.polling }, active = { filters.polling }),
        BottomSoftKeySlot.F3 to SoftKeyDefinition("✅\nOK", action = { filters.acknowledgments = !filters.acknowledgments }, active = { filters.acknowledgments }),
        BottomSoftKeySlot.F4 to SoftKeyDefinition("💬\nInfo", action = { filters.information = !filters.information }, active = { filters.information }),
        BottomSoftKeySlot.F5 to SoftKeyDefinition("⚠\nErrors", action = { filters.errors = !filters.errors }, active = { filters.errors }),
        BottomSoftKeySlot.F6 to SoftKeyDefinition("⏱\nTime", action = { filters.time = !filters.time }, active = { filters.time }),
        BottomSoftKeySlot.F7 to SoftKeyDefinition("↩\nBack", action = { filtersOpen = false }, repeatable = false),
    )
    private val bottomPages = SoftKeyPages(
        pages = listOf(
            mapOf(
                BottomSoftKeySlot.F1 to SoftKeyDefinition("▶️\nCycle Start (~)", action = { realtime(BottomSoftKeySlot.F1) { connection.sendRealtime(RealtimeCommand.CYCLE_START) } }, enabled = { realtimeBusy[BottomSoftKeySlot.F1] != true && observedState.canCycleStart(now) }, repeatable = false),
                BottomSoftKeySlot.F2 to SoftKeyDefinition("⏸️\nFeed Hold (!)", action = { realtime(BottomSoftKeySlot.F2) { connection.sendRealtime(RealtimeCommand.FEED_HOLD) } }, enabled = { connected && realtimeBusy[BottomSoftKeySlot.F2] != true }, repeatable = false),
                BottomSoftKeySlot.F3 to SoftKeyDefinition("⬆️\nPrev Cmd", action = { browseHistory(-1) }, enabled = { !submitting && historyIndex > 0 }),
                BottomSoftKeySlot.F4 to SoftKeyDefinition("⬇️\nNext Cmd", action = { browseHistory(1) }, enabled = { !submitting && historyIndex < commands.size }),
            ),
            mapOf(
                BottomSoftKeySlot.F1 to SoftKeyDefinition("🔄\nSoft Reset", action = ::confirmReset, enabled = { connected && !observedState.snapshot.resetInProgress && realtimeBusy[BottomSoftKeySlot.F1] != true }, repeatable = false),
                BottomSoftKeySlot.F2 to SoftKeyDefinition("Copy selected\ntext", action = { copySelection?.invoke() }, enabled = { selectedText.isNotEmpty() && copySelection != null }, repeatable = false),
                BottomSoftKeySlot.F3 to SoftKeyDefinition("🧹\nClear view", action = ::clearView, repeatable = false),
            ),
        ),
        nextSlot = BottomSoftKeySlot.F8,
        commonKeys = mapOf(
            BottomSoftKeySlot.F7 to SoftKeyDefinition("Filters", icon = filterIcon, action = { filtersOpen = true }, repeatable = false),
        ),
    )
    override val bottomKeys get() = if (filtersOpen) filterKeys else bottomPages.keys

    private fun clearView() {
        clearedThrough = connection.trafficCursor
        Snapshot.withMutableSnapshot { history.clearView() }
        selectedText = ""
    }

    fun openConnection() {
        if (screenStack.top === this) ControllerConnectionSettings(settings).open(this)
    }

    private fun realtime(slot: BottomSoftKeySlot, action: suspend () -> Unit) {
        if (!connected || realtimeBusy[slot] == true || screenStack.top !== this) return
        realtimeBusy[slot] = true
        scope?.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { localError = "Local control failure; result may be uncertain: ${error.message}" }
            finally { realtimeBusy[slot] = false }
        } ?: run { realtimeBusy[slot] = false }
    }

    private fun confirmReset() {
        if (!connected || connection.state.value.snapshot.resetInProgress || screenStack.top !== this) return
        ModalDialog(
            "Reset controller?",
            "Soft reset interrupts active work and invalidates pending commands. Machine position and execution may be uncertain. Nothing is replayed, unlocked or resumed automatically.",
            false,
            mapOf(BottomSoftKeySlot.F1 to ModalDialog.Choice("Reset", true), BottomSoftKeySlot.F8 to ModalDialog.Choice("Cancel", false)),
        ).open(this) { confirmed -> if (confirmed) realtime(BottomSoftKeySlot.F1) { connection.reset() } }
    }

    internal fun submit() {
        if (submitting || screenStack.top !== this) return
        val entered = prompt
        try {
            GrblProtocol.encodeCommand(entered.text)
            check(connection.state.value.canExecuteLine()) { "Manual command unavailable: controller must be synchronized and idle" }
        } catch (error: Exception) { localError = error.message; return }
        submitting = true // Set before launch: IME and key callbacks cannot admit twice.
        localError = null
        scope?.launch {
            try {
                connection.executeLine(entered.text)
                // Only traffic handoffs update command history; acknowledgment is not motion completion.
                if (prompt == entered) {
                    prompt = TextFieldValue()
                    historyIndex = commands.size
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                localError = "Command rejected or result uncertain; inspect traffic before submitting again: ${error.message}"
            } finally { submitting = false }
        } ?: run { submitting = false }
    }

    internal fun browseHistory(offset: Int) {
        if (commands.isEmpty() || submitting) return
        if (historyIndex == commands.size) draft = prompt
        historyIndex = (historyIndex + offset).coerceIn(0, commands.size)
        prompt = if (historyIndex == commands.size) draft else commands.elementAt(historyIndex).let { TextFieldValue(it, TextRange(it.length)) }
    }

    private fun receiveTraffic(traffic: ControllerTraffic) {
        val atDraft = historyIndex == commands.size
        if (history.receive(traffic, traffic.cursor > clearedThrough && filters.includes(traffic.event))) {
            commandHistory.receive(traffic)
            historyIndex = if (atDraft) commands.size else historyIndex.coerceAtMost(commands.size)
        }
    }

    /** Register first, then seed. SharedFlow queues concurrent events; cursor overlap is ignored. */
    internal suspend fun observeTraffic() {
        var delivered = 0
        connection.traffic.onSubscription {
            val snapshot = connection.diagnosticSnapshot()
            Snapshot.withMutableSnapshot {
                history.start(snapshot)
                snapshot.forEach(::receiveTraffic)
            }
        }.collect {
            Snapshot.withMutableSnapshot { receiveTraffic(it) }
            if (++delivered % 128 == 0) yield() // Let pointer/key/frame work run during sustained traffic.
        }
    }

    @Composable override fun Content() {
        val compositionScope = rememberCoroutineScope()
        DisposableEffect(compositionScope) { scope = compositionScope; onDispose { scope = null } }
        val state by connection.state.collectAsState(context = Dispatchers.Main)
        LaunchedEffect(connection) {
            // The composition dispatcher can flush inline on a publishing thread.
            // Confine incremental UI mutations to the actual desktop UI dispatcher.
            withContext(Dispatchers.Main) { observeTraffic() }
        }
        val clock = rememberCurrentTime()
        SideEffect { now = clock; observedState = state }
        val lines = history
        val list = rememberLazyListState()
        var userScrollingDown by remember { mutableStateOf(false) }
        val scrollObserver = remember(list) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput) {
                        if (available.y > 0f) { follow = false; userScrollingDown = false }
                        else if (available.y < 0f) userScrollingDown = true
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && userScrollingDown && !list.canScrollForward) follow = true
                    return Offset.Zero
                }
            }
        }
        LaunchedEffect(list) {
            snapshotFlow { Triple(list.canScrollForward, list.isScrollInProgress, userScrollingDown) }.collect { (canForward, scrolling, down) ->
                if (down && !canForward) follow = true
                if (down && !scrolling) userScrollingDown = false
            }
        }
        LaunchedEffect(lines.lastOrNull()?.cursor, follow) {
            // FlushCoroutineDispatcher can enter an effect while composition is applying.
            // Queue scroll measurement on the desktop event loop after that apply completes.
            withContext(Dispatchers.Main) {
                yield()
                if (follow && lines.isNotEmpty()) list.scrollToItem(lines.lastIndex)
            }
        }
        val clipboard = LocalClipboard.current
        DisposableEffect(compositionScope, clipboard) {
            copySelection = { compositionScope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(selectedText))) } }
            onDispose { copySelection = null }
        }
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val keyboardWidth = minOf(340.dp, maxWidth * .40f)
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${state.snapshot.connection} · ${state.endpoint?.description() ?: "No endpoint"} · ${state.snapshot.status?.executionState ?: "Unknown"}")
                        if (state.reportIsStale(clock)) Text("Machine state is stale or unavailable.", color = MaterialTheme.colorScheme.tertiary)
                        state.snapshot.alarmCode?.let { Text("Controller alarm: $it", color = MaterialTheme.colorScheme.error) }
                        if (history.missed > 0) Text("${history.missed} traffic events missed")
                        Column(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF101419)).padding(8.dp)) {
                            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(scrollObserver).testTag("terminal-log")) {
                                items(lines, key = { "${it.sessionId}:${it.event.sequence}" }) { traffic ->
                                    val event = traffic.event
                                    val text = render(event, filters.time)
                                    var value by remember(text) { mutableStateOf(TextFieldValue(text)) }
                                    val color = when {
                                        event.category in setOf(CommunicationEvent.Category.ALARM, CommunicationEvent.Category.ERROR) ||
                                            event.outcome in setOf(CommunicationEvent.Outcome.UNCERTAIN, CommunicationEvent.Outcome.LOCAL_REJECTION) -> Color(0xFFFF8F8F)
                                        event.direction == CommunicationEvent.Direction.TX -> Color(0xFF8BCBFF)
                                        event.category == CommunicationEvent.Category.ACKNOWLEDGMENT -> Color(0xFF93D6A3)
                                        else -> Color(0xFFD7DEE7)
                                    }
                                    BasicTextField(value, onValueChange = {
                                        value = it
                                        if (!it.selection.collapsed) selectedText = text.substring(it.selection.min, it.selection.max)
                                    }, readOnly = true, textStyle = consoleStyle.copy(color = color), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
                                }
                            }
                            BasicTextField(
                                value = prompt,
                                onValueChange = ::updatePrompt,
                                singleLine = true,
                                textStyle = consoleStyle.copy(color = Color.White),
                                cursorBrush = SolidColor(Color.White),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.fillMaxWidth().testTag("terminal-prompt")
                                    .onFocusChanged { if (!it.isFocused) enterHeld = false }
                                    .onPreviewKeyEvent { event ->
                                        when (event.key) {
                                            Key.Enter, Key.NumPadEnter -> { if (event.type == KeyEventType.KeyDown) { if (!enterHeld) { enterHeld = true; submit() } } else enterHeld = false; true }
                                            Key.DirectionUp -> { if (event.type == KeyEventType.KeyDown) browseHistory(-1); true }
                                            Key.DirectionDown -> { if (event.type == KeyEventType.KeyDown) browseHistory(1); true }
                                            else -> false
                                        }
                                    },
                                decorationBox = { input ->
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("> ", style = consoleStyle.copy(color = Color(0xFF93D6A3)), modifier = Modifier.testTag("terminal-prompt-marker"))
                                        Box(Modifier.weight(1f).testTag("terminal-input-line")) { input() }
                                    }
                                },
                            )
                        }
                        Text("Commands can move the machine. Enter submits one line; ok acknowledges reception, not completed motion.", style = MaterialTheme.typography.bodySmall)
                        if (submitting) Text("Awaiting command acknowledgment…")
                        if (!state.canExecuteLine(clock)) Text("Manual commands unavailable while disconnected, busy, stale or unsynchronized.", style = MaterialTheme.typography.bodySmall)
                        localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    GCodeKeyboard(
                        onAction = { action -> editWithKeypad(prompt, action, ::updatePrompt, ::submit) },
                        modifier = Modifier.widthIn(max = keyboardWidth).fillMaxHeight(),
                    )
                }
            }
        }
    }

    private fun updatePrompt(value: TextFieldValue) {
        prompt = value
        historyIndex = commands.size
    }

    private var enterHeld = false
    private companion object {
        val filterIcon = ImageVector.Builder(
            name = "Filter funnel", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 4f)
                lineTo(21f, 4f)
                lineTo(14f, 12f)
                lineTo(14f, 19f)
                lineTo(10f, 21f)
                lineTo(10f, 12f)
                close()
            }
        }.build()
        val consoleStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
        val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
        fun render(event: CommunicationEvent, time: Boolean): String = buildString {
            if (time) append(timeFormat.format(event.timestamp)).append(' ')
            append(event.direction).append(' ').append(event.category).append("  ")
            if (event.outcome == CommunicationEvent.Outcome.UNCERTAIN || event.outcome == CommunicationEvent.Outcome.LOCAL_REJECTION) {
                append("[${event.outcome} for handoff #${event.attemptSequence}: ${event.detail.orEmpty()}]")
            } else {
                event.text.trimEnd('\r', '\n').forEach { ch ->
                    if (ch.isISOControl()) append("\\x%02X".format(ch.code)) else append(ch)
                }
                if (event.outcome == CommunicationEvent.Outcome.ATTEMPTED) append(" [handoff #${event.sequence}]")
            }
        }
    }
}
