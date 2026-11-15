package no.grblcommander.machine.protocol

/** Protocol classification from grblHAL core 23bf875 (alarms.h / alarms.c). */
data class ControllerAlarm(
    val code: Int,
    val category: Category,
    /** null for extensions whose criticality is not known by this implementation. */
    val isCritical: Boolean?,
    val description: String?,
) {
    enum class Category {
        CRITICAL_STOP, SOFT_LIMIT, ABORTED_CYCLE, PROBING, HOMING_FAILURE,
        HOMING_REQUIRED, INPUT_PROTECTION, SPINDLE_OR_MODBUS, SYSTEM_FAILURE, UNKNOWN,
    }

    companion object {
        fun fromCode(code: Int, reportedDescription: String? = null): ControllerAlarm {
            val category = when (code) {
                1, 10, 17, 20 -> Category.CRITICAL_STOP
                2 -> Category.SOFT_LIMIT
                3 -> Category.ABORTED_CYCLE
                4, 5 -> Category.PROBING
                in 6..9, 15, 18 -> Category.HOMING_FAILURE
                11 -> Category.HOMING_REQUIRED
                12, 13 -> Category.INPUT_PROTECTION
                14, 19 -> Category.SPINDLE_OR_MODBUS
                16, 21, 22 -> Category.SYSTEM_FAILURE
                else -> Category.UNKNOWN
            }
            val critical = if (category == Category.UNKNOWN) null else code in setOf(1, 2, 10, 17, 20)
            val description = reportedDescription ?: when (code) {
                1 -> "Hard limit triggered; position may be lost. Rehoming is recommended."
                2 -> "Soft limit exceeded; core normally retains position. Critical protocol handling still requires reset."
                3 -> "Cycle interrupted; position may be lost. Rehoming is recommended."
                4, 5 -> "Probing failed; investigate before explicitly requesting another operation."
                in 6..9, 15, 18 -> "Homing failed; investigate the cause. Homed state is not established."
                10 -> "Emergency stop asserted; remove the cause before reset."
                11 -> "Homing is required; the application/operator may explicitly request \$H."
                12, 13 -> "Limit or probe protection active; remove the cause before continuing."
                14, 19 -> "Spindle or Modbus fault; investigate equipment and communication."
                16, 21, 22 -> "Self-test, nonvolatile storage or buffer fault; dedicated diagnosis is required."
                17 -> "Motor fault; remove the cause before reset."
                20 -> "I/O expander fault; investigate communication before reset."
                else -> null
            }
            return ControllerAlarm(code, category, critical, description)
        }
    }
}
