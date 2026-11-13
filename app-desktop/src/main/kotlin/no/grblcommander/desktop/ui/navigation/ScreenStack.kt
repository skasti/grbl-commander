package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.derivedStateOf
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

    /**
     * Memoized, read-only derivations. Navigation and snapshot-backed key maps
     * invalidate the appropriate bank; ordinary enabled/active predicate changes
     * leave the composed definitions intact. Structural key changes must be
     * snapshot-observable so rendering and activation use the same binding.
     */
    private val resolvedBottomKeys by derivedStateOf {
        val currentEntries = entries
        val firstEntry = currentEntries.lastOrNull { !it.screen.inheritBottomKeys } ?: currentEntries.first()
        val remainingEntries = currentEntries.subList(currentEntries.indexOf(firstEntry) + 1, currentEntries.size)

        firstEntry.screen.bottomKeys.definitions().applyAllOverrides(remainingEntries.map { it.screen.bottomKeys })
    }

    private val resolvedRightKeys by derivedStateOf {
        val currentEntries = entries
        val firstEntry = currentEntries.lastOrNull { !it.screen.inheritRightKeys } ?: currentEntries.first()
        val remainingEntries = currentEntries.subList(currentEntries.indexOf(firstEntry) + 1, currentEntries.size)
        firstEntry.screen.rightKeys.definitions().applyAllOverrides(remainingEntries.map { it.screen.rightKeys })
    }

    val effectiveBottomKeys: Map<BottomSoftKeySlot, SoftKeyDefinition>
        get() = resolvedBottomKeys

    val effectiveRightKeys: Map<RightSoftKeySlot, SoftKeyDefinition>
        get() = resolvedRightKeys

    /** Read the effective definition used by both rendering and activation. */
    fun softKeyDefinition(slot: SoftKeySlot): SoftKeyDefinition? =
        when (slot) {
            is BottomSoftKeySlot -> resolvedBottomKeys[slot]
            is RightSoftKeySlot -> resolvedRightKeys[slot]
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
