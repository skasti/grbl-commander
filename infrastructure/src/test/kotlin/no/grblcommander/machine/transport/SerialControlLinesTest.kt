package no.grblcommander.machine.transport

import kotlin.test.*

class SerialControlLinesTest {
    @Test fun `requested levels are seeded and applied after open without toggling`() {
        for (dtr in listOf(false, true)) for (rts in listOf(false, true)) {
            val calls = mutableListOf<String>()
            assertTrue(openWithControlLines(SerialConfiguration("COM3", dtr = dtr, rts = rts),
                { calls += "open"; true },
                { calls += "dtr:$it"; true }, { calls += "rts:$it"; true }))
            assertEquals(listOf("dtr:$dtr", "rts:$rts", "open", "dtr:$dtr", "rts:$rts"), calls)
        }
    }
    @Test fun `failed open and failed native application are reported`() {
        var calls = 0
        assertFalse(openWithControlLines(SerialConfiguration("COM3"), { false }, { calls++; true }, { calls++; true }))
        assertEquals(2, calls)
        assertFalse(openWithControlLines(SerialConfiguration("COM3"), { true }, { false }, { true }))
        assertFalse(openWithControlLines(SerialConfiguration("COM3"), { true }, { true }, { false }))
    }
}
