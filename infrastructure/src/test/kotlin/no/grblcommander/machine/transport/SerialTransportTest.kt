package no.grblcommander.machine.transport

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SerialTransportTest {
    private class Port : SerialPortAdapter {
        var configuration: SerialConfiguration? = null
        var opens = true
        var reads = 0
        var writes = 2
        var closes = 0
        val written = mutableListOf<Byte>()
        val readStarted = CompletableDeferred<Unit>()
        override fun open(configuration: SerialConfiguration): Boolean {
            this.configuration = configuration
            return opens
        }
        override fun read(buffer: ByteArray): Int {
            readStarted.complete(Unit)
            return reads
        }
        override fun write(bytes: ByteArray, offset: Int): Int {
            val n = minOf(writes, bytes.size - offset)
            if (n > 0) written.addAll(bytes.slice(offset until offset + n))
            return n
        }
        override fun close() { closes++ }
    }

    @Test fun `explicit configuration partial writes and idempotent disposal`() = runBlocking {
        val port = Port()
        val config = SerialConfiguration("COM7", 230400, parity = SerialConfiguration.Parity.EVEN)
        val transport = SerialTransport(config, port)
        transport.connect()
        assertEquals(config, port.configuration)
        transport.write("\$I\n".toByteArray())
        assertEquals("\$I\n", port.written.toByteArray().decodeToString())
        transport.close()
        transport.close()
        assertEquals(1, port.closes)
        assertFailsWith<IOException> { transport.read(ByteArray(1)) }
        assertFailsWith<IllegalStateException> { transport.connect() }
    }

    @Test fun `open failure closes native resources`() = runBlocking {
        val port = Port().apply { opens = false }
        assertFailsWith<IOException> { SerialTransport(SerialConfiguration("test"), port).connect() }
        assertEquals(1, port.closes)
    }

    @Test fun `disconnect and write errors terminate transport`() = runBlocking {
        for (read in listOf(true, false)) {
            val port = Port().apply { reads = -1; writes = -1 }
            val transport = SerialTransport(SerialConfiguration("test"), port)
            transport.connect()
            assertFailsWith<IOException> {
                if (read) transport.read(ByteArray(1)) else transport.write(byteArrayOf(63))
            }
            assertEquals(1, port.closes)
        }
    }

    @Test fun `stalled writes have a deadline`() = runBlocking {
        val port = Port().apply { writes = 0 }
        val transport = SerialTransport(SerialConfiguration("test"), port, writeTimeoutMillis = 30)
        transport.connect()
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { transport.write(byteArrayOf(63)) }
        assertEquals(1, port.closes)
    }

    @Test fun `cancellation releases idle reader`() = runBlocking {
        withTimeout(5_000) {
            val port = Port()
            val transport = SerialTransport(SerialConfiguration("test"), port)
            transport.connect()
            val reader = launch { while (true) transport.read(ByteArray(8)) }
            port.readStarted.await()
            reader.cancelAndJoin()
            assertEquals(1, port.closes)
        }
    }

    @Test fun `native library loads on supported CI platforms`() {
        assertTrue(com.fazecast.jSerialComm.SerialPort.getVersion().isNotBlank())
        // Enumeration is valid even on CI hosts with no physical serial ports.
        com.fazecast.jSerialComm.SerialPort.getCommPorts()
    }
}
