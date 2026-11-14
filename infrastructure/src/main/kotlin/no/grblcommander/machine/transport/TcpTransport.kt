package no.grblcommander.machine.transport

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    override val isConnected: Boolean
        get() = channel.get()?.let { !closed.get() && it.isOpen && it.isConnected } == true

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
        writer.withLock {
            try {
                withTimeout(writeTimeoutMillis) {
                    val socket = connectedChannel()
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) {
                        currentCoroutineContext().ensureActive()
                        if (socket.write(buffer) == 0) delay(5)
                    }
                }
            } catch (cancelled: CancellationException) {
                // NIO cancellation does not make the socket unusable. The session
                // owns command-delivery uncertainty and any partial line prefix.
                throw cancelled
            } catch (error: Throwable) {
                try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
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
