package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Window-scoped owner of independent UI-mode screen stacks.
 *
 * Selecting another mode changes only the visible navigation context. Stacks,
 * screen instances and their overlays are not popped or rebuilt by a mode switch.
 * The Compose host is responsible for keeping inactive stacks composed and
 * isolating their input.
 */
class ModeScreenStacks<M : Any>(
    initialMode: M,
    private val rootFor: (M) -> Screen<*>,
) {
    private val stacksByMode = mutableStateMapOf<M, ScreenStack>()

    var activeMode by mutableStateOf(initialMode)
        private set

    init {
        stacksByMode[initialMode] = ScreenStack(rootFor(initialMode))
    }

    val activeStack: ScreenStack
        get() = stackFor(activeMode)

    /** All stacks initialized in this window, observable by a Compose host. */
    val stacks: Map<M, ScreenStack>
        get() = stacksByMode.toMap()

    /** Lazily create a mode's root once, keeping its stack for later returns. */
    fun stackFor(mode: M): ScreenStack =
        stacksByMode.getOrPut(mode) { ScreenStack(rootFor(mode)) }

    fun selectMode(mode: M) {
        stackFor(mode)
        activeMode = mode
    }
}
