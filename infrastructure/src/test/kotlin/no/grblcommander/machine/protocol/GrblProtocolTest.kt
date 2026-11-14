package no.grblcommander.machine.protocol

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class GrblProtocolTest {
    @Test fun `recorded status preserves axes substate and extension fields`() {
        val message = assertIs<GrblMessage.Status>(GrblProtocol.parse(
            "<Hold:0|MPos:1.000,-2.000,0.500,42|WCO:0,1,2,3|FS:120,8000|Ov:100,100,90|Pn:P|Foo:bar>"
        ))
        assertEquals("Hold:0", message.report.executionState)
        assertEquals(listOf(1.0, -2.0, 0.5, 42.0), message.report.machinePosition)
        assertNull(message.report.workPosition)
        assertEquals("bar", message.report.fields["Foo"])
        assertEquals("120,8000", message.report.fields["FS"])
    }

    @Test fun `malformed or unknown input does not fabricate controller state`() {
        for (line in listOf("<>", "<Idle|MPos:NaN,0,0>", "<Idle|broken>", "<Idle|MPos:1|MPos:2>", "error:no", "something")) {
            assertEquals(GrblMessage.Information(line), GrblProtocol.parse(line))
        }
        assertEquals(GrblMessage.Ok, GrblProtocol.parse("ok"))
        assertEquals(GrblMessage.Error(20), GrblProtocol.parse("error:20"))
        assertEquals(GrblMessage.Alarm(1), GrblProtocol.parse("ALARM:1"))
        assertIs<GrblMessage.Startup>(GrblProtocol.parse("GrblHAL 1.1f ['$' or '$HELP' for help]"))
    }

    @Test fun `decoder handles fragmented coalesced and mixed CR LF lines`() {
        val decoder = GrblLineDecoder()
        val lines = mutableListOf<String>()
        for (part in listOf("o", "k\r", "\n<Idle|WPos:0,0,0>\n[VER:1.1f]", "\r\n")) {
            val bytes = part.toByteArray()
            decoder.accept(bytes, bytes.size, lines::add)
        }
        assertEquals(listOf("ok", "<Idle|WPos:0,0,0>", "[VER:1.1f]"), lines)
        assertFailsWith<IOException> { GrblLineDecoder(3).accept("abcd".toByteArray(), 4) {} }
    }

    @Test fun `normal command validation excludes line and realtime injection`() {
        assertEquals("\$I\n", GrblProtocol.encodeCommand("\$I").decodeToString())
        for (line in listOf("", "G0 X1\nG0 X2", "G0 X1\r", "?", "!", "~", "\u0018", "é", "a".repeat(121))) {
            assertFailsWith<IllegalArgumentException> { GrblProtocol.encodeCommand(line) }
        }
    }
}
