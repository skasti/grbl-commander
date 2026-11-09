package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * UI-thread confined navigation, ordered root to top. The root cannot be removed.
 * Instances are compared by identity, including when a Screen overrides equals.
 */
class ScreenStack(root: Screen) {
    internal class Entry(val screen: Screen)
    internal var entries by mutableStateOf(listOf(Entry(root)))
        private set

    val screens: List<Screen> get() = entries.map { it.screen }
    val top: Screen get() = entries.last().screen
    val size: Int get() = entries.size

    fun push(screen: Screen) {
        require(entries.none { it.screen === screen }) { "Screen instance is already on this stack" }
        entries = entries + Entry(screen)
    }

    /** Returns the dismissed instance, or null when only the root remains. */
    fun pop(): Screen? {
        if (entries.size == 1) return null
        val removed = top
        entries = entries.dropLast(1)
        return removed
    }

    /** Guard callbacks against dismissing a different, newer overlay. */
    fun dismiss(screen: Screen): Boolean {
        if (top !== screen || entries.size == 1) return false
        pop()
        return true
    }
}
