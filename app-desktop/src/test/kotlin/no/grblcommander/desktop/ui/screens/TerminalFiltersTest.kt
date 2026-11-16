package no.grblcommander.desktop.ui.screens

import java.time.Instant
import no.grblcommander.machine.session.CommunicationEvent
import kotlin.test.*

class TerminalFiltersTest {
    @Test fun `defaults hide only polling and status and individual toggles reveal every category`() {
        val filters = TerminalFilters()
        for (category in CommunicationEvent.Category.entries) {
            for (direction in CommunicationEvent.Direction.entries) {
                val event = CommunicationEvent(1, Instant.EPOCH, direction, category, "unfamiliar text", CommunicationEvent.Outcome.RECEIVED)
                assertEquals(category != CommunicationEvent.Category.STATUS, filters.includes(event), "$direction $category")
                filters.status = true; filters.polling = true
                assertTrue(filters.includes(event))
                filters.status = false
                filters.polling = false
            }
        }
        filters.status = true
        val status = CommunicationEvent(2, Instant.EPOCH, CommunicationEvent.Direction.RX, CommunicationEvent.Category.STATUS, "not parsed by UI", CommunicationEvent.Outcome.RECEIVED)
        assertTrue(filters.includes(status))
        assertFalse(filters.includes(status.copy(direction = CommunicationEvent.Direction.TX)))
        filters.errors = false
        assertFalse(filters.includes(status.copy(category = CommunicationEvent.Category.ALARM)))
        assertTrue(filters.includes(status.copy(category = CommunicationEvent.Category.COMMAND)))
        filters.time = false
        assertTrue(filters.includes(status))
    }
}
