package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.Composable

/**
 * One workspace instance. Keep durable UI state and result callbacks on the instance.
 * Screens never own transports. Navigation is independent of softkey definitions.
 */
interface Screen {
    @Composable
    fun Content(stack: ScreenStack)
}
