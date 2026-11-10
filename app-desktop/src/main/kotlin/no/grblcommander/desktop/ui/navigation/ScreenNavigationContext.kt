package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Bound to one screen entry in one ScreenStackHost, never to a process-wide navigator.
 * The host invalidates this context when its composition is disposed.
 */
internal class ScreenNavigationContext(
    private val stack: ScreenStack,
    private val owner: ScreenStack.Entry,
) {
    private var bound = true

    fun <R> open(screen: Screen, cancellation: R, onResult: (R) -> Unit): ScreenResultHandle<R> {
        check(bound) { "The screen navigation context is no longer attached" }
        return stack.openResult(owner, screen, cancellation, onResult)
    }

    fun dispose() {
        bound = false
    }
}

internal val LocalScreenNavigationContext = staticCompositionLocalOf<ScreenNavigationContext> {
    error("A screen-specific opener must be used inside ScreenStackHost")
}
