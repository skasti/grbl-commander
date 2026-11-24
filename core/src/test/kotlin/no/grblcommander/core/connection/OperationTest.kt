package no.grblcommander.core.connection

import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.transport.MachineTransport
import kotlin.test.*

class OperationTest {
    private val endpoint = ControllerEndpoint.Tcp("localhost", 1234, noPostConnectActions())

    @Test fun acknowledgementAndPhysicalCompletionAreIndependent() = scenario { owner, transport ->
        assertFailsWith<IllegalStateException> { owner.resumeFeedHold() }
        assertTrue(transport.drainWrites().all { it == "?" })
        val source = mutableListOf("G0 X1", "G1 X2", "G1 X3")
        val run = owner.startOperation(source = Operation.Source("COMMAND"), sourceId = "draft-1", lines = source)
        assertEquals(Operation.Source("COMMAND"), run.source)
        assertSame(run, owner.state.value.activeExecution)
        assertEquals("draft-1", run.sourceId)
        source[0] = "G0 X99"
        assertEquals("G0 X1", run.lines.first())
        assertEquals("N1 G0 X1\n", transport.nextNonStatusWrite())
        run.state.first { it.lastSentLineNumber == 1 && it.reply == BufferState.Reply.Pending }
        report(owner, transport, "<Run|Ln:1>")
        assertEquals(Duration.ZERO, run.state.first { it.executingLineNumber == 1 }.activeLineElapsed)
        val first = run.state.value.observedAt
        report(owner, transport, "<Run|Ln:1>")
        assertTrue(run.state.first { it.observedAt != first }.activeLineElapsed!! > Duration.ZERO)
        report(owner, transport, "<Hold:1|Ln:1>")
        assertFailsWith<IllegalStateException> { owner.resumeFeedHold() }
        report(owner, transport, "<Hold:0|Ln:1>")
        val held = run.state.first { it.phase == Operation.Phase.HELD }
        transport.emit("ok\n")
        run.state.first { it.reply == BufferState.Reply.Ok }
        assertTrue(transport.drainWrites().all { it == "?" })
        owner.resumeFeedHold()
        assertEquals("~", transport.nextNonStatusWrite())
        report(owner, transport, "<Run|Ln:1>")
        assertEquals(held.activeLineElapsed, run.state.first { it.phase == Operation.Phase.RUNNING }.activeLineElapsed)
        assertEquals("N2 G1 X2\n", transport.nextNonStatusWrite())
        transport.emit("ok\n")
        assertEquals("N3 G1 X3\n", transport.nextNonStatusWrite())
        run.state.first { it.lastSentLineNumber == 3 }
        report(owner, transport, "<Run|Ln:3>")
        assertEquals(Duration.ZERO, run.state.first { it.executingLineNumber == 3 }.activeLineElapsed)
        report(owner, transport, "<Idle>")
        transport.emit("ok\n")
        run.state.first { it.lastSentLineNumber == 3 && it.reply == BufferState.Reply.Ok }
        assertTrue(owner.state.value.operationPending)
        assertFailsWith<IOException> { owner.executeLine("G0 X9") }
        assertFailsWith<IOException> { owner.startOperation(Operation.Source.MDI, "other", listOf("G0 X9")) }
        report(owner, transport, "<Idle>")
        run.state.first { it.phase == Operation.Phase.COMPLETED }
        owner.state.first { !it.operationPending }
        assertNull(owner.state.value.activeExecution)
        assertSame(run, owner.state.value.operation)
        assertNull(run.state.value.executingLineNumber)
    }

    @Test fun rejectedLineStopsStreamingAndRequiresFreshIdleForAdmission() = scenario { owner, transport ->
        val run = owner.startOperation(Operation.Source.MDI, "draft", listOf("G0 X1", "G0 X2"))
        assertEquals("N1 G0 X1\n", transport.nextNonStatusWrite())
        transport.emit("error:20\n")
        val failed = run.state.first { it.phase == Operation.Phase.FAILED }
        assertEquals(1, failed.lastSentLineNumber)
        assertEquals(BufferState.Reply.Error(20), failed.reply)
        assertFailsWith<IOException> { owner.executeLine("G0 X3") }
        report(owner, transport, "<Idle>")
        owner.state.first { !it.operationPending }
        assertTrue(transport.drainWrites().all { it == "?" })
    }

