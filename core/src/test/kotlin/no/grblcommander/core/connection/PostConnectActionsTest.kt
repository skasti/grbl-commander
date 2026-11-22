package no.grblcommander.core.connection

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import no.grblcommander.configuration.ControllerEndpointPreferences
import no.grblcommander.machine.session.CommandCorrelationException
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.MachineSession
import no.grblcommander.machine.session.MachineSnapshot.Connection
import no.grblcommander.machine.session.RealtimeCommand
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class PostConnectActionsTest {
    private fun scenario(test: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10_000) { supervisorScope { test() } }
    }
    private val endpoint = ControllerEndpoint.Tcp("localhost", 1234)
    private val replacement = ControllerEndpoint.Tcp("localhost", 2345, noPostConnectActions())

    // Hold the real executeLine caller in cleanup after its command was interrupted.
    private class HeldQuery {
        val transport = ConnectionTestTransport()
        val job = CompletableDeferred<Job>()
        val cleaningUp = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val terminated = CompletableDeferred<Unit>()
        lateinit var session: MachineSession
            private set

        fun sessionFor(scope: CoroutineScope): MachineSession {
            val real = GrblMachineSession(transport, scope, commandTimeoutMillis = 60_000)
            session = real
            return object : MachineSession by real {
                override suspend fun awaitClosed() {
                    real.awaitClosed()
                    terminated.complete(Unit)
                }

                override suspend fun executeLine(
                    command: String,
                    expectedEpoch: Long,
                    onUncertainWrite: () -> Unit,
                    onSent: () -> Unit,
                ) = try {
                    job.complete(currentCoroutineContext().job)
                    real.executeLine(command, expectedEpoch, onUncertainWrite, onSent)
                } finally {
                    withContext(NonCancellable) {
                        cleaningUp.complete(Unit)
                        release.await()
                    }
                }
            }
        }
    }

    @Test fun disconnectWaitsForPostConnectTerminationAndThenAllowsReconnect() = scenario {
        val first = HeldQuery()
        val second = ConnectionTestTransport()
        val replacementCreated = CompletableDeferred<Unit>()
        lateinit var postConnectJob: Job
        val owner = ControllerConnection(this, { endpoint, scope ->
            if (endpoint == replacement) {
                assertTrue(first.terminated.isCompleted, "Old session must be joined before replacement")
                assertTrue(postConnectJob.isCompleted, "Old post-connect job must be terminated at factory entry")
                replacementCreated.complete(Unit)
                GrblMachineSession(second, scope)
            } else first.sessionFor(scope)
        })
        try {
            owner.connect(endpoint)
            assertEquals("\$I\n", first.transport.nextNonStatusWrite())
            postConnectJob = first.job.await()
            val disconnecting = async { owner.disconnect() }
            first.cleaningUp.await()
            assertTrue(first.transport.closed)
            assertTrue(first.job.await().isCancelled)
            assertFalse(first.job.await().isCompleted)
            assertFalse(disconnecting.isCompleted)

            val reconnecting = async(start = CoroutineStart.UNDISPATCHED) { owner.connect(replacement) }
            assertFalse(replacementCreated.isCompleted)
            assertEquals(0, second.connectCount)
            assertEquals(endpoint, owner.state.value.endpoint)
            assertTrue(owner.state.value.closing)

            first.release.complete(Unit)
            disconnecting.await()
            reconnecting.await()
            assertTrue(first.job.await().isCompleted)
            assertTrue(replacementCreated.isCompleted)
            assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
            assertEquals(replacement, owner.state.value.endpoint)
            assertEquals(1, second.connectCount)
            assertTrue(first.transport.drainWrites().all { it == "?" })
        } finally { first.release.complete(Unit); owner.close(); owner.awaitClosed() }
    }

    @Test fun transportEofCancelsPostConnectAndSessionJoinWaitsForItsCleanup() = scenario {
        val first = HeldQuery()
        val owner = ControllerConnection(this, { _, scope -> first.sessionFor(scope) })
        try {
            owner.connect(endpoint)
            assertEquals("\$I\n", first.transport.nextNonStatusWrite())
            // This child completes on cancellation while the parent is still held in cleanup.
            val cancellation = Job(first.job.await())
            first.transport.disconnect()
            first.cleaningUp.await()
            val joined = async(start = CoroutineStart.UNDISPATCHED) { first.session.awaitClosed() }
            assertFalse(joined.isCompleted)
            cancellation.join()
            assertTrue(owner.state.value.postConnectPending)
            assertTrue(first.transport.closed)

            first.release.complete(Unit)
            joined.await()
            assertTrue(first.job.await().isCancelled)
            assertTrue(first.job.await().isCompleted)
            owner.state.first { it.snapshot.connection == Connection.FAILED && !it.postConnectPending }
            assertEquals(1, first.transport.closeCount)
            assertTrue(first.transport.drainWrites().all { it == "?" })
        } finally { first.release.complete(Unit); owner.close(); owner.awaitClosed() }
    }

    @Test fun ownerShutdownClearsPendingOnlyAfterPostConnectTerminates() = scenario {
        val first = HeldQuery()
        val owner = ControllerConnection(this, { _, scope -> first.sessionFor(scope) })
        try {
            owner.connect(endpoint)
            assertEquals("\$I\n", first.transport.nextNonStatusWrite())
            owner.close()
            first.cleaningUp.await()
            val joined = async(start = CoroutineStart.UNDISPATCHED) { owner.awaitClosed() }
            assertFalse(joined.isCompleted)
            assertTrue(owner.state.value.postConnectPending)

            first.release.complete(Unit)
            joined.await()
            assertTrue(first.job.await().isCompleted)
            assertEquals(Connection.CLOSED, owner.state.value.snapshot.connection)
            assertFalse(owner.state.value.postConnectPending)
            assertEquals(1, first.transport.closeCount)
        } finally { first.release.complete(Unit); owner.close(); owner.awaitClosed() }
    }

    @Test fun resetWaitsForInterruptedPostConnectAndDoesNotResumeIt() = scenario {
        val first = HeldQuery()
        val owner = ControllerConnection(this, { _, scope -> first.sessionFor(scope) })
        try {
            owner.connect(endpoint)
            assertEquals("\$I\n", first.transport.nextNonStatusWrite())
            val resetting = async { owner.reset() }
            first.cleaningUp.await()
            assertTrue(first.job.await().isCancelled)
            assertFalse(resetting.isCompleted)
            assertTrue(first.transport.drainWrites().all { it == "?" }, "Reset must wait for query cleanup")

            first.release.complete(Unit)
            assertEquals("\u0018", first.transport.nextNonStatusWrite())
            resetting.await()
            assertTrue(first.job.await().isCompleted)
            owner.state.first { !it.postConnectPending && it.snapshot.normalCommandsSynchronized }
            assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
            assertTrue(first.transport.drainWrites().all { it == "?" })
        } finally { first.release.complete(Unit); owner.close(); owner.awaitClosed() }
    }

    @Test fun lateAcknowledgmentDrainsTimedOutQueryWithoutResumingSequenceOrCompletingLaterLine() = scenario {
        val transport = ConnectionTestTransport()
        val timedOut = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        lateinit var session: GrblMachineSession
        val owner = ControllerConnection(this, { _, scope ->
            session = GrblMachineSession(transport, scope, commandTimeoutMillis = 1_000)
            object : MachineSession by session {
                override suspend fun executeLine(
                    command: String,
                    expectedEpoch: Long,
                    onUncertainWrite: () -> Unit,
                    onSent: () -> Unit,
                ) = try {
                    session.executeLine(command, expectedEpoch, onUncertainWrite, onSent)
                } catch (error: TimeoutCancellationException) {
                    timedOut.complete(Unit)
                    resume.await()
                    throw error
                }
            }
        })
        try {
            owner.connect(endpoint)
            assertEquals("\$I\n", transport.nextNonStatusWrite())
            timedOut.await()
            assertFalse(session.state.value.normalCommandsSynchronized)
            assertFailsWith<CommandCorrelationException> { session.executeLine("G0 X1") }
            assertTrue(transport.drainWrites().all { it == "?" })

            transport.emit("ok\r\n")
            session.state.first { it.normalCommandsSynchronized }
            resume.complete(Unit)
            owner.state.first { !it.postConnectPending }
            assertTrue(transport.drainWrites().all { it == "?" }, "Draining the old response must not resume post-connect")

            val later = async { owner.executeLine("G0 X2") }
            assertEquals("G0 X2\n", transport.nextNonStatusWrite())
            assertFalse(later.isCompleted)
            transport.emit("error:2\r\n")
            assertEquals(2, later.await().errorCode)
        } finally { resume.complete(Unit); owner.close(); owner.awaitClosed() }
    }

    @Test fun successfulQueriesWaitForEachAcknowledgmentAndRunOnceInOrder() = scenario {
        val transport = ConnectionTestTransport()
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope) })
        try {
            owner.connect(endpoint)
            val queries = listOf("\$I", "$#", "\$G")
            for (query in queries) {
                assertEquals("$query\n", transport.nextNonStatusWrite())
                assertTrue(owner.state.value.postConnectPending)
                assertTrue(transport.drainWrites().all { it == "?" })
                transport.emit("ok\r\n")
            }
            val finished = owner.state.first { !it.postConnectPending }
            assertEquals(queries.map { "$it acknowledged." }, finished.postConnectResults)
            assertTrue(transport.drainWrites().all { it == "?" })
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun statusAndFeedHoldBypassPendingPostConnectAcknowledgment() = scenario {
        val transport = ConnectionTestTransport()
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope) })
        try {
            owner.connect(endpoint)
            assertEquals("\$I\n", transport.nextNonStatusWrite())

            owner.sendRealtime(RealtimeCommand.STATUS_QUERY)
            assertEquals("?", transport.writes.receive())
            owner.sendRealtime(RealtimeCommand.FEED_HOLD)
            assertEquals("!", transport.nextNonStatusWrite())
            assertTrue(owner.state.value.postConnectPending)
            assertFailsWith<IOException> { owner.executeLine("G0 X1") }

            transport.emit("ok\n")
            assertEquals("$#\n", transport.nextNonStatusWrite())
            transport.emit("ok\n")
            assertEquals("\$G\n", transport.nextNonStatusWrite())
            transport.emit("ok\n")
            owner.state.first { !it.postConnectPending }
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun alarmBetweenAcknowledgedLinesInvalidatesTheRestEvenAfterIdleReport() = scenario {
        val transport = ConnectionTestTransport()
        val firstAccepted = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val owner = ControllerConnection(this, { _, scope ->
            val real = GrblMachineSession(transport, scope)
            object : MachineSession by real {
                override suspend fun executeLine(
                    command: String,
                    expectedEpoch: Long,
                    onUncertainWrite: () -> Unit,
                    onSent: () -> Unit,
                ) =
                    real.executeLine(command, expectedEpoch, onUncertainWrite, onSent).also {
                        if (command == "\$I") {
                            firstAccepted.complete(Unit)
                            resume.await()
                        }
                    }
            }
        })
        try {
            owner.connect(endpoint)
            assertEquals("\$I\n", transport.nextNonStatusWrite())
            transport.emit("ok\n")
            firstAccepted.await()
            transport.emit("ALARM:3\n")
            owner.state.first { it.snapshot.alarmCode == 3 }
            transport.emit("<Idle|MPos:0,0,0>\n")
            owner.state.first { it.snapshot.alarmCode == null && it.snapshot.normalCommandsSynchronized }

            resume.complete(Unit)
            val finished = owner.state.first { !it.postConnectPending }
            assertTrue(finished.postConnectResults.first().contains("acknowledged"))
            assertTrue(finished.postConnectResults.last().contains("skipped"))
            assertTrue(transport.drainWrites().all { it == "?" })
        } finally { resume.complete(Unit); owner.close(); owner.awaitClosed() }
    }

    @Test fun orderedQueriesAndUnsupportedExtension() = scenario {
        val transport = ConnectionTestTransport().apply {
            onWrite = { line -> if (line.endsWith('\n')) emit(if (line == "\$I+\n") "error:3\r\n" else "[MSG:metadata]\r\nok\r\n") }
        }
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope, commandTimeoutMillis = 500) })
        try {
            owner.connect(ControllerEndpoint.Tcp("localhost", 1234, PostConnectActions(firmwareInfo = PostConnectActions.FirmwareInfo.EXTENDED)))
            owner.state.first { !it.postConnectPending }
            assertEquals(listOf("\$I+\n", "$#\n", "\$G\n"), transport.drainWrites().filter { it.endsWith('\n') })
            assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
            assertTrue(owner.state.value.postConnectResults.first().contains("error:3"))
            assertTrue(owner.state.value.snapshot.normalCommandsSynchronized)
            assertFalse(owner.state.value.postConnectPending)
            assertTrue(owner.diagnosticSnapshot().any { it.event.text.contains("error:3") })
        } finally { owner.close(); owner.awaitClosed() }
    }
    @Test fun timeoutPreservesConnectionAndStatus() = scenario {
        val transport = ConnectionTestTransport()
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope, commandTimeoutMillis = 60) })
        try {
            owner.connect(ControllerEndpoint.Tcp("localhost", 1234))
            owner.state.first { !it.postConnectPending && !it.snapshot.normalCommandsSynchronized }
            assertEquals(listOf("\$I\n"), transport.drainWrites().filter { it.endsWith('\n') })
            assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
            assertFalse(owner.state.value.snapshot.normalCommandsSynchronized)
            assertTrue(owner.state.value.postConnectResults.any { it.contains("skipped") })
            owner.sendRealtime(RealtimeCommand.STATUS_QUERY)
            assertEquals("?", transport.writes.receive())
            assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
        } finally { owner.close(); owner.awaitClosed() }
    }
    @Test fun manualResetRequiresConsentAndPrecedesQueries() = scenario {
        val transport = ConnectionTestTransport().apply { onWrite = { if (it.endsWith('\n')) emit("ok\r\n") } }
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope) })
        val endpoint = ControllerEndpoint.Tcp("localhost", 1234, PostConnectActions(resetController = true))
        try {
            assertFailsWith<IllegalStateException> { owner.connect(endpoint) }
            assertEquals(0, transport.connectCount)
            owner.connect(endpoint, resetConfirmed = true)
            // The session observer publishes independently of post-connect job completion.
            owner.state.first { !it.postConnectPending && it.snapshot.normalCommandsSynchronized }
            val writes = transport.drainWrites()
            assertEquals(1, writes.count { it == "\u0018" })
            assertTrue(writes.indexOf("\u0018") < writes.indexOf("\$I\n"))
            assertTrue(owner.state.value.snapshot.normalCommandsSynchronized)
        } finally { owner.close(); owner.awaitClosed() }
    }
    @Test fun startupSkipsResetAndRunsQueries() = scenario {
        val transport = ConnectionTestTransport().apply { onWrite = { if (it.endsWith('\n')) emit("ok\r\n") } }
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope) })
        val node = Preferences.userRoot().node("no/grblcommander/tests/" + UUID.randomUUID())
        try {
            ControllerEndpointPreferences(node).save(ControllerEndpointRecord.encode(ControllerEndpoint.Tcp("localhost", 1234, PostConnectActions(resetController = true))))
            val settings = ControllerSettings(owner, ControllerEndpointPreferences(node))
            settings.onUiReady()
            settings.initialize()
            owner.state.first { !it.postConnectPending }
            val writes = transport.drainWrites()
            assertFalse(writes.contains("\u0018"))
            assertEquals(listOf("\$I\n", "$#\n", "\$G\n"), writes.filter { it.endsWith('\n') })
            assertTrue(owner.state.value.postConnectResults.first().contains("skipped"))
        } finally { owner.close(); owner.awaitClosed(); node.removeNode() }
    }
    @Test fun criticalAlarmSkipsQueriesAndRetainsRealtimeControl() = scenario {
        val transport = ConnectionTestTransport().apply {
            onWrite = { if (it == "\$I\n") emit("ALARM:1\r\n") }
        }
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope) })
        try {
            owner.connect(ControllerEndpoint.Tcp("localhost", 1234))
            owner.state.first { !it.postConnectPending && it.snapshot.resetRequired }
            assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
            assertTrue(owner.state.value.snapshot.resetRequired)
            assertEquals(listOf("\$I\n"), transport.drainWrites().filter { it.endsWith('\n') })
            assertTrue(owner.state.value.postConnectResults.any { it.contains("skipped") })
            owner.sendRealtime(RealtimeCommand.STATUS_QUERY)
            assertEquals("?", transport.writes.receive())
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun recoverableAlarmStopsQueriesEvenAfterTheOldAcknowledgmentDrains() = scenario {
        val transport = ConnectionTestTransport()
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope) })
        try {
            owner.connect(endpoint)
            assertEquals("\$I\n", transport.nextNonStatusWrite())
            transport.emit("ALARM:3\r\n")
            val interrupted = owner.state.first { !it.postConnectPending && it.snapshot.alarmCode == 3 }
            assertEquals(Connection.CONNECTED, interrupted.snapshot.connection)
            assertFalse(interrupted.snapshot.resetRequired)
            assertFalse(interrupted.snapshot.normalCommandsSynchronized)
            assertTrue(interrupted.postConnectResults.any { it.contains("skipped", ignoreCase = true) })

            transport.emit("ok\r\n")
            owner.state.first { it.snapshot.normalCommandsSynchronized }
            assertTrue(transport.drainWrites().all { it == "?" })
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun failedResetIsVisibleAndNeverRetriesOrRunsQueries() = scenario {
        val transport = ConnectionTestTransport().apply { autoReset = false }
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope, handshakeTimeoutMillis = 500) })
        try {
            owner.connect(ControllerEndpoint.Tcp("localhost", 1234, PostConnectActions(resetController = true)), resetConfirmed = true)
            owner.state.first { !it.postConnectPending }
            val writes = transport.drainWrites()
            assertEquals(1, writes.count { it == "\u0018" })
            assertTrue(writes.none { it.endsWith('\n') })
            assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
            assertTrue(owner.state.value.postConnectResults.single().contains("reset failed"))
            owner.sendRealtime(RealtimeCommand.STATUS_QUERY)
            assertEquals("?", transport.writes.receive())
        } finally { owner.close(); owner.awaitClosed() }
    }
    @Test fun successfulHandshakeReturnsWhileSessionOwnsPendingQueries() = scenario {
        val transport = ConnectionTestTransport()
        val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope) })
        try {
            val caller = launch { owner.connect(ControllerEndpoint.Tcp("localhost", 1234)) }
            caller.join()
            assertTrue(caller.isCompleted)
            assertTrue(owner.state.value.postConnectPending)
            assertEquals(Connection.CONNECTED, owner.state.value.snapshot.connection)
            assertFalse(transport.closed)
            assertEquals("\$I\n", transport.nextNonStatusWrite())
            assertFailsWith<java.io.IOException> { owner.executeLine("G0 X1") }
            transport.onWrite = { if (it.endsWith('\n')) transport.emit("ok\r\n") }
            transport.emit("ok\r\n")
            owner.state.first { !it.postConnectPending }
            assertEquals(listOf("$#\n", "\$G\n"), transport.drainWrites().filter { it.endsWith('\n') })
            assertFalse(transport.closed)
        } finally { owner.close(); owner.awaitClosed() }
    }
}
