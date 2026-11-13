package no.grblcommander.desktop.ui.navigation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * One UI-thread activation path for pointer, keyboard and future panel input.
 * Resolve against the currently selected stack; never capture an action for later use.
 */
fun activateSoftKey(currentStack: () -> ScreenStack, slot: SoftKeySlot): Boolean {
    val definition = currentStack().softKeyDefinition(slot) ?: return false
    val action = definition.action ?: return false
    if (!definition.enabled()) return false
    action()
    return true
}

/** F1–F8 select bottom keys; Shift+F1–F7 select R1–R7. */
fun softKeySlot(key: Key, shift: Boolean = false): SoftKeySlot? {
    val index = listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8).indexOf(key)
    if (index < 0) return null
    return if (shift) RightSoftKeySlot.entries.getOrNull(index) else BottomSoftKeySlot.entries[index]
}

/**
 * Consume mapped key-up as well as key-down, executing only on key-down.
 * Each key-down (including OS-generated repeat) is an activation; actions that
 * must not repeat must enforce that in their own application-level preconditions.
 */
fun handleSoftKeyEvent(event: KeyEvent, currentStack: () -> ScreenStack): Boolean {
    if (event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) return false
    val slot = softKeySlot(event.key, event.isShiftPressed) ?: return false
    if (event.type == KeyEventType.KeyDown) activateSoftKey(currentStack, slot)
    return true
}