    @Test fun disconnectAndReconnectPreserveOutcomeWithoutReplay() = runBlocking {
        withTimeout(5000) {
            val initial = ConnectionTestTransport()
            val replacement = ConnectionTestTransport()
            val owner = ControllerConnection(this, { endpoint, scope ->
                GrblMachineSession(if ((endpoint as ControllerEndpoint.Tcp).port == 1234) initial else replacement, scope)
            })
            try {
                owner.connect(endpoint)
                val run = owner.startOperation(Operation.Source.MDI, "draft", listOf("G0 X1", "G0 X2"))
                assertEquals("N1 G0 X1\n", initial.nextNonStatusWrite())
                initial.disconnect()
                run.state.first { it.phase == Operation.Phase.INTERRUPTED }
                owner.connect(ControllerEndpoint.Tcp("localhost", 1235, noPostConnectActions()))
                assertSame(run, owner.state.value.operation)
                assertEquals(BufferState.Reply.Unknown, run.state.value.reply)
                assertTrue(replacement.drainWrites().all { it == "?" })
            } finally {
                owner.close()
                owner.awaitClosed()
            }
        }
    }

    @Test fun alarmInterruptsAndResetCannotCompleteTheRun() = scenario { owner, transport ->
        val run = owner.startOperation(Operation.Source.MDI, "draft", listOf("G0 X1", "G0 X2"))
        assertEquals("N1 G0 X1\n", transport.nextNonStatusWrite())
        transport.emit("ALARM:3\n")
        run.state.first { it.phase == Operation.Phase.INTERRUPTED }
        transport.autoStatus = true
        owner.reset()
        owner.state.first { !it.operationPending }
        assertEquals(Operation.Phase.INTERRUPTED, run.state.value.phase)
        assertTrue(transport.drainWrites().none { it.endsWith('\n') })
    }

    @Test fun timeoutLeavesOutcomeUnknown() = scenario(timeoutMillis = 150) { owner, transport ->
        val run = owner.startOperation(Operation.Source.MDI, "draft", listOf("G0 X1", "G0 X2"))
        assertEquals("N1 G0 X1\n", transport.nextNonStatusWrite())
        run.state.first { it.phase == Operation.Phase.INTERRUPTED && it.reply == BufferState.Reply.Unknown }
        transport.emit("ok\n")
        owner.state.first { it.snapshot.normalCommandsSynchronized }
        transport.autoStatus = true
        owner.state.first { !it.operationPending }
        assertTrue(transport.drainWrites().all { it == "?" })
        assertEquals(Operation.Phase.INTERRUPTED, run.state.value.phase)
    }

    @Test fun uncertainFirstWriteHasNoSuccessfullySentLine() = scenario(
        wrapTransport = { failOnWrite(it, "N1 G0 X1\n", prefixLength = 5) },
    ) { owner, transport ->
        val run = owner.startOperation(Operation.Source.MDI, "draft", listOf("G0 X1", "G0 X2"))
        assertEquals("N1 G0", transport.nextNonStatusWrite())
        val interrupted = run.state.first { it.phase == Operation.Phase.INTERRUPTED }
        assertEquals(1, interrupted.uncertainWriteLineNumber)
        assertNull(interrupted.lastSentLineNumber)
        assertEquals(BufferState.Reply.Empty, interrupted.reply)
        owner.state.first { !it.snapshot.normalCommandsSynchronized }
        assertTrue(transport.drainWrites().all { it == "?" })
    }

    @Test fun uncertainWritePreservesEarlierReplyAndNeverReplays() = scenario(
        wrapTransport = { failOnWrite(it, "N2 G0 X2\n", prefixLength = 5) },
    ) { owner, transport ->
        val run = owner.startOperation(Operation.Source.MDI, "draft", listOf("G0 X1", "G0 X2", "G0 X3"))
        assertEquals("N1 G0 X1\n", transport.nextNonStatusWrite())
        transport.emit("ok\n")
        assertEquals("N2 G0", transport.nextNonStatusWrite())
        val interrupted = run.state.first { it.phase == Operation.Phase.INTERRUPTED }
        assertEquals(2, interrupted.uncertainWriteLineNumber)
        assertEquals(1, interrupted.lastSentLineNumber)
        assertEquals(BufferState.Reply.Ok, interrupted.reply)

        transport.autoStatus = true
        owner.reset()
        owner.state.first { !it.operationPending }
        assertEquals(interrupted, run.state.value)
        assertTrue(transport.drainWrites().all { it == "?" || it == "\u0018" })
    }

