package no.grblcommander.desktop.ui.softkeys

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import no.grblcommander.desktop.ui.navigation.ScreenStack

/**
 * Identity of a displayed logical binding and its effective presentation.
 *
 * [entry] and [sourceDefinition] identify the original callback's owner and
 * definition, even when another screen eventually modifies that definition.
 * [modifiers] records the navigation lifetime and instance of each applied
 * modifier. A copy() of the effective definition is not a new logical binding
 * if its fields are equal, but replacing a source, a modifier, or any effective
 * field (including action/predicates) invalidates pending activation.
 *
 * Issue #4 will populate modifier provenance during cross-screen resolution.
 */
class SoftKeyBinding internal constructor(
    internal val stack: ScreenStack,
    internal val entry: ScreenStack.Entry,
    val definition: SoftKeyDefinition,
    internal val sourceDefinition: SoftKeyDefinition = definition,
    internal val modifiers: List<Pair<ScreenStack.Entry, SoftKeyModifier>> = emptyList(),
) {
    override fun equals(other: Any?): Boolean = other is SoftKeyBinding && matches(other)

    override fun hashCode(): Int {
        var result = 31 * (31 * System.identityHashCode(stack) +
            System.identityHashCode(entry)) + System.identityHashCode(sourceDefinition)
        for ((owner, modifier) in modifiers) {
            result = 31 * result + System.identityHashCode(owner)
            result = 31 * result + System.identityHashCode(modifier)
        }
        return 31 * result + definition.hashCode()
    }

    internal fun matches(other: SoftKeyBinding): Boolean =
        stack === other.stack &&
            entry === other.entry &&
            sourceDefinition === other.sourceDefinition &&
            modifiers.size == other.modifiers.size &&
            modifiers.indices.all { index ->
                val (owner, modifier) = modifiers[index]
                val (otherOwner, otherModifier) = other.modifiers[index]
                owner === otherOwner && modifier === otherModifier
            } &&
            definition == other.definition
}

/**
 * One UI-thread activation path for pointer, keyboard and future panel input.
 * Never invoke a captured action directly. Read the active stack and slot again.
 */
fun activateSoftKey(
    currentStack: () -> ScreenStack,
    slot: SoftKeySlot,
    expected: SoftKeyBinding? = null,
): Boolean {
    val stack = currentStack()
    val binding = stack.softKeyBinding(slot) ?: return false
    if (expected != null && !expected.matches(binding)) return false
    val definition = binding.definition
    val action = definition.action ?: return false
    if (!definition.enabled()) return false
    // Also reject synchronous navigation/binding changes made by a predicate.
    if (currentStack() !== stack) return false
    val latest = stack.softKeyBinding(slot) ?: return false
    if (!binding.matches(latest)) return false
    action()
    return true
}

/** F1–F8 select bottom keys; Shift+F1–F7 select R1–R7. */
fun softKeySlot(key: Key, shift: Boolean = false): SoftKeySlot? {
    val index = listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8).indexOf(key)
    if (index < 0) return null
    return if (shift) RightSoftKeySlot.entries.getOrNull(index) else BottomSoftKeySlot.entries[index]
}

/** Consume both phases of mapped shortcuts, executing only on key down. */
fun handleSoftKeyEvent(event: KeyEvent, currentStack: () -> ScreenStack): Boolean {
    if (event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) return false
    val slot = softKeySlot(event.key, event.isShiftPressed) ?: return false
    if (event.type == KeyEventType.KeyDown) activateSoftKey(currentStack, slot)
    return true
}
