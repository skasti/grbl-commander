package no.grblcommander.core.connection

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import no.grblcommander.machine.protocol.ControllerStatus
import no.grblcommander.machine.session.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerPollingTest {
    private val endpoint = ControllerEndpoint.Tcp("localhost", 4321)

    private class Session : MachineSession {
        override val state = MutableStateFlow(MachineSnapshot())
        override val communication = emptyFlow<CommunicationEvent>()
        override val recentCommunication = emptyList<CommunicationEvent>()
        override val droppedCommunicationEvents = MutableStateFlow(0L)
        var handshake: CompletableDeferred<Unit>? = null
        var failHandshake = false
        var closed = false
        var polls = 0
        var inFlight = 0
        var maximumInFlight = 0
        var onPoll: suspend () -> Unit = {}

        override suspend fun connect() {
            state.value = state.value.copy(connection = MachineSnapshot.Connection.CONNECTING)
            handshake?.await()
            if (failHandshake) {
                state.value = state.value.copy(connection = MachineSnapshot.Connection.FAILED)
                throw IOException("Handshake failed")
            }
            state.value = state.value.copy(connection = MachineSnapshot.Connection.CONNECTED)
        }
        override suspend fun sendRealtime(command: RealtimeCommand) {
            assertEquals(RealtimeCommand.STATUS_QUERY, command)
            check(!closed)
            polls++
            inFlight++
            maximumInFlight = maxOf(maximumInFlight, inFlight)
            try { onPoll() } finally { inFlight-- }
        }
        override suspend fun executeLine(command: String): CommandAcknowledgment = error("Polling cannot send normal commands")
        override suspend fun queryStatus(): ControllerStatus = error("Polling cannot wait for a status response")
        override suspend fun reset(): Unit = error("Polling cannot reset")
        override fun close() {
            closed = true
            if (state.value.connection != MachineSnapshot.Connection.FAILED)
                state.value = state.value.copy(connection = MachineSnapshot.Connection.CLOSED)
        }
        override suspend fun awaitClosed() = Unit
    }

    @Test fun `polling waits for handshake and sends status only every 500ms without inventing reports`() = runTest {
        val session = Session().apply { handshake = CompletableDeferred() }
        val owner = ControllerConnection(this, { _, _ -> session })
        try {
            val attempt = async { owner.connect(endpoint) }
            runCurrent()
            advanceTimeBy(2_000); runCurrent()
            assertEquals(0, session.polls)
            session.handshake!!.complete(Unit)
            attempt.await(); runCurrent()
            advanceTimeBy(499); runCurrent()
            assertEquals(0, session.polls)
            advanceTimeBy(1); runCurrent()
            assertEquals(1, session.polls)
            advanceTimeBy(500); runCurrent()
            assertEquals(2, session.polls)
            assertNull(owner.state.value.snapshot.reportedAt)
            assertTrue(owner.state.value.reportIsStale(Instant.now()))
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun `slow polling writes never overlap or build a catch-up queue`() = runTest {
        val session = Session().apply { onPoll = { delay(1_200) } }
        val owner = ControllerConnection(this, { _, _ -> session })
        try {
            owner.connect(endpoint); runCurrent()
            advanceTimeBy(500); runCurrent()
            assertEquals(1, session.inFlight)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(1, session.polls)
            advanceTimeBy(200); runCurrent()
            assertEquals(0, session.inFlight)
            advanceTimeBy(499); runCurrent()
            assertEquals(1, session.polls)
            advanceTimeBy(1); runCurrent()
            assertEquals(2, session.polls)
            assertEquals(1, session.maximumInFlight)
        } finally { owner.close(); owner.awaitClosed() }
        assertEquals(0, session.inFlight)
    }

    @Test fun `disconnect cancels in-flight polling and reconnect owns only one loop`() = runTest {
        val sessions = mutableListOf<Session>()
        val owner = ControllerConnection(this, { _, _ -> Session().also { sessions += it } })
        try {
            owner.connect(endpoint); runCurrent()
            sessions.single().onPoll = { awaitCancellation() }
            advanceTimeBy(500); runCurrent()
            assertEquals(1, sessions.single().inFlight)
            owner.disconnect()
            assertEquals(0, sessions.single().inFlight)
            advanceTimeBy(2_000); runCurrent()
            assertEquals(1, sessions.single().polls)
            owner.connect(endpoint); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            assertEquals(1, sessions.first().polls)
            assertEquals(2, sessions.last().polls)
            owner.reconfigure(endpoint)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(2, sessions.last().polls)
            assertEquals(2, sessions.size)
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun `recoverable write failure and timeout preserve polling but transport failure stops it`() = runTest {
        val session = Session()
        val owner = ControllerConnection(this, { _, _ -> session })
        try {
            owner.connect(endpoint); runCurrent()
            session.onPoll = { throw IOException("Local write failure") }
            advanceTimeBy(500); runCurrent()
            assertEquals(1, session.polls)
            session.onPoll = { withTimeout(100) { awaitCancellation() } }
            advanceTimeBy(500); runCurrent()
            assertEquals(2, session.polls)
            advanceTimeBy(100); runCurrent()
            session.onPoll = {}
            advanceTimeBy(500); runCurrent()
            assertEquals(3, session.polls)
            session.state.value = session.state.value.copy(connection = MachineSnapshot.Connection.FAILED)
            advanceTimeBy(2_000); runCurrent()
            assertEquals(3, session.polls)
            assertEquals(1, session.maximumInFlight)
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun `reset handshake pauses polling while alarm and unsynchronized commands do not`() = runTest {
        val session = Session()
        val owner = ControllerConnection(this, { _, _ -> session })
        try {
            owner.connect(endpoint); runCurrent()
            session.state.value = session.state.value.copy(resetInProgress = true)
            advanceTimeBy(1_500); runCurrent()
            assertEquals(0, session.polls)
            session.state.value = session.state.value.copy(resetInProgress = false, resetRequired = true,
                alarmCode = 1, normalCommandsSynchronized = false)
            advanceTimeBy(500); runCurrent()
            assertEquals(1, session.polls)
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun `failed handshake never starts polling and owner or parent shutdown cancels in-flight writes`() = runTest {
        val failed = Session().apply { failHandshake = true }
        val failedOwner = ControllerConnection(this, { _, _ -> failed })
        try {
            assertFailsWith<IOException> { failedOwner.connect(endpoint) }
            advanceTimeBy(1_000); runCurrent()
            assertEquals(0, failed.polls)
        } finally { failedOwner.close(); failedOwner.awaitClosed() }
        for (cancelParent in listOf(false, true)) {
            val parent = SupervisorJob(coroutineContext[Job])
            val session = Session().apply { onPoll = { awaitCancellation() } }
            val owner = ControllerConnection(CoroutineScope(coroutineContext + parent), { _, _ -> session })
            try {
                owner.connect(endpoint); runCurrent()
                advanceTimeBy(500); runCurrent()
                assertEquals(1, session.inFlight)
                if (cancelParent) parent.cancel() else owner.close()
                owner.awaitClosed()
                assertEquals(0, session.inFlight)
                advanceTimeBy(1_000); runCurrent()
                assertEquals(1, session.polls)
                assertTrue(session.closed)
            } finally { owner.close(); owner.awaitClosed(); parent.cancel() }
        }
    }
}
