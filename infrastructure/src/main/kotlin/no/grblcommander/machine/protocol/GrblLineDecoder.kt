package no.grblcommander.machine.protocol

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Incremental CR/LF framing with a hard bound for a broken/noisy controller. */
internal class GrblLineDecoder(private val maxLineBytes: Int = 4_096) {
    private val line = ByteArrayOutputStream()

    fun accept(bytes: ByteArray, count: Int, onLine: (String) -> Unit) {
        for (index in 0 until count) {
            val byte = bytes[index].toInt() and 0xff
            if (byte == 10 || byte == 13) {
                if (line.size() > 0) {
                    val text = line.toString(Charsets.UTF_8)
                    line.reset()
                    onLine(text)
                }
            } else {
                if (line.size() >= maxLineBytes) throw IOException("GRBL response exceeds $maxLineBytes bytes")
                line.write(byte)
            }
        }
    }
}
