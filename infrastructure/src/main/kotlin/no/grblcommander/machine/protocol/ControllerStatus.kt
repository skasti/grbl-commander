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
    val isAlarm: Boolean get() = executionState.substringBefore(':') == "Alarm"
    /** Alarm substatus is optional; never interpret Hold/Run substates as alarm codes. */
    val alarmCode: Int? get() = if (isAlarm) {
        executionState.substringAfter(':', "").toIntOrNull()?.takeIf { it > 0 }
    } else null
}
