package no.grblcommander.machine.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControllerAlarmTest {
    @Test fun `criticality exactly matches alarm_is_critical in pinned core`() {
        assertEquals(setOf(1, 2, 10, 17, 20), (1..22).filter { ControllerAlarm.fromCode(it).isCritical == true }.toSet())
        assertTrue((1..22).all { ControllerAlarm.fromCode(it).description != null })
        assertEquals(ControllerAlarm.Category.HOMING_REQUIRED, ControllerAlarm.fromCode(11).category)
        assertFalse(ControllerAlarm.fromCode(11).isCritical!!)
        assertEquals(ControllerAlarm.Category.SYSTEM_FAILURE, ControllerAlarm.fromCode(22).category)
        assertEquals(ControllerAlarm.Category.SOFT_LIMIT, ControllerAlarm.fromCode(2).category)
        assertNull(ControllerAlarm.fromCode(200).isCritical)
        assertNull(ControllerAlarm.fromCode(200).description)
        assertEquals("Plugin fault", ControllerAlarm.fromCode(200, "Plugin fault").description)
    }

    @Test fun `alarm substatus is parsed only for Alarm and remains optional`() {
        for ((state, code) in listOf("Alarm:11" to 11, "Alarm:200" to 200, "Alarm" to null, "Alarm:bad" to null,
            "Alarm:0" to null, "Hold:0" to null, "Run:2" to null)) {
            val status = assertIs<GrblMessage.Status>(GrblProtocol.parse("<$state|MPos:0,0,0>")).report
            assertEquals(state, status.executionState)
            assertEquals(code, status.alarmCode)
        }
    }

    @Test fun `controller enumeration preserves extension descriptions`() {
        assertEquals(GrblMessage.AlarmDescription(200, "Driver fault | details"),
            GrblProtocol.parse("[ALARMCODE:200||Driver fault | details]"))
        assertIs<GrblMessage.Information>(GrblProtocol.parse("[ALARMCODE:bad||text]"))
    }
}
