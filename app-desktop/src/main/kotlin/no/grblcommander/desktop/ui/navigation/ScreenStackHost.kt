package no.grblcommander.desktop.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * All entries stay composed in root-to-top drawing order. Each overlay occupies
 * the workspace, with a scrim over the previous entry. Only the top accepts input.
 * Shell controls are outside this host; softkey resolution is a separate concern.
 */
@Composable
fun ScreenStackHost(stack: ScreenStack, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    val topEntry = stack.entries.last()
    val activeFocus = remember(topEntry) { FocusRequester() }

    LaunchedEffect(topEntry) {
        // Includes captured text-field focus: a covered editor must stop receiving keys.
        focusManager.clearFocus(force = true)
        activeFocus.requestFocus()
    }

    Box(modifier.fillMaxSize().clipToBounds()) {
        stack.entries.forEachIndexed { index, entry ->
            key(entry) {
                val active = entry === topEntry
                val inputGate = Modifier
                    .onPreviewKeyEvent { stack.top !== entry.screen }
                    .pointerInput(entry) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (stack.top !== entry.screen) {
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                    .focusProperties {
                        // Cancel traversal into the entire covered group, not just its root.
                        @Suppress("DEPRECATION")
                        enter = {
                            if (stack.top === entry.screen) FocusRequester.Default
                            else FocusRequester.Cancel
                        }
                    }
                    .focusGroup()
                Box(
                    Modifier.fillMaxSize()
                        .then(inputGate)
                        .then(if (active) Modifier else Modifier.clearAndSetSemantics {}),
                ) {
                    if (index > 0) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
                    }
                    // This full-workspace pointer target also catches input outside a
                    // small overlay's content, preventing fall-through to lower siblings.
                    Box(
                        Modifier.fillMaxSize()
                            .then(if (active) Modifier.focusRequester(activeFocus) else Modifier)
                            .focusable(enabled = active)
                            .pointerInput(entry) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Final)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            },
                    ) {
                        entry.screen.Content(stack)
                    }
                }
            }
        }
    }
}
