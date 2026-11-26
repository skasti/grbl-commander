package no.grblcommander.core.controller

import java.io.IOException
import kotlinx.coroutines.*
import no.grblcommander.core.connection.ConnectionTestTransport
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.RealtimeCommand
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class CommandBufferTest {
    private fun exercise(test: suspend CoroutineScope.(ConnectionTestTransport, GrblMachineSession) -> Unit) = runBlocking {
        withTimeout(5000.milliseconds) {
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

    @Test fun sendsCommandsInEnqueueOrder() = exercise { transport, session ->
        val replies = mutableListOf<Int>()
        val buffer = CommandBuffer(session, this, 3) { command, outcome ->
            if (outcome is CommandOutcome.Reply) replies += command.position
        }
        buffer.enqueue(line(1))
        buffer.enqueue(line(2))
        buffer.enqueue(line(3))
        buffer.start()
        for (n in 1..3) {
            assertEquals("G1 X$n\n", transport.nextNonStatusWrite())
            transport.emit("ok\n")
        }
        buffer.finishProduction()
        buffer.awaitClosed()
        assertEquals(listOf(1, 2, 3), replies)
    }

    @Test fun waitsForPermissionBeforeSendingNextCommand() = exercise { transport, session ->
        val firstReply = CompletableDeferred<Unit>()
        val buffer = CommandBuffer(session, this, 1) { command, outcome ->
            if (command.position == 1 && outcome is CommandOutcome.Reply) firstReply.complete(Unit)
        }
        buffer.enqueue(line(1))
        buffer.start()
        assertEquals("G1 X1\n", transport.nextNonStatusWrite())
        buffer.enqueue(line(2))
        buffer.`suspend`()
        assertTrue(buffer.suspended)
        transport.emit("ok\n")
        firstReply.await()
        assertTrue(transport.drainWrites().isEmpty())
        buffer.resume()
        assertEquals("G1 X2\n", transport.nextNonStatusWrite())
        transport.emit("ok\n")
        buffer.finishProduction()
        buffer.awaitClosed()
    }

    @Test fun suspendAndResumeAreIdempotent() = exercise { _, session ->
        val buffer = CommandBuffer(session, this, report = { _, _ -> })
        buffer.`suspend`()
        buffer.`suspend`()
        assertTrue(buffer.suspended)
        buffer.resume()
        buffer.resume()
        assertFalse(buffer.suspended)
        buffer.close()
        buffer.awaitClosed()
    }

    @Test fun finishingProductionDrainsQueuedCommandsThenCloses() = exercise { transport, session ->
        val buffer = CommandBuffer(session, this, 2) { _, _ -> }
        buffer.enqueue(line(1))
        buffer.enqueue(line(2))
        buffer.start()
        assertEquals("G1 X1\n", transport.nextNonStatusWrite())
        buffer.finishProduction()
        assertFails { buffer.enqueue(line(3)) }

        val closed = async { buffer.awaitClosed() }
        assertFalse(closed.isCompleted)
        transport.emit("ok\n")
        assertEquals("G1 X2\n", transport.nextNonStatusWrite())
        assertFalse(closed.isCompleted)
        transport.emit("ok\n")
        closed.await()
    }

    @Test fun rejectionStopsQueuedWork() = exercise { transport, session ->
        val outcomes = mutableListOf<CommandOutcome>()
        val buffer = CommandBuffer(session, this) { _, outcome -> outcomes += outcome }
        buffer.enqueue(line(1))
        buffer.enqueue(line(2))
        buffer.start()
        assertEquals("G1 X1\n", transport.nextNonStatusWrite())
        transport.emit("error:20\n")
        buffer.awaitClosed()
        val reply = assertIs<CommandOutcome.Reply>(outcomes.single())
        assertFalse(reply.acknowledgment.accepted)
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

    @Test fun resuspendingBeforeWorkerDispatchKeepsCommandBlocked() = exercise { transport, session ->
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
        buffer.awaitClosed()
    }

    @Test fun closingSuspendedBufferPreventsTransmissionEvenAfterResume() = exercise { transport, session ->
        val buffer = CommandBuffer(session, this, 1) { _, _ -> }
        buffer.`suspend`()
        buffer.enqueue(line(1))
        buffer.start()
        buffer.enqueue(line(2)) // The worker has taken line 1 and is waiting at the suspension gate.
        buffer.close()
        buffer.resume()
        buffer.awaitClosed()
        assertTrue(transport.drainWrites().isEmpty())
        assertFails { buffer.enqueue(line(3)) }
    }

    private fun line(n: Int) = SourceCommand(n, "G1 X$n")
}
