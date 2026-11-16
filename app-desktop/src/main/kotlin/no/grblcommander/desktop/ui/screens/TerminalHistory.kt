package no.grblcommander.desktop.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import no.grblcommander.core.connection.ControllerTraffic

/** UI-thread ring. Slots change incrementally; retention never copies or shifts the full list. */
internal class TerminalHistory(private val capacity: Int = 10_000) : AbstractList<ControllerTraffic>() {
    init { require(capacity > 0) }
    private val slots = MutableList<ControllerTraffic?>(capacity) { null }.toMutableStateList()
    private var head by mutableIntStateOf(0)
    private var count by mutableIntStateOf(0)
    override val size: Int get() = count
    var missed by mutableLongStateOf(0)
        private set
    private var started = false
    private var cursor = 0L

    override fun get(index: Int): ControllerTraffic {
        checkElementIndex(index)
        return checkNotNull(slots[(head + index) % capacity])
    }

    /** The oldest retained diagnostic event is a starting point, not a loss warning. */
    fun start(snapshot: List<ControllerTraffic>) {
        if (!started) {
            cursor = snapshot.firstOrNull()?.cursor?.minus(1) ?: 0L
            started = true
        }
    }

    /** Returns false for the snapshot/live overlap. Filtered events still advance the cursor. */
    fun receive(traffic: ControllerTraffic, admitted: Boolean): Boolean {
        if (traffic.cursor <= cursor) return false
        missed += traffic.cursor - cursor - 1
        cursor = traffic.cursor
        started = true
        if (admitted) {
            if (count == capacity) {
                slots[head] = traffic
                head = (head + 1) % capacity
            } else {
                slots[(head + count) % capacity] = traffic
                count++
            }
        }
        return true
    }

    /** Clear only the presentation, keeping the loss/deduplication position. */
    fun clearView() {
        repeat(count) { slots[(head + it) % capacity] = null }
        head = 0
        count = 0
    }

    private fun checkElementIndex(index: Int) {
        if (index !in 0 until count) throw IndexOutOfBoundsException("$index of $count")
    }
}
