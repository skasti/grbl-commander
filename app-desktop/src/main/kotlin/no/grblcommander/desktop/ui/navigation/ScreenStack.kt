package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * UI-thread-confined navigation, ordered root to top. The root cannot be removed.
 * Instances are compared by identity, including when a Screen overrides equals.
 */
class ScreenStack(root: Screen) {
    internal class Entry(val screen: Screen, val onDismiss: (() -> Unit)? = null)
    internal var entries by mutableStateOf(listOf(Entry(root)))
        private set

    val screens: List<Screen> get() = entries.map { it.screen }
    val top: Screen get() = entries.last().screen
    val size: Int get() = entries.size

    fun push(screen: Screen) {
        require(entries.none { it.screen === screen }) { "Screen instance is already on this stack" }
        entries = entries + Entry(screen)
    }

    /** Removes the top entry, then delivers cancellation for a result-bearing screen. */
    fun pop(): Screen? {
        if (entries.size == 1) return null
        val removed = entries.last()
        entries = entries.dropLast(1)
        removed.onDismiss?.invoke()
        return removed.screen
    }

    /** Only the actual top instance may be dismissed, including result-bearing screens. */
    fun dismiss(screen: Screen): Boolean {
        if (top !== screen || entries.size == 1) return false
        pop()
        return true
    }

    /**
     * Result ownership belongs to this stack, not the screen. Both ordinary pop/back and
     * explicit completion remove the entry before delivering the callback, exactly once.
     * The owner entry also prevents covered or detached screens from opening overlays.
     */
    internal fun <R> openResult(
        owner: Entry,
        screen: Screen,
        cancellation: R,
        onResult: (R) -> Unit,
    ): ScreenResultHandle<R> {
        check(entries.last() === owner) { "Only the active screen can open an overlay" }
        require(entries.none { it.screen === screen }) { "Screen instance is already on this stack" }
        var delivered = false
        val deliver: (R) -> Unit = { result ->
            if (!delivered) {
                delivered = true
                onResult(result)
            }
        }
        val entry = Entry(screen) { deliver(cancellation) }
        entries = entries + entry
        return ScreenResultHandle { result ->
            if (entries.last() !== entry) {
                false
            } else {
                entries = entries.dropLast(1)
                deliver(result)
                true
            }
        }
    }
}

/** Typed, entry-bound completion; stale and duplicate completions return false. */
internal class ScreenResultHandle<R>(private val completeAction: (R) -> Boolean) {
    fun complete(result: R): Boolean = completeAction(result)
}
