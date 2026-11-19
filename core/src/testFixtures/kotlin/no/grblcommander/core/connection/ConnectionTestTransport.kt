package no.grblcommander.core.connection

import java.io.IOException
import kotlinx.coroutines.channels.Channel
import no.grblcommander.machine.transport.MachineTransport

class ConnectionTestTransport : MachineTransport {
    private val input = Channel<ByteArray>(Channel.UNLIMITED)
    val writes = Channel<String>(Channel.UNLIMITED)
    var closeCount = 0
    var closed = false
        private set
    private var connected = false
    override val isConnected: Boolean get() = connected && !closed
    var autoStatus = true
    var autoReset = true
    var connectCount = 0
    var statusReport = "<Idle|MPos:0,0,0|FS:0,0>"
    var localWriteFailure: Throwable? = null
    var onWrite: (suspend (String) -> Unit)? = null
    var writeFailure = false
    private var remaining = byteArrayOf()
    suspend fun nextNonStatusWrite(): String {
        while (true) {
            val write = writes.receive()
            if (write != "?") return write
        }
    }
    fun drainWrites(): List<String> = buildList {
        while (true) add(writes.tryReceive().getOrNull() ?: break)
    }
    override suspend fun connect() { check(!closed); connected = true; connectCount++ }
    override suspend fun read(buffer: ByteArray): Int {
        if (remaining.isEmpty()) remaining = input.receiveCatching().getOrNull() ?: return -1
        val count = minOf(buffer.size, remaining.size)
        remaining.copyInto(buffer, endIndex = count)
        remaining = remaining.copyOfRange(count, remaining.size)
        return count
    }
    override suspend fun write(bytes: ByteArray) {
        if (closed) throw IOException("Transport closed")
        if (writeFailure) {
            close()
            throw IOException("write failed")
        }
        localWriteFailure?.let { localWriteFailure = null; throw it }
        val text = bytes.decodeToString()
        writes.send(text)
        onWrite?.invoke(text)
        if (text == "\u0018" && autoReset) emit("GrblHAL 1.1f ['$' for help]\r\n")
        if ((text == "?" || bytes.contentEquals(byteArrayOf(0x87.toByte()))) && autoStatus) emit("$statusReport\r\n")
    }
    suspend fun emit(text: String) { input.send(text.toByteArray()) }
    fun disconnect() { connected = false; input.close() }
    override fun close() { closeCount++; closed = true; input.close() }
}


/** Lifecycle/terminal fixtures opt out of optional startup metadata queries. */
fun noPostConnectActions() = PostConnectActions(
    firmwareInfo = PostConnectActions.FirmwareInfo.OFF, parameters = false, parserState = false,
)
