package no.grblcommander.core.controller

import kotlin.test.*
import no.grblcommander.machine.session.CommandAcknowledgment

class OperationTest {
    @Test fun validatesAndKeepsTheOriginalSource() {
        assertFailsWith<IllegalArgumentException> { Operation(listOf("G1 X1", "?"), "MDI") }
        val lines = mutableListOf("N7 G1 X1", "N7 G1 X2", "N9 G1 X3")
        val op = Operation(lines, "MDI")
        lines.clear()
        assertEquals(3, op.lines.size)
        assertTrue(op.reserve())
        assertFalse(op.reserve())
        op.observe(7, 1, false)
        assertNull(op.progress.value.executing)
        op.observe(9, 2, false)
        assertEquals(3, op.progress.value.executing)
        val last = SourceCommand(3, op.lines.last())
        op.outcome(last, CommandOutcome.Reply(CommandAcknowledgment(null, emptyList())), 3)
        op.observe(null, 3, true)
        assertEquals(Operation.Phase.WAITING_FOR_IDLE, op.progress.value.phase)
        op.observe(null, 4, true)
        assertEquals(Operation.Phase.COMPLETED, op.progress.value.phase)
    }
}
