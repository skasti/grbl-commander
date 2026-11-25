package no.grblcommander.core.controller

import java.io.IOException
import kotlinx.coroutines.*
import no.grblcommander.core.connection.ConnectionTestTransport
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.RealtimeCommand
import kotlin.test.*

class CommandBufferTest {
    private fun exercise(test: suspend CoroutineScope.(ConnectionTestTransport, GrblMachineSession) -> Unit) = runBlocking {
        withTimeout(5000) {
            val transport = ConnectionTestTransport()
            val session = GrblMachineSession(transport, this)
            try {
                session.connect()
                transport.drainWrites()
                test(transport, session)
            } finally {
                session.close()
                session.awaitClosed()
            }
        }
    }

    @Test fun drainsInOrderAndRechecksPermissionAfterEachAck() = exercise { transport, session ->
        val replies = mutableListOf<Int>()
        val buffer = CommandBuffer(session, this, 1) { command, outcome -> if (outcome is CommandOutcome.Reply) replies += command.position }
        buffer.enqueue(line(1))
        buffer.start()
        buffer.enqueue(line(2))
        val third = async(start = CoroutineStart.UNDISPATCHED) { buffer.enqueue(line(3)) }
        assertFalse(third.isCompleted)
        assertEquals("G1 X1\n", transport.nextNonStatusWrite())
        buffer.`suspend`()
        buffer.`suspend`()
        assertTrue(buffer.suspended)
        session.sendRealtime(RealtimeCommand.FEED_HOLD)
        assertEquals("!", transport.nextNonStatusWrite())
        transport.emit("ok\n")
        third.await()
        buffer.finishProduction()
        assertTrue(transport.drainWrites().isEmpty())
        buffer.resume()
        buffer.resume()
        assertFalse(buffer.suspended)
        for (n in 2..3) {
            assertEquals("G1 X$n\n", transport.nextNonStatusWrite())
            transport.emit("ok\n")
        }
        buffer.awaitClosed()
        assertEquals(listOf(1, 2, 3), replies)
        assertFails { buffer.enqueue(line(4)) }
        assertFails { buffer.start() }
    }

    @Test fun rejectionStopsQueuedWork() = exercise { transport, session ->
        val buffer = CommandBuffer(session, this, report = { _, _ -> })
        buffer.enqueue(line(1))
        buffer.enqueue(line(2))
        buffer.start()
        transport.nextNonStatusWrite()
        transport.emit("error:20\n")
        buffer.awaitClosed()
        assertTrue(transport.drainWrites().isEmpty())
    }

    @Test fun closeDuringAckWakesBlockedProducer() = exercise { transport, session ->
        val outcomes = mutableListOf<CommandOutcome>()
        val buffer = CommandBuffer(session, this, 1) { _, outcome -> outcomes += outcome }
        buffer.start()
        buffer.enqueue(line(1))
        transport.nextNonStatusWrite()
        buffer.enqueue(line(2))
        val blocked = async(start = CoroutineStart.UNDISPATCHED) { runCatching { buffer.enqueue(line(3)) } }
        assertFalse(blocked.isCompleted)
        buffer.close()
        buffer.close()
        buffer.awaitClosed()
        assertTrue(blocked.await().isFailure)
        assertEquals(CommandOutcome.Sent, outcomes.first())
        assertIs<CommandOutcome.Interrupted>(outcomes.last())
        assertTrue(transport.drainWrites().isEmpty())
        assertFalse(transport.closed)
    }

    @Test fun closeWhileWaitingForWriterPreventsTransmission() = exercise { transport, session ->
        val release = CompletableDeferred<Unit>()
        transport.onWrite = { if (it == "!") release.await() }
        val realtime = launch { session.sendRealtime(RealtimeCommand.FEED_HOLD) }
        assertEquals("!", transport.nextNonStatusWrite())
        val outcomes = mutableListOf<CommandOutcome>()
        val buffer = CommandBuffer(session, this) { _, outcome -> outcomes += outcome }
        buffer.enqueue(line(1))
        buffer.start()
        yield() // Let the worker run to the occupied session writer.
        buffer.close()
        buffer.awaitClosed()
        release.complete(Unit)
        realtime.join()
        assertIs<CommandOutcome.Interrupted>(outcomes.single())
        assertTrue(transport.drainWrites().isEmpty())
    }

    @Test fun uncertainTransmissionRetainsSourceIdentity() = exercise { transport, session ->
        transport.onWrite = { throw IOException("write outcome unknown") }
        val outcomes = mutableListOf<Pair<SourceCommand, CommandOutcome>>()
        val buffer = CommandBuffer(session, this) { command, outcome -> outcomes += command to outcome }
        buffer.enqueue(line(7))
        buffer.start()
        buffer.awaitClosed()
        assertEquals(listOf(line(7), line(7)), outcomes.map { it.first })
        assertEquals(CommandOutcome.UncertainWrite, outcomes.first().second)
        assertIs<CommandOutcome.Interrupted>(outcomes.last().second)
    }

    @Test fun closeDuringEnteredWriteAwaitsUncertaintyAndCleanup() = exercise { transport, session ->
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.onWrite = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleaning.complete(Unit)
                    release.await()
                }
            }
        }
        val outcomes = mutableListOf<CommandOutcome>()
        val buffer = CommandBuffer(session, this) { _, event -> outcomes += event }
        buffer.enqueue(line(1))
        buffer.start()
        entered.await()
        buffer.close()
        val termination = async(start = CoroutineStart.UNDISPATCHED) { buffer.awaitClosed() }
        try {
            cleaning.await()
            assertFalse(termination.isCompleted)
        } finally {
            release.complete(Unit)
        }
        termination.await()
        assertEquals(CommandOutcome.UncertainWrite, outcomes.first())
        assertIs<CommandOutcome.Interrupted>(outcomes.last())
    }

    @Test fun executionCancellationRetiresBufferWhileSessionStaysConnected() = exercise { transport, session ->
        val execution = Job(coroutineContext[Job])
        val buffer = CommandBuffer(session, CoroutineScope(coroutineContext + execution),
            capacity = 1, report = { _, _ -> })
        buffer.enqueue(line(1))
        buffer.start()
        transport.nextNonStatusWrite()
        buffer.enqueue(line(2))
        val blocked = async(start = CoroutineStart.UNDISPATCHED) { runCatching { buffer.enqueue(line(3)) } }
        execution.cancelAndJoin()
        buffer.awaitClosed()
        assertTrue(blocked.await().isFailure)
        assertFails { buffer.start() }
        assertTrue(transport.drainWrites().isEmpty())
        assertTrue(transport.isConnected)
    }

    @Test fun closeWinsWhileWorkerHoldsASuspendedCommand() = exercise { transport, session ->
        val buffer = CommandBuffer(session, this, 1) { _, _ -> }
        buffer.`suspend`()
        buffer.enqueue(line(1))
        buffer.start()
        buffer.enqueue(line(2)) // First command has left the single queue slot.
        buffer.resume()
        buffer.`suspend`() // Revoke permission before the worker can claim on this dispatcher.
        yield()
        assertTrue(buffer.suspended)
        assertTrue(transport.drainWrites().isEmpty())
        buffer.close()
        buffer.resume()
        buffer.awaitClosed()
        assertTrue(transport.drainWrites().isEmpty())
        assertFails { buffer.enqueue(line(3)) }
    }

    private fun line(n: Int) = SourceCommand(n, "G1 X$n")
}
