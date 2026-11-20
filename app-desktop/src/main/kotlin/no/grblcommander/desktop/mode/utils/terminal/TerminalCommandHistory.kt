package no.grblcommander.desktop.mode.utils.terminal

import androidx.compose.runtime.mutableStateListOf
import no.grblcommander.core.connection.ControllerTraffic
import no.grblcommander.machine.session.CommunicationEvent

/** Actual normal-command handoffs, independent of acknowledgment and display filters. */
internal class TerminalCommandHistory : AbstractList<String>() {
    private data class Entry(val session: Long, val attempt: Long, val command: String)
    private val entries = mutableStateListOf<Entry>()
    override val size: Int get() = entries.size
    override fun get(index: Int): String = entries[index].command

    fun receive(traffic: ControllerTraffic) {
        val event = traffic.event
        if (event.direction != CommunicationEvent.Direction.TX || event.category != CommunicationEvent.Category.COMMAND) return
        when (event.outcome) {
            CommunicationEvent.Outcome.ATTEMPTED -> {
                if (entries.size == 100) entries.removeAt(0)
                entries.add(Entry(traffic.sessionId, event.sequence, event.text.removeSuffix("\n")))
            }
            CommunicationEvent.Outcome.LOCAL_REJECTION -> {
                // A transport-side validation rejection guarantees no bytes sent. Remove
                // only its own provisional handoff, never a same-text earlier command.
                val index = entries.indexOfFirst { it.session == traffic.sessionId && it.attempt == event.attemptSequence }
                if (index >= 0) entries.removeAt(index)
            }
            else -> Unit // error/timeout/UNCERTAIN cannot erase or replay a real handoff.
        }
    }
}
