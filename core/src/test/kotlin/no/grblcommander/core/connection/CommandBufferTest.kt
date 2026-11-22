package no.grblcommander.core.connection

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import no.grblcommander.machine.session.GrblMachineSession
import kotlin.test.*

class CommandBufferTest {
    @Test fun processesEveryReplyWhileCollectorIsSuspended() = runBlocking {
        withTimeout(5000) {
            val transport = ConnectionTestTransport()
            val session = GrblMachineSession(transport, this)
            val buffer = CommandBuffer(session)
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                buffer.state.collect { awaitCancellation() }
            }
            try {
                session.connect()
                val responses = mutableListOf<BufferState>()
                val batch = buffer.prepareBatch(
                    { listOf("N70 G0 X1", "N90 G0 X2", "G0 X3") },
                    mapOf(70 to 1, 90 to 2),
                ) { responses += buffer.state.value }
                batch.start()
                assertEquals("N70 G0 X1\n", transport.nextNonStatusWrite())
                buffer.state.first { it.reply == BufferState.Reply.Pending }
                transport.emit("<Run|Ln:70>\n")
                session.state.first { it.status?.executingLineNumber == 70 }
                buffer.refreshExecution()
                assertEquals(BufferState(1, BufferState.Reply.Pending, 1), buffer.state.value)
                transport.emit("ok\n")
                assertEquals("N90 G0 X2\n", transport.nextNonStatusWrite())
                buffer.state.first { it.lastSentLineNumber == 2 }
                assertEquals(BufferState(2, BufferState.Reply.Pending, 1), buffer.state.value)
                transport.emit("<Run|MPos:0,0,0>\n")
                session.state.first { it.status?.executingLineNumber == null }
                buffer.refreshExecution()
                assertNull(buffer.state.value.executingLineNumber)
                transport.emit("error:20\n")
                batch.join()
                assertEquals(listOf(BufferState(1, BufferState.Reply.Ok, 1),
                    BufferState(2, BufferState.Reply.Error(20))), responses)
                assertTrue(transport.drainWrites().isEmpty())

                val next = buffer.prepareBatch({ listOf("G4 P0") }) {}
                assertEquals(BufferState(), buffer.state.value)
                next.start()
                buffer.state.first { it.reply == BufferState.Reply.Pending }
                buffer.cancelBatch()
                assertEquals(BufferState(1, BufferState.Reply.Unknown), buffer.state.value)
            } finally {
                observer.cancelAndJoin()
                session.close()
                session.awaitClosed()
            }
        }
    }
}
