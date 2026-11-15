package no.grblcommander.machine.session

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import no.grblcommander.machine.protocol.ControllerAlarm
import no.grblcommander.machine.session.MachineSnapshot.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrblAlarmProtocolTest {
    private suspend fun connected(scope: CoroutineScope, transport: InMemoryTransport): GrblMachineSession {
        val session = GrblMachineSession(transport, scope, handshakeTimeoutMillis = 1_000)
        session.connect()
        assertEquals("?", transport.writes.receive())
        return session
    }

    @Test fun `critical alarms discard the response expectation and require reset even if ok arrives`() = runBlocking<Unit> {
        withTimeout(10_000) {
            for (code in listOf(1, 2, 10, 17, 20)) supervisorScope {
                val transport = InMemoryTransport()
                val session = connected(this, transport)
                try {
                    val line = async { session.executeLine("G4 P1") }
                    assertEquals("G4 P1\n", transport.writes.receive())
                    val queued = async { session.executeLine("\$I") }
                    yield()
                    transport.emit("ALARM:$code\n") // no command acknowledgment follows
                    assertFailsWith<CommandCorrelationException> { line.await() }
                    assertFailsWith<CommandCorrelationException> { queued.await() }
                    assertEquals(Connection.CONNECTED, session.state.value.connection)
                    assertEquals(code, session.state.value.alarmCode)
                    assertTrue(session.state.value.alarm!!.isCritical!!)
                    assertTrue(session.state.value.resetRequired)
                    assertFalse(session.state.value.normalCommandsSynchronized)
                    assertFailsWith<CommandCorrelationException> { session.executeLine("\$X") }
                    assertTrue(transport.writes.tryReceive().isFailure)
                    transport.statusReport = "<Alarm:$code|MPos:0,0,0>"
                    assertEquals(code, session.queryStatus().alarmCode)
                    assertEquals("?", transport.writes.receive())
                    transport.emit("ok\nerror:9\n<Idle|MPos:0,0,0>\n")
                    session.state.first { it.status?.executionState == "Idle" }
                    assertTrue(session.state.value.resetRequired) // neither an ack nor status exits the critical boundary
                    assertFalse(transport.closed)
                } finally { session.close(); session.awaitClosed() }
            }
        }
    }

    @Test fun `critical alarm reported only by status still blocks an empty queue`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            try {
                transport.emit("<Alarm:17|MPos:0,0,0|FW:grblHAL|\$C:1>\n")
                session.state.first { it.alarmCode == 17 && it.resetRequired }
                assertEquals(Connection.CONNECTED, session.state.value.connection)
                assertTrue(session.state.value.alarm!!.isCritical!!)
                assertFailsWith<CommandCorrelationException> { session.executeLine("\$X") }
                session.sendRealtime(RealtimeCommand.STATUS_QUERY_ALL)
                transport.writes.receive()
                assertFalse(transport.closed)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `noncritical missing acknowledgment can be recovered explicitly without replay`() = runBlocking<Unit> {
        withTimeout(5_000) {
            supervisorScope {
                val transport = InMemoryTransport()
                val session = connected(this, transport)
                try {
                    val line = async { session.executeLine("\$I") }
                    assertEquals("\$I\n", transport.writes.receive())
                    transport.emit("ALARM:4\n")
                    assertFailsWith<CommandCorrelationException> { line.await() }
                    assertFalse(session.state.value.resetRequired)
                    assertFalse(session.state.value.normalCommandsSynchronized)
                    session.sendRealtime(RealtimeCommand.FEED_HOLD)
                    assertEquals("!", transport.writes.receive())
                    session.reset()
                    assertEquals(Connection.CONNECTED, session.state.value.connection)
                    assertTrue(session.state.value.normalCommandsSynchronized)
                    assertEquals(1, transport.connectCount)
                    val sent = mutableListOf<String>()
                    while (true) sent += transport.writes.tryReceive().getOrNull() ?: break
                    assertTrue(sent.all { it == "\u0018" || it == "?" })
                    assertEquals(1, sent.count { it == "\u0018" })
                } finally { session.close(); session.awaitClosed() }
            }
        }
    }

    @Test fun `alarm after previous ok has no phantom pending acknowledgment`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            try {
                val previous = async { session.executeLine("\$I") }
                assertEquals("\$I\n", transport.writes.receive())
                transport.emit("ok\n")
                assertTrue(previous.await().accepted)
                transport.emit("ALARM:5\n")
                session.state.first { it.alarmCode == 5 }
                assertTrue(session.state.value.normalCommandsSynchronized)
                val recovery = async { session.executeLine("\$X") }
                assertEquals("\$X\n", transport.writes.receive())
                assertFalse(recovery.isCompleted)
                transport.emit("ok\n")
                assertTrue(recovery.await().accepted)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `homing-required status permits only explicitly submitted homing and repeated reports do not interrupt it`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            try {
                transport.emit("ALARM:11\n<Alarm:11|MPos:0,0,0>\n")
                session.state.first { it.status?.alarmCode == 11 }
                assertFalse(session.state.value.resetRequired)
                assertEquals(ControllerAlarm.Category.HOMING_REQUIRED, session.state.value.alarm!!.category)
                assertTrue(transport.writes.tryReceive().isFailure) // no automatic $X/$H/reset
                val home = async { session.executeLine("\$H") }
                assertEquals("\$H\n", transport.writes.receive())
                transport.statusReport = "<Alarm:11|MPos:0,0,0>"
                session.queryStatus()
                assertEquals("?", transport.writes.receive())
                assertFalse(home.isCompleted)
                assertTrue(session.state.value.normalCommandsSynchronized)
                transport.emit("<Home|MPos:0,0,0>\nok\n")
                assertTrue(home.await().accepted) // only acknowledgment, no locally inferred homing result
                assertEquals("Home", session.state.value.status?.executionState)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `status-only alarm codes and unknown controller descriptions reach the API`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            try {
                val enumeration = async { session.executeLine("\$EA") }
                assertEquals("\$EA\n", transport.writes.receive())
                transport.emit("[ALARMCODE:200||Extension drive fault]\nok\n")
                assertEquals(listOf("[ALARMCODE:200||Extension drive fault]"), enumeration.await().responseLines)
                transport.emit("<Alarm:200|MPos:0,0,0>\n")
                session.state.first { it.alarmCode == 200 }
                assertEquals("Extension drive fault", session.state.value.alarm!!.description)
                assertEquals(ControllerAlarm.Category.UNKNOWN, session.state.value.alarm!!.category)
                assertNull(session.state.value.alarm!!.isCritical)
                assertTrue(session.state.value.resetRequired)
                transport.emit("[ALARMCODE:200||Updated extension detail]\n")
                session.state.first { it.alarmDescription == "Updated extension detail" }
                assertEquals(Connection.CONNECTED, session.state.value.connection)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `bare alarm can be identified by a later alarm substatus without inventing criticality`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            try {
                transport.emit("<Alarm|MPos:0,0,0>\n")
                session.state.first { it.resetRequired }
                assertNull(session.state.value.alarmCode)
                session.sendRealtime(RealtimeCommand.STATUS_QUERY_ALL)
                transport.writes.receive()
                transport.emit("<Alarm:11|MPos:0,0,0>\n")
                session.state.first { it.alarmCode == 11 }
                assertFalse(session.state.value.resetRequired)
                assertTrue(session.state.value.normalCommandsSynchronized)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `explicit reset needs banner then fresh status and never replays old lines`() = runBlocking<Unit> {
        withTimeout(5_000) {
            supervisorScope {
                val transport = InMemoryTransport().apply { autoReset = false }
                val session = connected(this, transport)
                try {
                    val old = async { session.executeLine("G4 P1") }
                    assertEquals("G4 P1\n", transport.writes.receive())
                    transport.emit("ALARM:1\n")
                    assertFailsWith<CommandCorrelationException> { old.await() }
                    val reset = async { session.reset() }
                    assertEquals("\u0018", transport.writes.receive())
                    assertEquals("?", transport.writes.receive())
                    transport.emit("ok\nerror:9\n<Idle|MPos:9,9,9>\n")
                    session.state.first { it.status?.machinePosition == listOf(9.0, 9.0, 9.0) }
                    assertFalse(reset.isCompleted)
                    assertTrue(session.state.value.resetRequired)
                    transport.autoStatus = false
                    transport.emit("GrblHAL 1.1f ['$' for help]\n")
                    session.state.first { it.lastMessage?.startsWith("GrblHAL") == true }
                    assertFalse(reset.isCompleted)
                    transport.emit("ok\n<Alarm:1|MPos:1,2,3>\n") // old critical code can persist after reset
                    reset.await()
                    assertEquals(Connection.CONNECTED, session.state.value.connection)
                    assertEquals(1, session.state.value.alarmCode)
                    assertTrue(session.state.value.alarm!!.isCritical!!)
                    assertFalse(session.state.value.resetRequired)
                    assertTrue(session.state.value.normalCommandsSynchronized)
                    assertEquals(1, transport.connectCount)
                    while (true) {
                        val sent = transport.writes.tryReceive().getOrNull() ?: break
                        assertEquals("?", sent) // no replay/unlock/homing, only handshake polling
                    }
                    val unlock = async { session.executeLine("\$X") }
                    assertEquals("\$X\n", transport.writes.receive())
                    assertFalse(unlock.isCompleted)
                    transport.emit("ok\n<Idle|MPos:1,2,3>\n")
                    assertTrue(unlock.await().accepted)
                    session.state.first { it.alarmCode == null }
                    transport.emit("ALARM:1\n") // a new event with the same code requires a new reset
                    session.state.first { it.resetRequired }
                } finally { session.close(); session.awaitClosed() }
            }
        }
    }

    @Test fun `critical event reasserted after reset barrier is never cleared by handshake completion`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport().apply { autoReset = false }
            val session = connected(this, transport)
            try {
                val reset = async { session.reset() }
                assertEquals("\u0018", transport.writes.receive())
                transport.autoStatus = false
                transport.emit("GrblHAL 1.1f ['$' for help]\nALARM:2\n<Alarm:2|FW:grblHAL|\$C:1>\n")
                reset.await()
                assertEquals(Connection.CONNECTED, session.state.value.connection)
                assertEquals(2, session.state.value.alarmCode)
                assertTrue(session.state.value.resetRequired)
                assertFalse(session.state.value.normalCommandsSynchronized)
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `learning retained alarm code does not interrupt recovery but a reasserted blocking flag does`() = runBlocking<Unit> {
        withTimeout(5_000) {
            supervisorScope {
                val transport = InMemoryTransport()
                val session = connected(this, transport)
                try {
                    transport.emit("ALARM:1\n")
                    session.state.first { it.resetRequired }
                    transport.statusReport = "<Alarm|MPos:0,0,0>"
                    session.reset()
                    while (transport.writes.tryReceive().isSuccess) { /* consume reset/status writes */ }
                    val unlock = async { session.executeLine("\$X") }
                    assertEquals("\$X\n", transport.writes.receive())
                    transport.emit("<Alarm:1|FW:grblHAL>\n")
                    session.state.first { it.alarmCode == 1 }
                    assertFalse(session.state.value.resetRequired)
                    assertTrue(session.state.value.normalCommandsSynchronized)
                    assertFalse(unlock.isCompleted)
                    transport.emit("ok\n")
                    assertTrue(unlock.await().accepted)
                    val next = async { session.executeLine("\$I") }
                    assertEquals("\$I\n", transport.writes.receive())
                    transport.emit("<Alarm:1|FW:grblHAL|\$C:1>\n")
                    assertFailsWith<CommandCorrelationException> { next.await() }
                    assertTrue(session.state.value.resetRequired)
                } finally { session.close(); session.awaitClosed() }
            }
        }
    }

    private suspend fun resetFromCriticalAlarm(
        session: GrblMachineSession,
        transport: InMemoryTransport,
        report: String,
    ) {
        transport.emit("ALARM:1\n")
        session.state.first { it.resetRequired }
        transport.statusReport = report
        session.reset()
        assertFalse(session.state.value.resetRequired)
        assertTrue(session.state.value.normalCommandsSynchronized)
        while (transport.writes.tryReceive().isSuccess) { /* consume reset/status writes */ }
    }

    @Test fun `new status alarm after reset and Idle interrupts active and queued work`() = runBlocking<Unit> {
        withTimeout(15_000) {
            for (code in listOf(11, 1)) {
                for (fields in listOf("", "|FW:grblHAL", "|\$C:1", "|FW:grblHAL|\$C:1")) supervisorScope {
                    val transport = InMemoryTransport()
                    val session = connected(this, transport)
                    try {
                        resetFromCriticalAlarm(session, transport, "<Alarm:1|FW:grblHAL>")
                        transport.emit("<Idle|FW:grblHAL>\n")
                        session.state.first { it.status?.executionState == "Idle" }
                        val line = async { session.executeLine("G4 P1") }
                        assertEquals("G4 P1\n", transport.writes.receive())
                        val queued = async(start = CoroutineStart.UNDISPATCHED) { session.executeLine("\$I") }
                        transport.emit("<Alarm:$code$fields>\n")
                        assertFailsWith<CommandCorrelationException> { line.await() }
                        assertFailsWith<CommandCorrelationException> { queued.await() }
                        val resetRequired = code == 1 || fields.contains("\$C:1")
                        assertEquals(code, session.state.value.alarmCode)
                        assertEquals(resetRequired, session.state.value.resetRequired)
                        assertFalse(session.state.value.normalCommandsSynchronized)
                        assertEquals(Connection.CONNECTED, session.state.value.connection)
                        assertFailsWith<CommandCorrelationException> { session.executeLine("\$X") }
                        assertTrue(transport.writes.tryReceive().isFailure) // no replay or automatic recovery
                        session.sendRealtime(RealtimeCommand.FEED_HOLD)
                        assertEquals("!", transport.writes.receive())
                        transport.statusReport = "<Alarm:$code$fields>"
                        session.queryStatus()
                        assertEquals("?", transport.writes.receive())
                        // A late reply drains only a noncritical line's old response slot.
                        val sequence = session.state.value.reportSequence
                        transport.emit("ok\n<Alarm:$code$fields>\n")
                        session.state.first { it.reportSequence > sequence }
                        assertEquals(!resetRequired, session.state.value.normalCommandsSynchronized)
                        assertEquals(resetRequired, session.state.value.resetRequired)
                        if (!resetRequired) {
                            val home = async { session.executeLine("\$H") }
                            assertEquals("\$H\n", transport.writes.receive())
                            assertFalse(home.isCompleted)
                            transport.emit("ok\n")
                            assertTrue(home.await().accepted)
                        }
                        assertFalse(transport.closed)
                    } finally { session.close(); session.awaitClosed() }
                }
            }
        }
    }

    @Test fun `retained status alarm preserves pending unlock with partial and all-fields reports`() = runBlocking<Unit> {
        withTimeout(10_000) {
            for (fields in listOf("", "|FW:grblHAL")) {
                for (initialCode in listOf("", ":1")) {
                    val transport = InMemoryTransport()
                    val session = connected(this, transport)
                    try {
                        resetFromCriticalAlarm(session, transport, "<Alarm$initialCode$fields>")
                        val unlock = async { session.executeLine("\$X") }
                        assertEquals("\$X\n", transport.writes.receive())
                        val sequence = session.state.value.reportSequence
                        transport.emit("<Alarm:1$fields>\n<Alarm:1$fields>\n")
                        session.state.first { it.reportSequence >= sequence + 2 }
                        assertEquals(1, session.state.value.alarmCode)
                        assertFalse(session.state.value.resetRequired)
                        assertTrue(session.state.value.normalCommandsSynchronized)
                        assertFalse(unlock.isCompleted)
                        transport.emit("ok\n")
                        assertTrue(unlock.await().accepted)
                    } finally { session.close(); session.awaitClosed() }
                }
            }
        }
    }

    @Test fun `changed alarm code ends retained history even without intervening Idle`() = runBlocking<Unit> {
        withTimeout(5_000) {
            supervisorScope {
                val transport = InMemoryTransport()
                val session = connected(this, transport)
                try {
                    resetFromCriticalAlarm(session, transport, "<Alarm:1|FW:grblHAL>")
                    val unlock = async { session.executeLine("\$X") }
                    assertEquals("\$X\n", transport.writes.receive())
                    transport.emit("<Alarm:11|FW:grblHAL>\n")
                    assertFailsWith<CommandCorrelationException> { unlock.await() }
                    assertFalse(session.state.value.resetRequired)
                    assertFalse(session.state.value.normalCommandsSynchronized)
                    transport.emit("ok\n")
                    session.state.first { it.normalCommandsSynchronized }
                    val home = async { session.executeLine("\$H") }
                    assertEquals("\$H\n", transport.writes.receive())
                    transport.emit("<Alarm:1|FW:grblHAL>\n")
                    assertFailsWith<CommandCorrelationException> { home.await() }
                    assertTrue(session.state.value.resetRequired)
                } finally { session.close(); session.awaitClosed() }
            }
        }
    }

    @Test fun `explicit alarm notification supersedes retained status history in either message order`() = runBlocking<Unit> {
        withTimeout(10_000) {
            for (notificationFirst in listOf(false, true)) supervisorScope {
                val transport = InMemoryTransport()
                val session = connected(this, transport)
                try {
                    resetFromCriticalAlarm(session, transport, "<Alarm:1|FW:grblHAL>")
                    val unlock = async { session.executeLine("\$X") }
                    assertEquals("\$X\n", transport.writes.receive())
                    val status = "<Alarm:1|FW:grblHAL>\n"
                    transport.emit(if (notificationFirst) "ALARM:1\n$status" else "${status}ALARM:1\n")
                    assertFailsWith<CommandCorrelationException> { unlock.await() }
                    assertTrue(session.state.value.resetRequired)
                    assertFalse(session.state.value.normalCommandsSynchronized)
                    assertEquals(Connection.CONNECTED, session.state.value.connection)
                } finally { session.close(); session.awaitClosed() }
            }
        }
    }

    @Test fun `unseen alarm code after reset is not retained merely because blocking flag is absent`() = runBlocking<Unit> {
        withTimeout(10_000) {
            for (code in listOf(1, 11, 200)) {
                val transport = InMemoryTransport()
                val session = connected(this, transport)
                try {
                    transport.statusReport = "<Alarm:$code|FW:grblHAL>"
                    session.reset() // pre-reset state was Idle, so there is no known retained code
                    assertEquals(code, session.state.value.alarmCode)
                    assertEquals(code != 11, session.state.value.resetRequired)
                    assertEquals(code == 11, session.state.value.normalCommandsSynchronized)
                } finally { session.close(); session.awaitClosed() }
            }
        }
    }

    @Test fun `reset without banner times out without closing transport and a later explicit reset recovers`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport().apply { autoReset = false }
            val session = GrblMachineSession(transport, this, handshakeTimeoutMillis = 100)
            session.connect()
            try {
                assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { session.reset() }
                assertEquals(Connection.CONNECTED, session.state.value.connection)
                assertTrue(session.state.value.resetRequired)
                assertFalse(session.state.value.resetInProgress)
                assertFalse(transport.closed)
                assertEquals("Idle", session.queryStatus().executionState)
                assertTrue(session.state.value.resetRequired)
                transport.autoReset = true
                session.reset()
                assertFalse(session.state.value.resetRequired)
                assertEquals(1, transport.connectCount)
                val sent = mutableListOf<String>()
                while (true) sent += transport.writes.tryReceive().getOrNull() ?: break
                assertEquals(2, sent.count { it == "\u0018" }) // no automatic reset retry
            } finally { session.close(); session.awaitClosed() }
        }
    }

    @Test fun `reset transport failure marks connection failed`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = InMemoryTransport()
            val session = connected(this, transport)
            transport.writeFailure = true
            assertFailsWith<IOException> { session.reset() }
            session.awaitClosed()
            assertEquals(Connection.FAILED, session.state.value.connection)
            assertFalse(transport.isConnected)
        }
    }
}
