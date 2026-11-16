package no.grblcommander.core.connection

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import no.grblcommander.machine.protocol.ControllerStatus
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.MachineSnapshot
import no.grblcommander.machine.session.MachineSession
import no.grblcommander.machine.session.CommunicationEvent
import no.grblcommander.machine.session.RealtimeCommand
import kotlin.test.*

class ManualCommandAdmissionTest {
    @Test fun `delayed traffic for an earlier identical line cannot authorize the current command`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = ConnectionTestTransport()
            val releaseOld = CompletableDeferred<Unit>()
            val releaseCurrent = CompletableDeferred<Unit>()
            val currentObserved = CompletableDeferred<Unit>()
            val owner = ControllerConnection(this, { _, child ->
                val session = GrblMachineSession(transport, child)
                object : MachineSession by session {
                    override val communication = flow {
                        var commands = 0
                        session.communication.collect { event ->
                            if (event.category == CommunicationEvent.Category.COMMAND) {
                                if (++commands == 1) releaseOld.await()
                                else { currentObserved.complete(Unit); releaseCurrent.await() }
                            }
                            emit(event)
                        }
                    }
                }
            })
            try {
                owner.connect(ControllerEndpoint.Tcp("localhost", 1234))
                val first = async { owner.executeLine("\$I") }
                assertEquals("\$I\n", transport.nextNonStatusWrite())
                transport.emit("ok\n"); first.await()
                val second = async { owner.executeLine("\$I") }
                assertEquals("\$I\n", transport.nextNonStatusWrite())
                releaseOld.complete(Unit)
                currentObserved.await()
                assertFalse(owner.state.value.manualCommandTransmitted)
                releaseCurrent.complete(Unit)
                owner.state.first { it.manualCommandTransmitted }
                transport.emit("ok\n"); second.await()
            } finally { owner.close(); owner.awaitClosed() }
        }
    }

    @Test fun `fresh synchronized reports and operation ownership gate normal commands and resume`() {
        val now = Instant.now()
        val idle = ControllerConnectionState(snapshot = MachineSnapshot(
            connection = MachineSnapshot.Connection.CONNECTED,
            status = ControllerStatus("Idle", null, null, null, emptyMap()), reportedAt = now,
        ))
        assertTrue(idle.canExecuteLine(now))
        assertFalse(idle.canCycleStart(now))
        val held = idle.copy(snapshot = idle.snapshot.copy(status = idle.snapshot.status!!.copy(executionState = "Hold:0")))
        assertFalse(held.canExecuteLine(now))
        assertFalse(held.canCycleStart(now))
        assertFalse(held.copy(manualCommandPending = true).canCycleStart(now))
        assertTrue(held.copy(manualCommandPending = true, manualCommandTransmitted = true).canCycleStart(now))
        for (snapshot in listOf(
            held.snapshot.copy(normalCommandsSynchronized = false), held.snapshot.copy(resetInProgress = true),
            held.snapshot.copy(resetRequired = true), held.snapshot.copy(alarmCode = 1),
            held.snapshot.copy(reportedAt = now.minusSeconds(4)), held.snapshot.copy(connection = MachineSnapshot.Connection.DISCONNECTED),
            held.snapshot.copy(status = held.snapshot.status!!.copy(executionState = "Hold:1")),
        )) assertFalse(held.copy(snapshot = snapshot, manualCommandPending = true, manualCommandTransmitted = true).canCycleStart(now))
        assertFalse(idle.copy(manualCommandPending = true).canExecuteLine(now))
        assertFalse(idle.copy(closing = true).canExecuteLine(now))
        assertFalse(idle.canExecuteLine(now.plusSeconds(4)))
    }

    @Test fun `pending manual line excludes conflicts but realtime hold resume and reset stay available`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = ConnectionTestTransport()
            val owner = ControllerConnection(this, { _, child -> GrblMachineSession(transport, child) })
            try {
                owner.connect(ControllerEndpoint.Tcp("localhost", 1234))
                assertFailsWith<IllegalStateException> { owner.sendRealtime(RealtimeCommand.CYCLE_START) }
                assertFailsWith<IllegalArgumentException> { owner.executeLine("G0 X1\nG0 X2") }
                val command = async { owner.executeLine("G4 P1") }
                assertEquals("G4 P1\n", transport.nextNonStatusWrite())
                assertTrue(owner.state.value.manualCommandPending)
                assertFailsWith<IOException> { owner.executeLine("G0 X2") }
                owner.sendRealtime(RealtimeCommand.FEED_HOLD)
                assertEquals("!", transport.nextNonStatusWrite())
                transport.statusReport = "<Hold:0|MPos:0,0,0>"
                transport.emit(transport.statusReport + "\n")
                owner.state.first { it.canCycleStart() }
                owner.sendRealtime(RealtimeCommand.CYCLE_START)
                assertEquals("~", transport.nextNonStatusWrite())
                transport.emit("ok\n")
                command.await()
                assertFalse(owner.state.value.manualCommandPending)
                assertFailsWith<IllegalStateException> { owner.sendRealtime(RealtimeCommand.CYCLE_START) }
                owner.reset()
                assertEquals("\u0018", transport.nextNonStatusWrite())
                owner.disconnect()
                assertFalse(owner.state.value.canExecuteLine())
                assertFalse(owner.state.value.canCycleStart())
            } finally { owner.close(); owner.awaitClosed() }
        }
    }
}
