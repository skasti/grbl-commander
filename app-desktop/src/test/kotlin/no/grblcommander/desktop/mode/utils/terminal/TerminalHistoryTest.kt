package no.grblcommander.desktop.mode.utils.terminal

import java.time.Instant
import no.grblcommander.core.connection.ControllerEndpoint
import no.grblcommander.core.connection.ControllerTraffic
import no.grblcommander.machine.session.CommunicationEvent
import kotlin.test.*

class TerminalHistoryTest {
    private val endpoint = ControllerEndpoint.Tcp("localhost", 1234, no.grblcommander.core.connection.noPostConnectActions())
    private fun event(cursor: Long, category: CommunicationEvent.Category = CommunicationEvent.Category.INFORMATION,
        session: Long = 1, sequence: Long = cursor) = ControllerTraffic(session, endpoint,
        CommunicationEvent(sequence, Instant.EPOCH, CommunicationEvent.Direction.RX, category, "line $cursor", CommunicationEvent.Outcome.RECEIVED), cursor)

    @Test fun `10000 admitted events survive filtered traffic and filter changes never reconstruct or delete`() {
        val history = TerminalHistory()
        val filters = TerminalFilters()
        for (cursor in 1L..10_000L) history.receive(event(cursor), filters.includes(event(cursor).event))
        for (cursor in 10_001L..30_000L) {
            val status = event(cursor, CommunicationEvent.Category.STATUS)
            history.receive(status, filters.includes(status.event))
        }
        assertEquals(10_000, history.size)
        assertEquals(1L, history.first().cursor)
        assertEquals(10_000L, history.last().cursor)
        filters.information = false
        filters.status = true
        assertEquals(10_000, history.size)
        val visible = event(30_001, CommunicationEvent.Category.STATUS)
        history.receive(visible, filters.includes(visible.event))
        assertEquals(10_000, history.size)
        assertEquals(2L, history.first().cursor)
        assertEquals(30_001L, history.last().cursor)
        assertEquals(0L, history.missed) // Neither filtered traffic nor normal eviction is a loss.
    }

    @Test fun `snapshot overlap and new sessions deduplicate while counting source and core losses only once`() {
        val history = TerminalHistory()
        val snapshot = listOf(event(500), event(504), event(505))
        history.start(snapshot)
        snapshot.forEach { history.receive(it, true) }
        assertFalse(history.receive(event(504), true)) // Queued live duplicate from before snapshot.
        history.receive(event(509, session = 2, sequence = 3), true)
        assertEquals(listOf(500L, 504L, 505L, 509L), history.map { it.cursor })
        assertEquals(6L, history.missed) // Three upstream + three combined downstream/new-session losses.
        history.clearView()
        assertTrue(history.isEmpty())
        assertFalse(history.receive(event(509, session = 2, sequence = 3), true))
        history.receive(event(510, session = 2, sequence = 4), true)
        assertEquals(1, history.size)
        assertEquals(6L, history.missed)
    }

    @Test fun `handoffs are retained for error and uncertainty but exact local rejections are removed`() {
        val commands = TerminalCommandHistory()
        fun tx(cursor: Long, outcome: CommunicationEvent.Outcome = CommunicationEvent.Outcome.ATTEMPTED, attempt: Long? = null) =
            event(cursor, CommunicationEvent.Category.COMMAND).let { it.copy(event = it.event.copy(
                direction = CommunicationEvent.Direction.TX, text = "G0 X1\n", outcome = outcome, attemptSequence = attempt,
            )) }
        commands.receive(tx(1))
        commands.receive(event(2, CommunicationEvent.Category.ERROR))
        commands.receive(tx(3)) // Same text, separate actual handoff.
        commands.receive(tx(4, CommunicationEvent.Outcome.LOCAL_REJECTION, 3))
        assertEquals(listOf("G0 X1"), commands.toList())
        commands.receive(tx(5))
        commands.receive(tx(6, CommunicationEvent.Outcome.UNCERTAIN, 5))
        assertEquals(2, commands.size)
        for (cursor in 7L..206L) commands.receive(tx(cursor))
        assertEquals(100, commands.size)
        // Pre-admission rejection emits nothing, so history cannot invent a transmitted command.
    }
}
