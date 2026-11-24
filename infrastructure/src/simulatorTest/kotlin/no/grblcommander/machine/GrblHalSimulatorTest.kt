package no.grblcommander.machine

import java.net.ConnectException
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.CommandCorrelationException
import no.grblcommander.machine.session.RealtimeCommand
import no.grblcommander.machine.session.MachineSnapshot.Connection
import no.grblcommander.machine.storage.GrblStorageBrowser
import no.grblcommander.machine.storage.GrblStorageReports
import no.grblcommander.machine.transport.TcpTransport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class GrblHalSimulatorTest {
    @TempDir lateinit var directory: Path

    private class Simulator(directory: Path, storage: Path? = null) : AutoCloseable {
        val port = ServerSocket(0).use { it.localPort }
        val log = directory.resolve("simulator.log")
        val process: Process = ProcessBuilder(buildList {
            addAll(listOf(System.getProperty("grblhal.simulator"), "-p", port.toString(), "-n"))
            if (storage != null) addAll(listOf("-d", storage.toString()))
        }).directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()

        suspend fun connect(scope: CoroutineScope): GrblMachineSession = withTimeout(10_000) {
            while (true) {
                check(process.isAlive) { "Simulator exited with ${process.exitValue()}" }
                val session = GrblMachineSession(
                    TcpTransport("127.0.0.1", port, connectTimeoutMillis = 500), scope,
                    handshakeTimeoutMillis = 5_000, commandTimeoutMillis = 5_000,
                )
                try {
                    session.connect()
                    return@withTimeout session
                } catch (notListening: ConnectException) {
                    session.close()
                    session.awaitClosed()
                    delay(25)
                } catch (error: Throwable) {
                    session.close()
                    withContext(NonCancellable) { session.awaitClosed() }
                    throw error
                }
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        }

        override fun close() {
            // Upstream may not exit on SIGTERM; forced termination is bounded too.
            try {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
                check(process.waitFor(3, TimeUnit.SECONDS)) { "Simulator did not terminate" }
            } finally {
                process.outputStream.close()
                process.inputStream.close()
                process.errorStream.close()
            }
        }
    }

    @Test fun `official simulator identification status non-motion commands and disconnect`() = runBlocking<Unit> {
        val simulator = Simulator(directory)
        var session: GrblMachineSession? = null
        try {
            withTimeout(20_000) {
                val connected = simulator.connect(this)
                session = connected
                assertEquals(Connection.CONNECTED, connected.state.value.connection)
                val identification = connected.executeLine("\$I")
                assertTrue(identification.accepted)
                assertTrue(identification.responseLines.any { it.startsWith("[VER:") })
                assertTrue(identification.responseLines.any { it == "[DRIVER:Simulator]" })
                val status = connected.queryStatus()
                assertTrue(status.executionState in setOf("Idle", "Alarm"))
                assertEquals(3, assertNotNull(status.machinePosition).size)
                assertNotNull(connected.state.value.reportedAt)
                val rejected = connected.executeLine("G999")
                assertFalse(rejected.accepted)
                assertNotNull(rejected.errorCode)
                assertEquals(Connection.CONNECTED, connected.state.value.connection)
                val modal = connected.executeLine("\$G")
                assertTrue(modal.accepted)
                assertTrue(modal.responseLines.any { it.startsWith("[GC:") })
                // Kill the actual peer and verify production EOF handling and observation health.
                simulator.close()
                connected.state.first { it.connection == Connection.FAILED }
                assertNotNull(connected.state.value.status)
            }
        } catch (error: Throwable) {
            throw AssertionError("Simulator test failed. Output:\n${Files.readString(simulator.log)}", error)
        } finally {
            withContext(NonCancellable) {
                try {
                    session?.close()
                    withTimeout(3_000) { session?.awaitClosed() }
                } finally { simulator.close() }
            }
            assertFalse(simulator.process.isAlive)
        }
    }

    @Test fun `official hard limit discards dwell response and recovers on same TCP session after reset`() = runBlocking<Unit> {
        val sessionScope = this
        val simulator = Simulator(directory)
        var session: GrblMachineSession? = null
        try {
            withTimeout(30_000) {
                supervisorScope {
                    val connected = simulator.connect(sessionScope)
                    session = connected
                    assertTrue(connected.executeLine("\$21=1").accepted) // isolated simulator NVS only
                    val dwell = async { connected.executeLine("G4 P60") } // non-motion pending operation
                    yield()
                    connected.queryStatus()
                    assertFalse(dwell.isCompleted)
                    simulator.process.outputStream.write('x'.code) // upstream simulator hardware-input toggle
                    simulator.process.outputStream.flush()
                    connected.state.first { it.alarmCode == 1 }
                    assertFailsWith<CommandCorrelationException> { dwell.await() }
                    assertEquals(Connection.CONNECTED, connected.state.value.connection)
                    assertTrue(connected.state.value.resetRequired)
                    assertFailsWith<CommandCorrelationException> { connected.executeLine("\$X") }
                    var sequence = connected.state.value.reportSequence
                    connected.sendRealtime(RealtimeCommand.STATUS_QUERY_ALL)
                    val blocking = connected.state.first { it.reportSequence > sequence && it.status?.blockingCriticalEvent == true }
                    assertEquals(1, blocking.status?.alarmCode)
                    simulator.process.outputStream.write('x'.code) // explicitly remove the simulated cause
                    simulator.process.outputStream.flush()
                    withTimeout(5_000) {
                        while (connected.queryStatus().fields["Pn"]?.contains('X') == true) delay(25)
                    }
                    connected.reset() // same transport; no reconnect, replay, unlock or homing
                    assertEquals(Connection.CONNECTED, connected.state.value.connection)
                    assertTrue(connected.state.value.normalCommandsSynchronized)
                    assertFalse(connected.state.value.resetRequired)
                    sequence = connected.state.value.reportSequence
                    connected.sendRealtime(RealtimeCommand.STATUS_QUERY_ALL)
                    val retained = connected.state.first { it.reportSequence > sequence && it.status?.blockingCriticalEvent == false }
                    assertEquals(1, retained.status?.alarmCode) // core retains code after its blocking loop exits
                    assertFalse(retained.resetRequired)
                    assertTrue(connected.executeLine("\$X").accepted) // operator-requested second recovery step
                    assertEquals("Idle", connected.queryStatus().executionState)
                    val identification = connected.executeLine("\$I")
                    assertTrue(identification.accepted)
                    assertTrue(identification.responseLines.any { it.startsWith("[VER:") })
                    assertTrue(simulator.process.isAlive)
                }
            }
        } catch (error: Throwable) {
            throw AssertionError("Simulator alarm test failed. Output:\n${Files.readString(simulator.log)}", error)
        } finally {
            withContext(NonCancellable) {
                try {
                    session?.close()
                    withTimeout(3_000) { session?.awaitClosed() }
                } finally { simulator.close() }
            }
            assertFalse(simulator.process.isAlive)
        }
    }

    @Test fun browsesMountedVfsOverTcp() = runBlocking<Unit> {
        val storage = Files.createDirectory(directory.resolve("storage"))
        Files.writeString(storage.resolve("readme.dat"), "hello")

        Simulator(directory, storage).use { simulator ->
            withTimeout(20_000) {
                val session = simulator.connect(this)
                try {
                    val browser = GrblStorageBrowser(session::executeLine)
                    assertTrue(browser.mounts().any { it.path == "/" && it.driver == "PosixFs" })
                    val rootFile = browser.list("/").entries.single()
                    assertEquals("/readme.dat", rootFile.path)
                    assertEquals(5L, rootFile.sizeBytes)

                    // The simulator's recursive VFS traversal can fail when the root already contains nested files.
                    val jobs = Files.createDirectory(storage.resolve("jobs"))
                    Files.createDirectory(jobs.resolve("empty"))
                    Files.writeString(jobs.resolve("notes.dat"), "abc")
                    val nested = browser.list("/jobs")
                    assertTrue(nested.mayOmitEmptyDirectories)
                    val nestedFile = nested.entries.single()
                    assertEquals("/jobs/notes.dat", nestedFile.path)
                    assertEquals(3L, nestedFile.sizeBytes)
                    assertEquals("/", GrblStorageReports.workingDirectory(session.executeLine("\$PWD").responseLines))
                    assertTrue(session.executeLine("\$G").accepted)
                } finally {
                    session.close()
                    withContext(NonCancellable) { session.awaitClosed() }
                }
            }
        }
    }

    @Test fun `fixture reaps simulator when test body fails`() {
        val simulator = Simulator(directory)
        val expected = IllegalStateException("deliberate test body failure")
        val caught = kotlin.test.assertFailsWith<IllegalStateException> {
            simulator.use { throw expected }
        }
        assertTrue(caught === expected)
        assertFalse(simulator.process.isAlive)
    }
}
