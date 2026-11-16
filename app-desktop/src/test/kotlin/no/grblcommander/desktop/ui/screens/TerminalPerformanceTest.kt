package no.grblcommander.desktop.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.*
import no.grblcommander.core.connection.*
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.*
import no.grblcommander.machine.session.GrblMachineSession
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real protocol -> core stream -> retained Compose UI, using software rendering and no hardware. */
@OptIn(ExperimentalTestApi::class)
class TerminalPerformanceTest {
    @Test fun `10000 entries remain interactive during burst ingestion and sustained traffic`() = exercise(burst = true)

    @Test fun `production buffers remain responsive with 10000 rows and sustained traffic`() = exercise(burst = false)

    private fun exercise(burst: Boolean) = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = ConnectionTestTransport()
        val owner = ControllerConnection(scope, { _, child ->
            GrblMachineSession(transport, child, communicationBufferCapacity = if (burst) 32_768 else 256)
        }, trafficCapacity = if (burst) 32_768 else 1_024)
        val terminal = Terminal(ControllerSettings(owner))
        val stack = ScreenStack(terminal)
        try {
            setContent { MaterialTheme { AppShell(ShellState.startup(), { stack }) { ScreenStackHost(stack) } } }
            runBlocking { owner.connect(ControllerEndpoint.Tcp("localhost", 1234)) }
            val started = System.nanoTime()
            runBlocking {
                repeat(625) { batch ->
                    repeat(16) { transport.emit("[LOAD:${batch * 16 + it}]\n") }
                    if (!burst) delay(4)
                }
            }
            waitUntil(timeoutMillis = 20_000) { terminal.history.size == 10_000 }
            waitForIdle()
            val loadMillis = (System.nanoTime() - started) / 1_000_000.0
            val initialTail = terminal.history.last().cursor
            onNodeWithTag("terminal-log").performTouchInput { swipeDown() }
            onNodeWithTag("softkey-R6").assertIsNotSelected()
            val log = onNodeWithTag("terminal-log").fetchSemanticsNode()
            val prompt = onNodeWithTag("terminal-prompt").fetchSemanticsNode()
            val scroll = log.config[SemanticsActions.ScrollBy].action!!
            val edit = prompt.config[SemanticsActions.SetText].action!!
            val produced = AtomicInteger()
            val producer = scope.launch {
                repeat(1_000) { batch ->
                    repeat(16) { transport.emit("[LIVE:${batch * 16 + it}]\n"); produced.incrementAndGet() }
                    delay(if (burst) 2 else 4)
                }
            }
            val latency = mutableListOf<Double>()
            var concurrentTasks = 0
            repeat(40) { index ->
                val before = System.nanoTime()
                runOnUiThread {
                    if (producer.isActive) concurrentTasks++
                    activateSoftKey({ stack }, BottomSoftKeySlot.F7)
                    activateSoftKey({ stack }, BottomSoftKeySlot.F4) // Toggle Info on receipt, retaining older entries.
                    activateSoftKey({ stack }, BottomSoftKeySlot.F7)
                    scroll(0f, if (index % 2 == 0) -96f else 96f)
                    edit(AnnotatedString("draft $index"))
                }
                latency += (System.nanoTime() - before) / 1_000_000.0
                Thread.sleep(10) // Spread input across active producer batches, not a post-traffic test.
            }
            runBlocking { producer.join() }
            waitUntil(timeoutMillis = 20_000) { terminal.history.last().cursor > initialTail }
            waitForIdle()
            assertEquals(10_000, terminal.history.size)
            assertEquals("draft 39", terminal.prompt.text)
            assertEquals(0L, terminal.history.missed)
            assertTrue(concurrentTasks >= 10, "Interactions must actually overlap the running producer")
            assertTrue(latency.max() < 1_500, "UI task starvation: $latency")
            val sorted = latency.sorted()
            println("TERMINAL_PERF mode=${if (burst) "burst-32768" else "production-256-1024"} preload=10000 loadMs=%.2f sustainedEvents=%d uiTasks=%d concurrent=%d p95Ms=%.2f maxMs=%.2f retained=%d missed=%d".format(
                loadMillis, produced.get(), latency.size, concurrentTasks, sorted[(sorted.size * .95).toInt()], sorted.last(), terminal.history.size, terminal.history.missed,
            ))
        } finally { owner.close(); runBlocking { owner.awaitClosed() }; scope.cancel() }
    }
}
