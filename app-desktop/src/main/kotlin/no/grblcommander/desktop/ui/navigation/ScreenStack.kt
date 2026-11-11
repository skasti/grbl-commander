package no.grblcommander.desktop.ui.navigation

import no.grblcommander.desktop.ui.softkeys.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * UI-thread-confined stack of screen instances, root to top. The root is bound
 * at construction and cannot be removed. All navigation is independent of UI
 * modes, input rendering and controller execution.
 */
class ScreenStack(root: Screen<*>) : NavigationParent {
    override val screenStack: ScreenStack get() = this

    internal class Entry(val screen: Screen<*>)
    internal var entries by mutableStateOf(listOf(Entry(root)))
        private set

    init {
        bindRoot(root)
    }

    private fun <R> bindRoot(root: Screen<R>) {
        root.bind(this, {})
    }

    val screens: List<Screen<*>> get() = entries.map { it.screen }
    val top: Screen<*> get() = entries.last().screen
    val size: Int get() = entries.size

    /** Computed views of each bank; screens remain the sole owners of bindings. */
    val effectiveBottomKeys: Map<BottomSoftKeySlot, SoftKeyDefinition>
        get() = BottomSoftKeySlot.entries.mapNotNull { slot ->
            softKeyDefinition(slot)?.let { slot to it }
        }.toMap()

    val effectiveRightKeys: Map<RightSoftKeySlot, SoftKeyDefinition>
        get() = RightSoftKeySlot.entries.mapNotNull { slot ->
            softKeyDefinition(slot)?.let { slot to it }
        }.toMap()

    /**
     * Each bank finds its own inheritance boundary, then applies overrides
     * bottom-to-top. Reading the screens' maps here observes structural Compose
     * state changes without keeping a second set of mutable bindings.
     */
    fun softKeyDefinition(slot: SoftKeySlot): SoftKeyDefinition? {
        val currentEntries = entries
        var boundary = currentEntries.lastIndex
        while (boundary > 0) {
            val screen = currentEntries[boundary].screen
            val inherits = when (slot) {
                is BottomSoftKeySlot -> screen.inheritBottomKeys
                is RightSoftKeySlot -> screen.inheritRightKeys
            }
            if (!inherits) break
            boundary--
        }

        var definition: SoftKeyDefinition? = null
        for (index in boundary..currentEntries.lastIndex) {
            val screen = currentEntries[index].screen
            val override = when (slot) {
                is BottomSoftKeySlot -> screen.bottomKeys[slot]
                is RightSoftKeySlot -> screen.rightKeys[slot]
            }
            definition = when (override) {
                is SoftKeyDefinition -> override
                is SoftKeyModifier -> definition?.let(override.modify)
                null -> definition
            }
        }
        return definition
    }

    /** Direct stack navigation always pushes above the current top screen. */
    fun <R> push(screen: Screen<R>) {
        attach(screen, {})
    }

    /** Open a typed result-bearing screen directly on this stack. */
    fun <R> open(screen: Screen<R>, onResult: (R) -> Unit) {
        attach(screen, onResult)
    }

    private fun <R> attach(screen: Screen<R>, onResult: (R) -> Unit) {
        require(entries.none { it.screen === screen }) { "Screen instance is already on this stack" }
        screen.bind(this, onResult)
        entries = entries + Entry(screen)
    }

    /** Pops the active non-root screen and delivers its cancellation result once. */
    fun pop(): Screen<*>? {
        if (entries.size == 1) return null
        val removed = entries.last()
        entries = entries.dropLast(1)
        removed.screen.deliverCancellation()
        return removed.screen
    }

    /** A stale or covered instance cannot dismiss a different screen. */
    fun dismiss(screen: Screen<*>): Boolean {
        if (entries.size == 1 || top !== screen) return false
        pop()
        return true
    }

    /** Explicitly complete only the current non-root instance. */
    internal fun <R> finish(screen: Screen<R>, result: R): Boolean {
        if (entries.size == 1 || top !== screen) return false
        entries = entries.dropLast(1)
        screen.deliver(result)
        return true
    }
}
