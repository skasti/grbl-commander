package no.grblcommander.machine.transport

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Non-blocking NIO keeps connect/read/write cancellable on Linux and Windows. */
class TcpTransport(
    val host: String,
    val port: Int,
    private val connectTimeoutMillis: Long = 5_000,
    private val writeTimeoutMillis: Long = 5_000,
) : MachineTransport {
    init {
        require(host.isNotBlank())
        require(port in 1..65535)
        require(connectTimeoutMillis > 0 && writeTimeoutMillis > 0)
    }

    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val channel = AtomicReference<SocketChannel?>()
    private val writer = Mutex()

    override suspend fun connect() {
        check(started.compareAndSet(false, true)) { "Transport is one-shot" }
        try {
            withTimeout(connectTimeoutMillis) {
                val address = runInterruptible(Dispatchers.IO) { InetSocketAddress(host, port) }
                val socket = SocketChannel.open()
                channel.set(socket)
                if (closed.get()) throw IOException("Transport closed")
                socket.configureBlocking(false)
                socket.socket().tcpNoDelay = true
                socket.connect(address)
                while (!socket.finishConnect()) delay(5)
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override suspend fun read(buffer: ByteArray): Int {
        require(buffer.isNotEmpty())
        return try {
            val count = connectedChannel().read(ByteBuffer.wrap(buffer))
            if (count == 0) delay(5)
            if (count < 0) close()
            count
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override suspend fun write(bytes: ByteArray) {
        try {
            writer.withLock {
                withTimeout(writeTimeoutMillis) {
                    val socket = connectedChannel()
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) {
                        if (socket.write(buffer) == 0) delay(5)
                    }
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun connectedChannel(): SocketChannel {
        if (closed.get()) throw IOException("Transport closed")
        return channel.get()?.takeIf { it.isConnected }
            ?: throw IOException("Transport not connected")
    }

    override fun close() {
        closed.set(true)
        channel.getAndSet(null)?.close()
    }
}
