package no.grblcommander.desktop.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.zIndex

/**
 * Composes each initialized mode's ScreenStackHost for the window lifetime.
 * The active mode is drawn in front. Inactive hosts are not drawn or interactive,
 * but their screens (including overlays) retain ordinary Compose remember state.
 */
@Composable
fun <M : Any> ModeScreenStacksHost(
    modes: ModeScreenStacks<M>,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().clipToBounds()) {
        modes.stacks.forEach { (mode, stack) ->
            key(mode) {
                val active = mode == modes.activeMode
                ScreenStackHost(
                    stack = stack,
                    modifier = Modifier.fillMaxSize()
                        .zIndex(if (active) 1f else 0f)
                        .drawWithContent { if (active) drawContent() },
                    isActive = active,
                )
            }
        }
    }
}
