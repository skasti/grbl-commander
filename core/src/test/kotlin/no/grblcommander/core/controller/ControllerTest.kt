package no.grblcommander.core.controller

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import no.grblcommander.core.connection.ConnectionTestTransport
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.RealtimeCommand
import kotlin.test.*

class ControllerTest {
    private fun scenario(block: suspend CoroutineScope.(Controller, ConnectionTestTransport) -> Unit) = runBlocking {
        withTimeout(8000) {
            val controller = Controller(this)
            val transport = ConnectionTestTransport()
            try {
                controller.connect(GrblMachineSession(transport, this))
                block(controller, transport)
            } finally {
                controller.close()
            }
        }
    }

    @Test fun concurrentAdmissionRetainsWinnerUntilLaterIdle() = scenario { controller, transport ->
        val start = CompletableDeferred<Unit>()
        val contenders = List(2) { Operation(listOf("G1 X1"), "MDI") }
        val accepted = contenders.map { operation ->
            async(Dispatchers.Default) {
                start.await()
                runCatching { controller.run(operation) }.isSuccess
            }
        }
        start.complete(Unit)
        val winner = contenders.zip(accepted.awaitAll()).single { it.second }.first
        assertSame(winner, controller.activeOperation.value)
        assertEquals("N1 G1 X1\n", transport.nextNonStatusWrite())
        transport.emit("ok\n")
        winner.progress.first { it.phase == Operation.Phase.WAITING_FOR_IDLE }
        report(controller, transport, "Run")
        assertSame(winner, controller.activeOperation.value)
        report(controller, transport, "Idle")
        controller.activeOperation.first { it == null }
        assertEquals(Operation.Phase.COMPLETED, winner.progress.value.phase)
        assertFails { controller.run(winner) }
    }

    @Test fun heldQueueRequiresExplicitStartAndSubsequentReadiness() = scenario { controller, transport ->
        val operation = Operation(List(4) { "G0 X$it" }, "Program", bufferCapacity = 1)
        controller.run(operation)
        assertEquals("N1 G0 X0\n", transport.nextNonStatusWrite())
        controller.sendRealtime(RealtimeCommand.FEED_HOLD)
        assertEquals("!", transport.nextNonStatusWrite())
        transport.emit("ok\n")
        operation.progress.first { it.acknowledged == 1 }
        report(controller, transport, "Run")
        assertFails { controller.sendRealtime(RealtimeCommand.CYCLE_START) }
        report(controller, transport, "Hold:0")
        controller.sendRealtime(RealtimeCommand.CYCLE_START)
        assertEquals("~", transport.nextNonStatusWrite())
        assertTrue(transport.drainWrites().isEmpty())
        report(controller, transport, "Run")
        assertEquals("N2 G0 X1\n", transport.nextNonStatusWrite())
        controller.reset()
        assertNull(controller.activeOperation.value)
        assertEquals(Operation.Phase.INTERRUPTED, operation.progress.value.phase)
    }

    @Test fun transientHoldCannotBeLostBetweenStatusCollections() = scenario { controller, transport ->
        val operation = Operation(listOf("G1 X1", "G1 X2"), "MDI")
        controller.run(operation)
        transport.nextNonStatusWrite()
        transport.emit("<Hold:0>\n<Run>\nok\n")
        operation.progress.first { it.acknowledged == 1 }
        assertTrue(transport.drainWrites().isEmpty())
        report(controller, transport, "Hold:0")
        controller.sendRealtime(RealtimeCommand.CYCLE_START)
        assertEquals("~", transport.nextNonStatusWrite())
        report(controller, transport, "Run")
        assertEquals("N2 G1 X2\n", transport.nextNonStatusWrite())
    }

    @Test fun idleInSameReadAsFinalAckCompletesOperation() = scenario { controller, transport ->
        val operation = Operation(listOf("G4 P0"), "MDI")
        controller.run(operation)
        transport.nextNonStatusWrite()
        transport.emit("ok\n<Idle>\n")
        controller.activeOperation.first { it == null }
        assertEquals(Operation.Phase.COMPLETED, operation.progress.value.phase)
    }

    @Test fun idleBeforeFinalAckCannotCompleteOperation() = scenario { controller, transport ->
        transport.autoStatus = false
        val operation = Operation(listOf("G4 P0"), "MDI")
        controller.run(operation)
        transport.nextNonStatusWrite()
        transport.emit("<Idle>\nok\n")
        operation.progress.first { it.phase == Operation.Phase.WAITING_FOR_IDLE }
        assertSame(operation, controller.activeOperation.value)
        report(controller, transport, "Idle")
        controller.activeOperation.first { it == null }
        assertEquals(Operation.Phase.COMPLETED, operation.progress.value.phase)
    }

