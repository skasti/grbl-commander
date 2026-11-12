package no.grblcommander.desktop.ui.navigation

import no.grblcommander.desktop.ui.softkeys.*
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
     * leave the composed definitions intact.
     */
    private val resolvedBottomKeys by derivedStateOf {
        val currentEntries = entries
        val firstEntry = currentEntries.last { !it.screen.inheritBottomKeys }
        val remainingEntries = currentEntries.subList(currentEntries.indexOf(firstEntry) + 1, currentEntries.size)

        firstEntry.screen.bottomKeys.definitions().applyAllOverrides(remainingEntries.map { it.screen.bottomKeys })
    }

    private val resolvedRightKeys by derivedStateOf {
        val currentEntries = entries
        val firstEntry = currentEntries.last { !it.screen.inheritRightKeys }
        val remainingEntries = currentEntries.subList(currentEntries.indexOf(firstEntry) + 1, currentEntries.size)
        firstEntry.screen.rightKeys.definitions().applyAllOverrides(remainingEntries.map { it.screen.rightKeys })
    }

    val effectiveBottomKeys: Map<BottomSoftKeySlot, SoftKeyDefinition>
        get() = resolvedBottomKeys

    val effectiveRightKeys: Map<RightSoftKeySlot, SoftKeyDefinition>
        get() = resolvedRightKeys

    /**
     * Resolve the latest binding at invocation time, including non-observable
     * changes to a screen's definition. The shell uses the derived bank views
     * for rendering, while activation deliberately reads the source directly.
     */
    fun softKeyDefinition(slot: SoftKeySlot): SoftKeyDefinition? =
        when (slot) {
            is BottomSoftKeySlot -> resolvedBottomKeys[slot]
            is RightSoftKeySlot -> resolvedRightKeys[slot]
        }

    /**
     * Each bank finds its own inheritance boundary, then applies overrides
     * bottom-to-top. The derivations above observe structural Compose state
     * without introducing a separately mutable binding store.
     */
    private fun resolveSoftKeyDefinition(slot: SoftKeySlot): SoftKeyDefinition? {
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

private fun <K : SoftKeySlot> MutableMap<K, SoftKeyDefinition>.applyAllOverrides(overrides: List<Map<K, SoftKeyOverride>>): Map<K, SoftKeyDefinition> {
    for (overrideMap in overrides) {
        applyOverrides(overrideMap)
    }
    return this
}

private fun <K: SoftKeySlot>Map<K, SoftKeyOverride>.definitions(): MutableMap<K, SoftKeyDefinition> {
    val result = mutableMapOf<K, SoftKeyDefinition>()
    for ((slot, override) in this) {
        val definition = when (override) {
            is SoftKeyDefinition -> override
            is SoftKeyModifier -> result[slot]?.let(override.modify)
        }
        if (definition != null) {
            result[slot] = definition
        }
    }
    return result
}

private fun <K : SoftKeySlot> MutableMap<K, SoftKeyDefinition>.applyOverrides(keyOverrides: Map<K, SoftKeyOverride>) {
    for ((slot, override) in keyOverrides) {
        val current = this[slot]
        val newDefinition = when (override) {
            is SoftKeyDefinition -> override
            is SoftKeyModifier -> current?.let(override.modify)
        }
        if (newDefinition != null) {
            this[slot] = newDefinition
        } else {
            this.remove(slot)
        }
    }
}
