package no.grblcommander.machine

import java.net.ConnectException
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.MachineSnapshot.Connection
import no.grblcommander.machine.transport.TcpTransport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GrblHalSimulatorTest {
    @TempDir lateinit var directory: Path

    private class Simulator(directory: Path) : AutoCloseable {
        val port = ServerSocket(0).use { it.localPort }
        val log = directory.resolve("simulator.log")
        val process: Process = ProcessBuilder(
            System.getProperty("grblhal.simulator"), "-p", port.toString(), "-n",
        ).directory(directory.toFile())
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
