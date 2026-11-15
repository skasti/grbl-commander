package no.grblcommander.machine.protocol

/** One report, without predicted coordinates or inferred completion. */
data class ControllerStatus(
    val executionState: String,
    val machinePosition: List<Double>?,
    val workPosition: List<Double>?,
    val workCoordinateOffset: List<Double>?,
    /** Includes optional/unknown grblHAL fields without assuming capability support. */
    val fields: Map<String, String>,
) {
    /** Core includes $C:1 only while blocking; FW marks its all-fields report. */
    val blockingCriticalEvent: Boolean? get() = when {
        fields["\$C"] == "1" -> true
        fields["\$C"] == null && fields["FW"] == "grblHAL" -> false
        else -> null
    }

    val isAlarm: Boolean get() = executionState.substringBefore(':') == "Alarm"
    /** Alarm substatus is optional; never interpret Hold/Run substates as alarm codes. */
    val alarmCode: Int? get() = if (isAlarm) {
        executionState.substringAfter(':', "").toIntOrNull()?.takeIf { it > 0 }
    } else null
}
