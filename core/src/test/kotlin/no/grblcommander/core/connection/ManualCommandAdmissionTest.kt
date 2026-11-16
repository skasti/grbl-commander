package no.grblcommander.core.connection

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import no.grblcommander.machine.protocol.ControllerStatus
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.MachineSnapshot
import no.grblcommander.machine.session.RealtimeCommand
import kotlin.test.*

class ManualCommandAdmissionTest {
    @Test fun `acknowledged motion can be resumed after feed hold without a pending command`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = ConnectionTestTransport()
            val owner = ControllerConnection(this, { _, child -> GrblMachineSession(transport, child) })
            try {
                owner.connect(ControllerEndpoint.Tcp("localhost", 1234))
                val command = async { owner.executeLine("G1 X100 F100") }
                assertEquals("G1 X100 F100\n", transport.nextNonStatusWrite())
                transport.emit("ok\n")
                command.await()
                assertFalse(owner.state.value.manualCommandPending)
                transport.statusReport = "<Run|MPos:1,0,0>"
                transport.emit(transport.statusReport + "\n")
                owner.state.first { it.snapshot.status?.executionState == "Run" }
                assertFailsWith<IllegalStateException> { owner.sendRealtime(RealtimeCommand.CYCLE_START) }
                owner.sendRealtime(RealtimeCommand.FEED_HOLD)
                assertEquals("!", transport.nextNonStatusWrite())
                transport.statusReport = "<Hold:1|MPos:2,0,0>"
                transport.emit(transport.statusReport + "\n")
                owner.state.first { it.snapshot.status?.executionState == "Hold:1" }
                assertFailsWith<IllegalStateException> { owner.sendRealtime(RealtimeCommand.CYCLE_START) }
                transport.statusReport = "<Hold:0|MPos:3,0,0>"
                transport.emit(transport.statusReport + "\n")
                owner.state.first { it.canCycleStart() }
                assertFalse(owner.state.value.manualCommandPending)
                owner.sendRealtime(RealtimeCommand.CYCLE_START)
                assertEquals("~", transport.nextNonStatusWrite())
                assertTrue(transport.drainWrites().all { it == "?" }) // No replay of acknowledged motion.
            } finally { owner.close(); owner.awaitClosed() }
        }
    }

    @Test fun `fresh synchronized reports gate normal commands and explicit cycle start`() {
        val now = Instant.now()
        val idle = ControllerConnectionState(snapshot = MachineSnapshot(
            connection = MachineSnapshot.Connection.CONNECTED,
            status = ControllerStatus("Idle", null, null, null, emptyMap()), reportedAt = now,
        ))
        assertTrue(idle.canExecuteLine(now))
        assertTrue(idle.canCycleStart(now))
        val held = idle.copy(snapshot = idle.snapshot.copy(status = idle.snapshot.status!!.copy(executionState = "Hold:0")))
        assertFalse(held.canExecuteLine(now))
        assertTrue(held.canCycleStart(now))
        assertTrue(held.copy(manualCommandPending = true).canCycleStart(now))
        for (snapshot in listOf(
            held.snapshot.copy(normalCommandsSynchronized = false), held.snapshot.copy(resetInProgress = true),
            held.snapshot.copy(resetRequired = true), held.snapshot.copy(alarmCode = 1),
            held.snapshot.copy(reportedAt = now.minusSeconds(4)), held.snapshot.copy(connection = MachineSnapshot.Connection.DISCONNECTED),
            *listOf("Run", "Run:1", "Hold:1", "Jog", "Door:0", "Door:1", "Alarm", "Check", "Home", "Sleep", "Unknown", "Hold").map {
                held.snapshot.copy(status = held.snapshot.status!!.copy(executionState = it))
            }.toTypedArray(),
            held.snapshot.copy(status = null),
        )) assertFalse(held.copy(snapshot = snapshot).canCycleStart(now))
        assertFalse(idle.copy(manualCommandPending = true).canExecuteLine(now))
        assertFalse(idle.copy(closing = true).canExecuteLine(now))
        assertFalse(held.copy(closing = true).canCycleStart(now))
        assertFalse(idle.canExecuteLine(now.plusSeconds(4)))
    }

    @Test fun `pending manual line excludes conflicts but realtime hold resume and reset stay available`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = ConnectionTestTransport()
            val owner = ControllerConnection(this, { _, child -> GrblMachineSession(transport, child) })
            try {
                owner.connect(ControllerEndpoint.Tcp("localhost", 1234))
                owner.sendRealtime(RealtimeCommand.CYCLE_START)
                assertEquals("~", transport.nextNonStatusWrite())
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
                owner.sendRealtime(RealtimeCommand.CYCLE_START)
                assertEquals("~", transport.nextNonStatusWrite())
                owner.reset()
                assertEquals("\u0018", transport.nextNonStatusWrite())
                owner.disconnect()
                assertFalse(owner.state.value.canExecuteLine())
                assertFalse(owner.state.value.canCycleStart())
            } finally { owner.close(); owner.awaitClosed() }
        }
    }
}
