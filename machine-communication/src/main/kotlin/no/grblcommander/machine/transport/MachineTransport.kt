package no.grblcommander.machine.transport

import java.io.Closeable

/**
 * One-shot byte connection. A session exclusively owns a connected transport.
 * Implementations permit one reader and concurrent writers; writes are atomic
 * per call. Read returns 0 on its bounded poll timeout, -1 on EOF. Close is
 * idempotent, may race connect/read/write and unblocks outstanding I/O.
 * Failed/closed transports cannot be reconnected; construct a fresh instance.
 */
interface MachineTransport : Closeable {
    suspend fun connect()
    suspend fun read(buffer: ByteArray): Int
    suspend fun write(bytes: ByteArray)
    override fun close()
}
