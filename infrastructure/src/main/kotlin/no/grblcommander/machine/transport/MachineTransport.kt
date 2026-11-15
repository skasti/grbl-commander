package no.grblcommander.machine.transport

import java.io.Closeable

/**
 * One-shot byte connection. A session exclusively owns a connected transport.
 * Implementations permit one reader and serialize concurrent writers. Successful
 * writes consume the full buffer; failed writes can leave a prefix. Read returns
 * 0 on its bounded poll timeout, -1 on EOF. Close is
 * idempotent, may race connect/read/write and unblocks outstanding I/O.
 * isConnected means no connection loss is known; it does not prove peer readiness.
 * Write cancellation preserves the connection but may leave an uncertain/partial
 * write. Other write failures must update isConnected before throwing if the
 * transport is no longer usable. Local argument rejection sends no bytes.
 * Failed/closed transports cannot be reconnected; construct a fresh instance.
 */
interface MachineTransport : Closeable {
    val isConnected: Boolean
    suspend fun connect()
    suspend fun read(buffer: ByteArray): Int
    suspend fun write(bytes: ByteArray)
    override fun close()
}
