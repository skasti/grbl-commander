package no.grblcommander.core.connection

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.RealtimeCommand
import no.grblcommander.machine.storage.ControllerStorageException
import no.grblcommander.machine.storage.ControllerStorageException.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControllerStorageTest {
    private val endpoint = ControllerEndpoint.Tcp("localhost", 1234, noPostConnectActions())

    @Test
    fun listsNestedDirectoryAndRestoresCwd() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = ConnectionTestTransport()
            val owner = ControllerConnection(this, { _, child -> GrblMachineSession(transport, child) })
            try {
                owner.connect(endpoint)
                val discovery = async { owner.discoverStorage() }
                transport.reply("\$FI", "[FS:fatfs@/ size 7.45 GB, free 5.59 GB]\n[FS:littlefs@/littlefs]\nok")
                assertEquals(listOf("/", "/littlefs"), discovery.await().map { it.path })
                assertFalse(owner.controllerStorageReadSupported)

                val browse = async { owner.listStorage("/jobs/part a") }
                transport.reply("\$PWD", "[CWD:/]\nok")
                transport.reply("\$650", "\$650=4\nok")
                transport.reply("\$CWD=/jobs/part a", "ok")
                transport.reply("\$PWD", "[CWD:/jobs/part a]\nok")
                assertEquals("\$F+\n", transport.nextNonStatusWrite())

                assertTrue(owner.state.value.storagePending)
                assertFailsWith<IOException> { owner.executeLine("\$CWD=/elsewhere") }
                assertFailsWith<IOException> { owner.startOperation(Operation.Source.MDI, "MDI", listOf("G4 P0")) }
                assertFailsWith<IllegalStateException> { owner.sendRealtime(RealtimeCommand.CYCLE_START) }
                owner.sendRealtime(RealtimeCommand.FEED_HOLD)
                assertEquals("!", transport.nextNonStatusWrite())

                transport.emit("[FILE:..|SIZE:-1]\n[FILE:setup|SIZE:-1]\n[FILE:tool.txt|SIZE:127]\nok\n")
                transport.reply("\$CWD=/", "ok")
                val directory = browse.await()
                assertFalse(directory.mayOmitEmptyDirectories)
                assertEquals(listOf("/jobs/part a/setup", "/jobs/part a/tool.txt"), directory.entries.map { it.path })
                assertEquals(127L, directory.entries.last().sizeBytes)
                assertFalse(owner.state.value.storagePending)
            } finally {
                owner.close()
                owner.awaitClosed()
            }
        }
    }

    @Test
    fun listsRecursiveResultsAsImmediateChildrenWithoutChangingSettings() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = ConnectionTestTransport()
            val owner = ControllerConnection(this, { _, child -> GrblMachineSession(transport, child) })
            try {
                owner.connect(endpoint)
                val browse = async { owner.listStorage("/") }
                transport.reply("\$PWD", "[CWD:/]\nok")
                transport.reply("\$650", "error:53")
                transport.reply("\$F+", "[FILE:/jobs/nested/part.nc|SIZE:1]\n[FILE:/jobs/info.txt|SIZE:4]\n[FILE:/readme|SIZE:0]\nok")
                val directory = browse.await()
                assertTrue(directory.mayOmitEmptyDirectories)
                assertEquals(listOf("/jobs", "/readme"), directory.entries.map { it.path })
                assertTrue(directory.entries.first().directory)
            } finally {
                owner.close()
                owner.awaitClosed()
            }
        }
    }

    @Test
    fun distinguishesEmptyUnsupportedMediaAndIdleWithPendingSdJob() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = ConnectionTestTransport()
            val owner = ControllerConnection(this, { _, child -> GrblMachineSession(transport, child) })
            try {
                assertEquals(Kind.DISCONNECTED, assertFailsWith<ControllerStorageException> { owner.discoverStorage() }.kind)
                owner.connect(endpoint)
                val empty = async { owner.discoverStorage() }
                transport.reply("\$FI", "ok")
                assertTrue(empty.await().isEmpty())
                val unsupported = async { runCatching { owner.discoverStorage() } }
                transport.reply("\$FI", "error:3")
                assertEquals(Kind.UNSUPPORTED, (unsupported.await().exceptionOrNull() as ControllerStorageException).kind)
                val noMedia = async { runCatching { owner.listStorage("/") } }
                transport.reply("\$PWD", "[CWD:/]\nok")
                transport.reply("\$650", "\$650=4\nok")
                transport.reply("\$F+", "error:65")
                assertEquals(Kind.NOT_MOUNTED, (noMedia.await().exceptionOrNull() as ControllerStorageException).kind)
                val invalid = assertFailsWith<ControllerStorageException> { owner.listStorage("/jobs/\nG0 X1") }
                assertEquals(Kind.INVALID_PATH, invalid.kind)
                transport.statusReport = "<Idle|SD:Pending>"
                transport.emit(transport.statusReport + "\n")
                owner.state.first { it.snapshot.status?.fields?.get("SD") == "Pending" }
                assertEquals(Kind.BUSY, assertFailsWith<ControllerStorageException> { owner.discoverStorage() }.kind)
                assertTrue(transport.drainWrites().all { it == "?" })
            } finally {
                owner.close()
                owner.awaitClosed()
            }
        }
    }

    @Test
    fun cancelsWithoutSendingMoreCommandsWhenCorrelationIsLost() = runBlocking<Unit> {
        withTimeout(5_000) {
            val transport = ConnectionTestTransport()
            val owner = ControllerConnection(this, { _, child -> GrblMachineSession(transport, child) })
            try {
                owner.connect(endpoint)
                val browse = async { owner.listStorage("/jobs") }
                transport.reply("\$PWD", "[CWD:/]\nok")
                transport.reply("\$650", "\$650=4\nok")
                transport.reply("\$CWD=/jobs", "ok")
                transport.reply("\$PWD", "[CWD:/jobs]\nok")
                assertEquals("\$F+\n", transport.nextNonStatusWrite())
                browse.cancelAndJoin()
                owner.state.first { !it.storagePending && !it.snapshot.normalCommandsSynchronized }
                assertTrue(transport.drainWrites().all { it == "?" })
                transport.emit("[FILE:part.nc|SIZE:1]\nok\n")
                owner.state.first { it.snapshot.normalCommandsSynchronized }
                val next = async { owner.listStorage("/jobs") }
                transport.reply("\$PWD", "[CWD:/jobs]\nok")
                transport.reply("\$650", "\$650=4\nok")
                transport.reply("\$F+", "[FILE:..|SIZE:-1]\nok")
                assertTrue(next.await().entries.isEmpty())
            } finally {
                owner.close()
                owner.awaitClosed()
            }
        }
    }

    @Test
    fun resetsAndReconnectsWithoutContinuingAnOldBrowse() = runBlocking<Unit> {
        withTimeout(5_000) {
            val first = ConnectionTestTransport()
            val second = ConnectionTestTransport()
            val transports = listOf(first, second).iterator()
            val owner = ControllerConnection(this, { _, child -> GrblMachineSession(transports.next(), child) })
            try {
                owner.connect(endpoint)
                val resetBrowse = async { runCatching { owner.listStorage("/") } }
                assertEquals("\$PWD\n", first.nextNonStatusWrite())
                owner.reset()
                assertEquals("\u0018", first.nextNonStatusWrite())
                assertTrue(resetBrowse.await().exceptionOrNull() is CancellationException)
                val replaced = async { runCatching { owner.listStorage("/") } }
                assertEquals("\$PWD\n", first.nextNonStatusWrite())
                owner.connect(endpoint)
                assertEquals(Kind.DISCONNECTED, (replaced.await().exceptionOrNull() as ControllerStorageException).kind)
                assertTrue(first.closed)
                val discovery = async { owner.discoverStorage() }
                second.reply("\$FI", "[FS:littlefs@/littlefs]\nok")
                assertEquals("/littlefs", discovery.await().single().path)
            } finally {
                owner.close()
                owner.awaitClosed()
            }
        }
    }

    private suspend fun ConnectionTestTransport.reply(command: String, response: String) {
        assertEquals("$command\n", nextNonStatusWrite())
        emit("$response\n")
    }
}
