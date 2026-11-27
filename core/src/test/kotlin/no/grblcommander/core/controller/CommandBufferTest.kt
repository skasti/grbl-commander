package no.grblcommander.core.controller

import java.io.IOException
import kotlinx.coroutines.*
import no.grblcommander.core.commands.Command
import no.grblcommander.core.commands.CommandBuffer
import no.grblcommander.core.commands.CommandResult
import no.grblcommander.core.commands.CommandSource
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
        val buffer = CommandBuffer(session, 3)
        buffer.bindReport { command, outcome, _ ->
            if (outcome is CommandResult.Reply) replies += checkNotNull(command.line)
        }
        buffer.enqueue(line(1))
        buffer.enqueue(line(2))
        buffer.enqueue(line(3))
        buffer.start(this)
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
        val buffer = CommandBuffer(session, 1)
        buffer.bindReport { command, outcome, _ ->
            if (command.line == 1 && outcome is CommandResult.Reply) firstReply.complete(Unit)
        }
        buffer.enqueue(line(1))
        buffer.start(this)
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
        val buffer = CommandBuffer(session)
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
        val buffer = CommandBuffer(session, 2)
        buffer.bindReport { _, _, _ -> }
        buffer.enqueue(line(1))
        buffer.enqueue(line(2))
        buffer.start(this)
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
        val outcomes = mutableListOf<Pair<Command, CommandResult>>()
        val buffer = CommandBuffer(session)
        buffer.bindReport { command, outcome, _ -> outcomes += command to outcome }
        buffer.enqueue(line(1))
        buffer.enqueue(line(2))
        buffer.start(this)
        assertEquals("G1 X1\n", transport.nextNonStatusWrite())
        transport.emit("error:20\n")
        buffer.awaitClosed()
        assertEquals(listOf(line(1), line(1)), outcomes.map { it.first })
        assertEquals(CommandResult.Sent, outcomes.first().second)
        val reply = assertIs<CommandResult.Reply>(outcomes.last().second)
        assertEquals(20, reply.acknowledgment.errorCode)
        assertTrue(transport.drainWrites().isEmpty())
    }

    @Test fun closeDuringAckWakesBlockedProducer() = exercise { transport, session ->
        val outcomes = mutableListOf<CommandResult>()
        val buffer = CommandBuffer(session, 1)
        buffer.bindReport { _, outcome, _ -> outcomes += outcome }
        buffer.start(this)
        buffer.enqueue(line(1))
        transport.nextNonStatusWrite()
        buffer.enqueue(line(2))
        val blocked = async(start = CoroutineStart.UNDISPATCHED) { runCatching { buffer.enqueue(line(3)) } }
        assertFalse(blocked.isCompleted)
        buffer.close()
        buffer.awaitClosed()
        assertTrue(blocked.await().isFailure)
        assertEquals(CommandResult.Sent, outcomes.first())
        assertIs<CommandResult.Interrupted>(outcomes.last())
        assertTrue(transport.drainWrites().isEmpty())
        assertFalse(transport.closed)
    }

    @Test fun closeWhileWaitingForWriterPreventsTransmission() = exercise { transport, session ->
        val release = CompletableDeferred<Unit>()
        transport.onWrite = { if (it == "!") release.await() }
        val realtime = launch { session.sendRealtime(RealtimeCommand.FEED_HOLD) }
        assertEquals("!", transport.nextNonStatusWrite())
        val outcomes = mutableListOf<CommandResult>()
        val buffer = CommandBuffer(session)
        buffer.bindReport { _, outcome, _ -> outcomes += outcome }
        buffer.enqueue(line(1))
        buffer.start(this)
        yield() // Let the worker run to the occupied session writer.
        buffer.close()
        buffer.awaitClosed()
        release.complete(Unit)
        realtime.join()
        assertIs<CommandResult.Interrupted>(outcomes.single())
        assertTrue(transport.drainWrites().isEmpty())
    }

    @Test fun uncertainTransmissionRetainsSourceIdentity() = exercise { transport, session ->
        transport.onWrite = { throw IOException("write outcome unknown") }
        val outcomes = mutableListOf<Pair<Command, CommandResult>>()
        val buffer = CommandBuffer(session)
        buffer.bindReport { command, outcome, _ -> outcomes += command to outcome }
        buffer.enqueue(line(7))
        buffer.start(this)
        buffer.awaitClosed()
        assertEquals(listOf(line(7), line(7)), outcomes.map { it.first })
        assertEquals(CommandResult.UncertainWrite, outcomes.first().second)
        assertIs<CommandResult.Interrupted>(outcomes.last().second)
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
        val outcomes = mutableListOf<CommandResult>()
        val buffer = CommandBuffer(session)
        buffer.bindReport { _, event, _ -> outcomes += event }
        buffer.enqueue(line(1))
        buffer.start(this)
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
        assertEquals(CommandResult.UncertainWrite, outcomes.first())
        assertIs<CommandResult.Interrupted>(outcomes.last())
    }

    @Test fun executionCancellationRetiresBufferWhileSessionStaysConnected() = exercise { transport, session ->
        val execution = Job(coroutineContext[Job])
        val executionScope = CoroutineScope(coroutineContext + execution)
        val buffer = CommandBuffer(session, capacity = 1)
        buffer.bindReport { _, _, _ -> }
        buffer.enqueue(line(1))
        buffer.start(executionScope)
        transport.nextNonStatusWrite()
        buffer.enqueue(line(2))
        val blocked = async(start = CoroutineStart.UNDISPATCHED) { runCatching { buffer.enqueue(line(3)) } }
        execution.cancelAndJoin()
        buffer.awaitClosed()
        assertTrue(blocked.await().isFailure)
        assertFails { buffer.start(executionScope) }
        assertTrue(transport.drainWrites().isEmpty())
        assertTrue(transport.isConnected)
    }

    @Test fun resuspendingBeforeWorkerDispatchKeepsCommandBlocked() = exercise { transport, session ->
        val buffer = CommandBuffer(session, 1)
        buffer.bindReport { _, _, _ -> }
        buffer.`suspend`()
        buffer.enqueue(line(1))
        buffer.start(this)
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
        val buffer = CommandBuffer(session, 1)
        buffer.bindReport { _, _, _ -> }
        buffer.`suspend`()
        buffer.enqueue(line(1))
        buffer.start(this)
        buffer.enqueue(line(2)) // The worker has taken line 1 and is waiting at the suspension gate.
        buffer.close()
        buffer.resume()
        buffer.awaitClosed()
        assertTrue(transport.drainWrites().isEmpty())
        assertFails { buffer.enqueue(line(3)) }
    }

    private fun line(n: Int) = Command(CommandSource.MDI, n, "G1 X$n")
}
