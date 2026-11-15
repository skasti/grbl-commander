package no.grblcommander.machine.protocol

import java.util.Collections

object GrblProtocol {
    fun parse(line: String): GrblMessage {
        if (line == "ok") return GrblMessage.Ok
        if (line.startsWith("error:")) line.substringAfter(':').toIntOrNull()?.let {
            return GrblMessage.Error(it)
        }
        if (line.startsWith("ALARM:")) line.substringAfter(':').toIntOrNull()?.takeIf { it > 0 }?.let {
            return GrblMessage.Alarm(it)
        }
        if (line.startsWith("[ALARMCODE:") && line.endsWith(']')) {
            val parts = line.substring(11, line.length - 1).split('|', limit = 3)
            val code = parts.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 }
            if (code != null && parts.size == 3 && parts[2].isNotBlank()) {
                return GrblMessage.AlarmDescription(code, parts[2])
            }
        }
        if (line.startsWith("Grbl ", ignoreCase = true) || line.startsWith("grblHAL ", ignoreCase = true)) {
            return GrblMessage.Startup(line)
        }
        if (line.startsWith('<') && line.endsWith('>')) {
            parseStatus(line)?.let { return GrblMessage.Status(it) }
        }
        return GrblMessage.Information(line)
    }

    private fun parseStatus(line: String): ControllerStatus? {
        val parts = line.substring(1, line.length - 1).split('|')
        if (parts[0].isBlank()) return null
        val fields = linkedMapOf<String, String>()
        for (part in parts.drop(1)) {
            val separator = part.indexOf(':')
            if (separator <= 0) return null
            val key = part.substring(0, separator)
            if (fields.put(key, part.substring(separator + 1)) != null) return null
        }
        fun position(key: String): List<Double>? {
            val value = fields[key] ?: return null
            val numbers = value.split(',').map { it.toDoubleOrNull() ?: Double.NaN }
            return if (numbers.all { it.isFinite() }) Collections.unmodifiableList(numbers) else null
        }
        val machine = position("MPos")
        val work = position("WPos")
        val offset = position("WCO")
        if (("MPos" in fields && machine == null) || ("WPos" in fields && work == null) ||
            ("WCO" in fields && offset == null)) return null
        return ControllerStatus(parts[0], machine, work, offset, Collections.unmodifiableMap(fields))
    }

    /** Reject embedded lines and real-time bytes which firmware executes out of band. */
    fun encodeCommand(command: String): ByteArray {
        require(command.isNotBlank()) { "Command must not be blank" }
        require(command.length <= 120) { "Command exceeds conservative GRBL line capacity (120 bytes)" }
        require(command.all { it.code in 32..126 && it !in "?!~" }) {
            "Use one ASCII line without embedded real-time commands"
        }
        return (command + "\n").toByteArray(Charsets.US_ASCII)
    }
}