    @Test fun knownLocalRejectionLeavesNoUncertainWrite() = scenario(
        wrapTransport = { failOnWrite(it, "N2 G0 X2\n") },
    ) { owner, transport ->
        val run = owner.startOperation(Operation.Source.MDI, "draft", listOf("G0 X1", "G0 X2", "G0 X3"))
        assertEquals("N1 G0 X1\n", transport.nextNonStatusWrite())
        transport.emit("ok\n")
        val interrupted = run.state.first { it.phase == Operation.Phase.INTERRUPTED }
        assertEquals(1, interrupted.lastSentLineNumber)
        assertEquals(BufferState.Reply.Ok, interrupted.reply)
        assertNull(interrupted.uncertainWriteLineNumber)
        assertTrue(owner.state.value.snapshot.normalCommandsSynchronized)
        assertTrue(transport.drainWrites().all { it == "?" })
    }

    private fun failOnWrite(
        transport: ConnectionTestTransport,
        target: String,
        prefixLength: Int = 0,
    ): MachineTransport =
        object : MachineTransport by transport {
            override suspend fun write(bytes: ByteArray) {
                if (bytes.decodeToString() == target) {
                    if (prefixLength == 0) throw IllegalArgumentException("rejected before writing")
                    transport.write(bytes.copyOf(prefixLength))
                    throw IOException("write failed after a partial transfer")
                }
                transport.write(bytes)
            }
        }

    @Test fun staleStatusClearsActiveLineAndKeepsReservation() = scenario { owner, transport ->
        val run = owner.startOperation(Operation.Source.MDI, "draft", listOf("N10 G0 X1"))
        assertEquals("N10 G0 X1\n", transport.nextNonStatusWrite())
        run.state.first { it.reply == BufferState.Reply.Pending }
        report(owner, transport, "<Run|Ln:10>")
        run.state.first { it.executingLineNumber == 1 }
        transport.emit("ok\n")
        run.state.first { it.reply == BufferState.Reply.Ok }
        val stale = run.state.first { it.observedAt == null }
        assertNull(stale.executingLineNumber)
        assertNull(stale.activeLineElapsed)
        assertTrue(owner.state.value.operationPending)
        assertFailsWith<IOException> { owner.executeLine("G0 X2") }

        report(owner, transport, "<Run>")
        val unmapped = run.state.first { it.observedAt != null }
        assertNull(unmapped.executingLineNumber)
        assertNull(unmapped.activeLineElapsed)
        report(owner, transport, "<Idle>")
        run.state.first { it.phase == Operation.Phase.COMPLETED }
    }

    @Test fun resetInterruptsAnAcceptedRunBeforeItCompletes() = scenario { owner, transport ->
        val run = owner.startOperation(Operation.Source.MDI, "draft", listOf("G0 X1"))
        assertEquals("N1 G0 X1\n", transport.nextNonStatusWrite())
        transport.emit("ok\n")
        run.state.first { it.reply == BufferState.Reply.Ok }
        transport.autoStatus = true
        owner.reset()
        owner.state.first { !it.operationPending }
        assertEquals(Operation.Phase.INTERRUPTED, run.state.value.phase)
        assertEquals(BufferState.Reply.Ok, run.state.value.reply)
    }

    @Test fun preservesExistingNumbersAndSuppressesDuplicateMappings() {
        val source = listOf("g0 n10x1", "N10 G0 X2", "N20 G1 X3 (N10)", "G4 P1")
        val commands = OperationCommands(source)
        assertEquals(source, commands.commands)
        assertEquals(mapOf(20 to 3), commands.lineNumbers)
    }

    private suspend fun report(owner: ControllerConnection, transport: ConnectionTestTransport, text: String) {
        val before = owner.state.value.snapshot.reportSequence
        transport.emit("$text\n")
        owner.state.first { it.snapshot.reportSequence > before }
    }

    private fun scenario(
        timeoutMillis: Long = 2000,
        wrapTransport: (ConnectionTestTransport) -> MachineTransport = { it },
        block: suspend (ControllerConnection, ConnectionTestTransport) -> Unit,
    ) = runBlocking {
            withTimeout(7000) {
                val transport = ConnectionTestTransport()
                val owner = ControllerConnection(this, { _, scope ->
                    GrblMachineSession(wrapTransport(transport), scope, commandTimeoutMillis = timeoutMillis)
                })
                try {
                    owner.connect(endpoint)
                    transport.autoStatus = false
                    block(owner, transport)
                } finally {
                    owner.close()
                    owner.awaitClosed()
                }
            }
        }
}