    @Test fun pollingCompletesAnOperationWithoutUnsolicitedStatus() = scenario { controller, transport ->
        val initial = controller.state.value.reportSequence
        controller.state.first { it.reportSequence > initial }
        val operation = Operation(listOf("G4 P0"), "MDI")
        controller.run(operation)
        assertEquals("G4 P0\n", transport.nextNonStatusWrite())
        transport.emit("ok\n")
        controller.activeOperation.first { it == null }
        assertEquals(Operation.Phase.COMPLETED, operation.progress.value.phase)
    }

    @Test fun executingLineRequiresAFreshRunOrHoldReportAfterItsWrite() = scenario { controller, transport ->
        val release = CompletableDeferred<Unit>()
        transport.autoStatus = false
        transport.onWrite = { if (it == "N1 G1 X1\n") release.await() }
        val operation = Operation(listOf("G1 X1"), "MDI")
        controller.run(operation)
        try {
            assertEquals("N1 G1 X1\n", transport.nextNonStatusWrite())
            report(controller, transport, "Run|Ln:1")
            assertNull(operation.progress.value.executing)
        } finally {
            release.complete(Unit)
        }
        operation.progress.first { it.sent == 1 }
        transport.emit("ok\n")
        operation.progress.first { it.phase == Operation.Phase.WAITING_FOR_IDLE }
        assertNull(operation.progress.value.executing)
        report(controller, transport, "Run|Ln:1")
        operation.progress.first { it.executing == 1 }
        report(controller, transport, "Idle|Ln:1")
        controller.activeOperation.first { it == null }
        assertEquals(Operation.Phase.COMPLETED, operation.progress.value.phase)
        assertNull(operation.progress.value.executing)
    }

    @Test fun lossAfterFinalAckInterruptsAndReplacementCanRun() = scenario { controller, transport ->
        val operation = Operation(listOf("G4 P0"), "MDI")
        controller.run(operation)
        transport.nextNonStatusWrite()
        transport.emit("ok\n")
        operation.progress.first { it.phase == Operation.Phase.WAITING_FOR_IDLE }
        transport.disconnect()
        controller.activeOperation.first { it == null }
        assertEquals(Operation.Phase.INTERRUPTED, operation.progress.value.phase)
        assertFails { controller.run(Operation(listOf("G4 P0"), "MDI")) }
        val replacement = ConnectionTestTransport()
        controller.connect(GrblMachineSession(replacement, this))
        controller.run(Operation(listOf("G4 P1"), "MDI"))
        assertEquals("G4 P1\n", replacement.nextNonStatusWrite())
    }

    @Test fun replacementWaitsForEnteredWriteAndRetainsUncertainty() = scenario { controller, transport ->
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.onWrite = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanup.complete(Unit)
                    release.await()
                }
            }
        }
        val first = Operation(List(8) { "G1 X$it" }, "Program", bufferCapacity = 1)
        controller.run(first)
        entered.await()
        val replacement = ConnectionTestTransport()
        val nextSession = GrblMachineSession(replacement, this)
        val replacing = launch { controller.connect(nextSession) }
        try {
            cleanup.await()
            assertSame(first, controller.activeOperation.value)
            assertEquals(0, replacement.connectCount)
            assertFails { controller.run(Operation(listOf("G4 P0"), "MDI")) }
        } finally {
            release.complete(Unit)
        }
        replacing.join()
        assertNull(controller.activeOperation.value)
        assertEquals(1, first.progress.value.uncertainWrite)
        assertNull(first.progress.value.sent)
        assertNull(first.progress.value.acknowledged)
        assertEquals(Operation.Phase.INTERRUPTED, first.progress.value.phase)
        val second = Operation(listOf("G4 P0"), "MDI")
        controller.run(second)
        assertEquals("G4 P0\n", replacement.nextNonStatusWrite())
        assertSame(second, controller.activeOperation.value)
    }

    @Test fun rejectsStaleBusyAndUnsynchronizedSessionWithoutConsumingOperation() = runBlocking {
        withTimeout(5000) {
            var offset = 10L
            val controller = Controller(this, now = { java.time.Instant.now().plusSeconds(offset) })
            val transport = ConnectionTestTransport()
            val operation = Operation(listOf("G4 P0"), "MDI")
            try {
                assertFails { controller.run(operation) }
                controller.connect(GrblMachineSession(transport, this))
                assertFails { controller.run(operation) }
                offset = 0
                report(controller, transport, "Run")
                assertFails { controller.run(operation) }
                report(controller, transport, "Idle")
                transport.emit("ok\n")
                controller.state.first { !it.normalCommandsSynchronized }
                assertFails { controller.run(operation) }
                assertEquals(Operation.Phase.READY, operation.progress.value.phase)
            } finally {
                controller.close()
            }
        }
    }

    private suspend fun report(controller: Controller, transport: ConnectionTestTransport, mode: String) {
        val previous = controller.state.value.reportSequence
        transport.emit("<$mode>\n")
        controller.state.first { it.reportSequence > previous && it.status?.executionState == mode.substringBefore('|') }
    }
}
