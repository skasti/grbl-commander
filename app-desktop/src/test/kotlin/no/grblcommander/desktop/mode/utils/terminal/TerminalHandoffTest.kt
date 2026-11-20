package no.grblcommander.desktop.mode.utils.terminal

import java.io.IOException
import kotlinx.coroutines.*
import no.grblcommander.core.connection.*
import no.grblcommander.machine.session.CommunicationEvent
import no.grblcommander.machine.session.GrblMachineSession
import kotlin.test.*

class TerminalHandoffTest {
    @Test fun `real handoff reaches command history before acknowledgment and remains on controller error or timeout`() = runBlocking<Unit> {
        withTimeout(10_000) {
            for (response in listOf("ok\n", "error:2\n", null)) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val transport = ConnectionTestTransport()
                val owner = ControllerConnection(scope, { _, child -> GrblMachineSession(transport, child, commandTimeoutMillis = 500) })
                val terminal = Terminal(ControllerSettings(owner))
                val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { terminal.observeTraffic() }
                try {
                    owner.connect(ControllerEndpoint.Tcp("localhost", 1234, no.grblcommander.core.connection.noPostConnectActions()))
                    val command = async { runCatching { owner.executeLine("G4 P1") } }
                    assertEquals("G4 P1\n", transport.nextNonStatusWrite())
                    while (terminal.commandHistory.isEmpty()) yield()
                    assertFalse(command.isCompleted)
                    assertEquals("G4 P1", terminal.commandHistory.single())
                    response?.let { transport.emit(it) }
                    val result = command.await()
                    if (response == null) assertIs<TimeoutCancellationException>(result.exceptionOrNull())
                    else assertEquals(response == "ok\n", result.getOrThrow().accepted)
                    assertEquals(listOf("G4 P1"), terminal.commandHistory.toList())
                    assertEquals(1, owner.diagnosticSnapshot().count { it.event.category == CommunicationEvent.Category.COMMAND })
                } finally { observer.cancelAndJoin(); owner.close(); owner.awaitClosed(); scope.cancel() }
            }
        }
    }

    @Test fun `validation rejection creates no history and transport rejection rolls back only its own handoff`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val transport = ConnectionTestTransport()
            val owner = ControllerConnection(scope, { _, child -> GrblMachineSession(transport, child) })
            val terminal = Terminal(ControllerSettings(owner))
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { terminal.observeTraffic() }
            try {
                owner.connect(ControllerEndpoint.Tcp("localhost", 1234, no.grblcommander.core.connection.noPostConnectActions()))
                assertFailsWith<IllegalArgumentException> { owner.executeLine("G0 X1\nG0 X2") }
                assertTrue(terminal.commandHistory.isEmpty())
                transport.localWriteFailure = IllegalArgumentException("local admission rejected")
                assertFailsWith<IllegalArgumentException> { owner.executeLine("G0 X1") }
                while (terminal.history.lastOrNull()?.event?.outcome != CommunicationEvent.Outcome.LOCAL_REJECTION) yield()
                assertTrue(terminal.commandHistory.isEmpty())
                assertTrue(transport.drainWrites().all { it == "?" })
            } finally { observer.cancelAndJoin(); owner.close(); owner.awaitClosed(); scope.cancel() }
        }
    }
}
