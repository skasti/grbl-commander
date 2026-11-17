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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex

/**
 * A single workspace host for multiple long-lived screen stacks. Stacks are
 * retained by the window; switching the active stack never removes an inactive
 * stack from composition (so ordinary remember state and overlays survive).
 *
 * Each stack still owns its navigation and typed result callbacks. This host
 * only decides which stack is visible and receives operator input.
 */
@Composable
fun ScreenStackHost(
    stacks: List<ScreenStack>,
    activeStack: ScreenStack,
    modifier: Modifier = Modifier,
) {
    require(stacks.any { it === activeStack }) { "The active stack must be hosted" }
    Box(modifier.fillMaxSize().clipToBounds()) {
        stacks.forEach { stack ->
            key(stack) {
                val active = stack === activeStack
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

/**
 * All entries stay composed in root-to-top drawing order. Each overlay occupies
 * the workspace, with a scrim over the previous entry. Only the top accepts input.
 * An inactive host keeps its compositions but refuses interaction and focus.
 * Shell controls are outside this host; softkey resolution is a separate concern.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScreenStackHost(stack: ScreenStack, modifier: Modifier = Modifier, isActive: Boolean = true) {
    val focusManager = LocalFocusManager.current
    val topEntry = stack.entries.last()
    val activeFocus = remember(topEntry) { FocusRequester() }

    LaunchedEffect(topEntry, isActive) {
        if (!isActive) return@LaunchedEffect
        // Includes captured text-field focus: a covered editor must stop receiving keys.
        focusManager.clearFocus(force = true)
        activeFocus.requestFocus()
    }

    Box(modifier.fillMaxSize().clipToBounds()
        .then(if (isActive) Modifier else Modifier.clearAndSetSemantics {})
        .onPreviewKeyEvent { !isActive }
        .pointerInput(isActive) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (!isActive) event.changes.forEach { it.consume() }
                }
            }
        }
        .focusProperties {
            canFocus = isActive
            onEnter = {
                if (!isActive) cancelFocusChange()
            }
        }
        .focusGroup()
    ) {
        stack.entries.forEachIndexed { index, entry ->
            key(entry) {
                val active = isActive && entry === topEntry
                val inputGate = Modifier
                    .onPreviewKeyEvent { !isActive || stack.top !== entry.screen }
                    .pointerInput(entry, isActive) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (!isActive || stack.top !== entry.screen) {
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                    .focusProperties {
                        // Cancel traversal into the entire covered group, not just its root.
                        onEnter = {
                            if (!isActive || stack.top !== entry.screen) {
                                cancelFocusChange()
                            }
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
                    // Catch blank-area input behind the content, never as an ancestor of
                    // child gestures. Consuming child moves in Final cancels valid clicks.
                    Box(Modifier.matchParentSize().pointerInput(entry) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    })
                    Box(
                        Modifier.fillMaxSize()
                            .then(if (active) Modifier.focusRequester(activeFocus) else Modifier)
                            .focusable(enabled = active),
                    ) {
                        entry.screen.Content()
                    }
                }
            }
        }
    }
}
