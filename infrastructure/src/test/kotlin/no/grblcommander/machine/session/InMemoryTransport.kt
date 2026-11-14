package no.grblcommander.machine.session

import java.io.IOException
import kotlinx.coroutines.channels.Channel
import no.grblcommander.machine.transport.MachineTransport

internal class InMemoryTransport : MachineTransport {
    private val input = Channel<ByteArray>(Channel.UNLIMITED)
    val writes = Channel<String>(Channel.UNLIMITED)
    var closed = false
        private set
    var autoStatus = true
    var onWrite: (suspend (String) -> Unit)? = null
    var writeFailure = false
    private var remaining = byteArrayOf()
    override suspend fun connect() { check(!closed) }
    override suspend fun read(buffer: ByteArray): Int {
        if (remaining.isEmpty()) remaining = input.receiveCatching().getOrNull() ?: return -1
        val count = minOf(buffer.size, remaining.size)
        remaining.copyInto(buffer, endIndex = count)
        remaining = remaining.copyOfRange(count, remaining.size)
        return count
    }
    override suspend fun write(bytes: ByteArray) {
        if (closed || writeFailure) throw IOException("write failed")
        val text = bytes.decodeToString()
        writes.send(text)
        onWrite?.invoke(text)
        if (text == "?" && autoStatus) emit("<Idle|MPos:0,0,0|FS:0,0>\r\n")
    }
    suspend fun emit(text: String) { input.send(text.toByteArray()) }
    fun disconnect() { input.close() }
    override fun close() { closed = true; input.close() }
}
