package no.grblcommander.machine.transport

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class SerialTransport internal constructor(
    val configuration: SerialConfiguration,
    private val adapter: SerialPortAdapter,
    private val connectTimeoutMillis: Long = 5_000,
    private val writeTimeoutMillis: Long = 5_000,
) : MachineTransport {
    constructor(configuration: SerialConfiguration) :
        this(configuration, JSerialCommAdapter(configuration.port))

    init { require(connectTimeoutMillis > 0 && writeTimeoutMillis > 0) }
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val connected = AtomicBoolean()
    private val writer = Mutex()

    override val isConnected: Boolean get() = connected.get() && !closed.get()

    override suspend fun connect() {
        check(started.compareAndSet(false, true)) { "Transport is one-shot" }
        try {
            if (closed.get()) throw IOException("Transport closed")
            withTimeout(connectTimeoutMillis) {
                runInterruptible(Dispatchers.IO) {
                    // Native open can finish while cancellation/close is occurring.
                    try {
                        if (!adapter.open(configuration)) throw IOException("Unable to open ${configuration.port}")
                        connected.set(true)
                    } finally {
                        if (closed.get() || Thread.currentThread().isInterrupted) adapter.close()
                    }
                }
            }
            if (closed.get()) throw IOException("Transport closed during connect")
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    override suspend fun read(buffer: ByteArray): Int {
        require(buffer.isNotEmpty())
        return try {
            checkConnected()
            val count = adapter.read(buffer)
            if (count < 0) throw IOException("Serial port disconnected or read failed")
            if (count == 0) delay(5)
            count
        } catch (error: Throwable) {
            terminate(error)
        }
    }

    override suspend fun write(bytes: ByteArray) {
        writer.withLock {
            try {
                withTimeout(writeTimeoutMillis) {
                    checkConnected()
                    var offset = 0
                    while (offset < bytes.size) {
                        currentCoroutineContext().ensureActive()
                        val count = adapter.write(bytes, offset)
                        if (count < 0) throw IOException("Serial port disconnected or write failed")
                        offset += count
                        if (count == 0) delay(5)
                    }
                }
            } catch (cancelled: CancellationException) {
                // Non-blocking polling can be cancelled without closing the port.
                throw cancelled
            } catch (error: Throwable) {
                terminate(error)
            }
        }
    }

    private fun checkConnected() {
        if (closed.get() || !connected.get()) throw IOException("Serial transport not connected")
    }

    private fun terminate(error: Throwable): Nothing {
        try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
        throw error
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) adapter.close()
    }
}
