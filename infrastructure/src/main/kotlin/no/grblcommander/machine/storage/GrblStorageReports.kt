package no.grblcommander.machine.storage

import java.io.IOException

/** Records emitted by the optional grblHAL filesystem stream plugin. */
object GrblStorageReports {
    data class Mount(val driver: String, val path: String, val capacityBytes: Long?, val freeBytes: Long?)
    data class Entry(val path: String, val sizeBytes: Long?, val directory: Boolean, val usable: Boolean)

    private val mount = Regex("""\[FS:([^@]+)@(/.*?)(?: size (\d+), free (\d+))?\]""")
    private val entry = Regex("""\[FILE:(.+)\|SIZE:(-1|\d+)(\|UNUSABLE)?\]""")

    fun mounts(lines: List<String>): List<Mount> = records(lines, "[FS:").map { line ->
        val (driver, path, capacity, free) = mount.matchEntire(line)?.destructured ?: malformed(line)
        val bytes = capacity.takeIf(String::isNotEmpty)?.let { size(it, line) }
        val freeBytes = free.takeIf(String::isNotEmpty)?.let { size(it, line) }
        if (bytes != null && freeBytes != null && freeBytes > bytes) malformed(line)
        Mount(driver, path.trimEnd('/').ifEmpty { "/" }, bytes, freeBytes)
    }

    fun workingDirectory(lines: List<String>): String {
        val line = records(lines, "[CWD:").singleOrNull()
            ?: throw IOException("Expected one grblHAL working-directory report")
        if (!line.endsWith(']') || !line.startsWith("[CWD:/")) malformed(line)
        return line.substring(5, line.lastIndex).trimEnd('/').ifEmpty { "/" }
    }

    fun entries(lines: List<String>): List<Entry> = records(lines, "[FILE:").map { line ->
        val (path, bytes, marker) = entry.matchEntire(line)?.destructured ?: malformed(line)
        val directory = bytes == "-1"
        Entry(path, if (directory) null else size(bytes, line), directory, marker.isEmpty())
    }

    private fun size(value: String, line: String): Long = value.toLongOrNull() ?: malformed(line)

    private fun records(lines: List<String>, prefix: String): List<String> = lines.filter { it.startsWith(prefix) }

    private fun malformed(line: String): Nothing = throw IOException("Malformed grblHAL storage report: $line")
}
