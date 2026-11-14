package no.grblcommander.machine.transport

import java.io.IOException
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TcpTransportTest {
    @Test fun `loopback bytes EOF and one-shot lifecycle`() = runBlocking<Unit> {
        withTimeout(5_000) {
            ServerSocket(0).use { server ->
                val peer = async(Dispatchers.IO) { server.accept() }
                val transport = TcpTransport("127.0.0.1", server.localPort)
                transport.connect()
                peer.await().use { socket ->
                    transport.write("\$I\n".toByteArray())
                    assertEquals("\$I", socket.getInputStream().bufferedReader().readLine())
                    socket.getOutputStream().write("ok\r\n".toByteArray())
                    val bytes = ByteArray(16)
                    var n = 0
                    while (n == 0) n = transport.read(bytes)
                    assertEquals("ok\r\n", bytes.decodeToString(0, n))
                }
                val bytes = ByteArray(1)
                while (transport.read(bytes) == 0) { /* await EOF */ }
                assertFailsWith<IOException> { transport.write(byteArrayOf(63)) }
                assertFailsWith<IllegalStateException> { transport.connect() }
                transport.close()
                transport.close()
            }
        }
    }

    @Test fun `refused connect closes transport`() = runBlocking<Unit> {
        val port = ServerSocket(0).use { it.localPort }
        val transport = TcpTransport("127.0.0.1", port)
        assertFailsWith<IOException> { transport.connect() }
        assertFailsWith<IOException> { transport.read(ByteArray(1)) }
        transport.close()
    }

    @Test fun `close before connect cannot resurrect connection`() = runBlocking<Unit> {
        val transport = TcpTransport("127.0.0.1", 1)
        transport.close()
        assertFailsWith<IOException> { transport.connect() }
    }

    @Test fun `cancelling reader releases connection`() = runBlocking<Unit> {
        withTimeout(5_000) {
            ServerSocket(0).use { server ->
                val peer = async(Dispatchers.IO) { server.accept() }
                val transport = TcpTransport("127.0.0.1", server.localPort)
                transport.connect()
                peer.await().use {
                    val reader = launch { while (true) transport.read(ByteArray(8)) }
                    kotlinx.coroutines.yield()
                    reader.cancelAndJoin()
                    assertFailsWith<IOException> { transport.write(byteArrayOf(63)) }
                }
            }
        }
    }
}
