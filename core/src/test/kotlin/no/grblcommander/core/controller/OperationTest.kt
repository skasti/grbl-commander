package no.grblcommander.core.controller

import kotlin.test.*

class OperationTest {
    @Test fun validatesAndKeepsTheOriginalSource() {
        assertFailsWith<IllegalArgumentException> { Operation(listOf("G1 X1", "?"), "MDI") }
        val lines = mutableListOf("N7 G1 X1", "N7 G1 X2", "N9 G1 X3")
        val op = Operation(lines, "MDI")
        lines.clear()
        assertEquals(3, op.lines.size)
    }

    @Test fun indexesOnlyUnambiguousSourceNumbers() {
        val program = LineProgram(listOf("N7 G1 X1", "N7 G1 X2", "N9 G1 X3"))
        assertEquals(mapOf(9 to 3), program.lineNumbers)
    }
}
