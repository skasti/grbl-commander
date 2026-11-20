package no.grblcommander.desktop.ui.layout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.RightSoftKeySlot
import no.grblcommander.desktop.ui.navigation.SoftKeyDefinition
import no.grblcommander.desktop.ui.navigation.SoftKeyModifier
import no.grblcommander.desktop.ui.navigation.SoftKeyOverride
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SoftKeyRenderingTest {
    @Test fun predicatesRecomposeModifiedDefinitionAndRetainDisabledContent() = runComposeUiTest {
        val allowed = mutableStateOf(false)
        val highlighted = mutableStateOf(false)
        val icon = ImageVector.Builder("fixture", 16.dp, 16.dp, 16f, 16f).build()
        val original = SoftKeyDefinition("Select", icon, {}, { allowed.value }, { highlighted.value })
        val modified = SoftKeyModifier { it.copy(label = "Choose") }.modify(original)
        val screen = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(BottomSoftKeySlot.F1 to modified, BottomSoftKeySlot.F2 to SoftKeyDefinition.Blank)
            @Composable override fun Content() {}
        }
        val stack = ScreenStack(screen)
        setContent {
            MaterialTheme {
                Column {
                    BottomSoftKeySlot.entries.forEach { slot ->
                        SoftkeyTile(slot, stack, { stack }, Modifier.size(120.dp, 80.dp))
                    }
                }
            }
        }
        onNodeWithTag("softkey-F1").assertIsNotEnabled().assertIsNotSelected()
        onNodeWithText("Choose").assertExists()
        onNodeWithTag("softkey-F2").assertIsNotEnabled()
        onNodeWithText("F2").assertDoesNotExist()
        onNodeWithText("F3").assertDoesNotExist()
        runOnIdle { allowed.value = true; highlighted.value = true }
        onNodeWithTag("softkey-F1").assertIsEnabled().assertIsSelected()
        runOnIdle { assertSame(modified, stack.softKeyDefinition(BottomSoftKeySlot.F1)) }
        runOnIdle { allowed.value = false }
        onNodeWithTag("softkey-F1").assertIsNotEnabled().assertIsSelected()
        onNodeWithText("Choose").assertExists()
    }

    @Test fun shellPointerAndKeyboardUseActiveStackAndCurrentEnabledState() = runComposeUiTest {
        var programCalls = 0
        var manualCalls = 0
        val allowed = mutableStateOf(true)
        val program = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(
                BottomSoftKeySlot.F1 to SoftKeyDefinition(
                    "Program action",
                    action = { programCalls++ },
                    enabled = { allowed.value })
            )
            override val rightKeys = mapOf(
                RightSoftKeySlot.R7 to SoftKeyDefinition(
                    "Program info",
                    action = { programCalls++ })
            )
            @Composable override fun Content() {}
        }
        val manual = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(
                BottomSoftKeySlot.F1 to SoftKeyDefinition(
                    "Manual action",
                    action = { manualCalls++ })
            )
            @Composable override fun Content() {}
        }
        val programStack = ScreenStack(program)
        val manualStack = ScreenStack(manual)
        var activeStack by mutableStateOf(programStack)
        val stacks = listOf(programStack, manualStack)
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { activeStack }) {
                    ScreenStackHost(stacks, activeStack, Modifier.testTag("workspace"))
                }
            }
        }
        onNodeWithTag("softkey-F1").performTouchInput { click() }
        onNodeWithTag("workspace").performKeyInput { pressKey(Key.F1) }
        onNodeWithTag("workspace").performKeyInput {
            keyDown(Key.ShiftLeft); pressKey(Key.F7); keyUp(Key.ShiftLeft)
        }
        runOnIdle { assertEquals(3, programCalls); allowed.value = false }
        onNodeWithTag("softkey-F1").assertIsNotEnabled().performTouchInput { click() }
        onNodeWithTag("workspace").performKeyInput { pressKey(Key.F1) }
        runOnIdle { assertEquals(3, programCalls); activeStack = manualStack }
        onNodeWithTag("softkey-F1").performTouchInput { click() }
        onNodeWithTag("workspace").performKeyInput { pressKey(Key.F1) }
        runOnIdle { assertEquals(2, manualCalls); assertEquals(3, programCalls) }
        onNodeWithTag("softkey-R7").assertIsNotEnabled()
        onNodeWithTag("workspace").performKeyInput { pressKey(Key.F8) }
        runOnIdle { assertEquals(2, manualCalls) }
    }

    @Test fun pendingTouchCannotActivateReplacementOrDisabledBinding() = runComposeUiTest {
        var oldCalls = 0
        var newCalls = 0
        val allowed = mutableStateOf(true)
        val root = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(
                BottomSoftKeySlot.F1 to SoftKeyDefinition(
                    "Root action", action = { oldCalls++ }, enabled = { allowed.value },
                )
            )
            @Composable override fun Content() {}
        }
        val overlay = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(
                BottomSoftKeySlot.F1 to SoftKeyDefinition(
                    "Overlay action", action = { newCalls++ },
                )
            )
            @Composable override fun Content() {}
        }
        val stack = ScreenStack(root)
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { stack }) {
                    ScreenStackHost(stack)
                }
            }
        }
        onNodeWithTag("softkey-F1").performTouchInput { down(center) }
        runOnIdle { stack.push(overlay) }
        onNodeWithTag("softkey-F1").performTouchInput { up() }
        runOnIdle { assertEquals(0, oldCalls); assertEquals(0, newCalls); stack.pop() }
        onNodeWithTag("softkey-F1").performTouchInput { down(center) }
        runOnIdle { allowed.value = false }
        onNodeWithTag("softkey-F1").performTouchInput { up() }
        runOnIdle { assertEquals(0, oldCalls); assertEquals(0, newCalls) }
    }

    @Test fun observableDefinitionReplacementRecomposesAndCancelsPendingTouch() = runComposeUiTest {
        var oldCalls = 0
        var newCalls = 0
        val initial = SoftKeyDefinition("Old action", action = { oldCalls++ })
        val replacement = SoftKeyDefinition("New action", action = { newCalls++ })
        val screen = object : Screen<Unit>(Unit) {
            var overrides by mutableStateOf<Map<BottomSoftKeySlot, SoftKeyOverride>>(
                mapOf(BottomSoftKeySlot.F1 to initial),
            )
            override val bottomKeys get() = overrides
            @Composable override fun Content() {}
        }
        val stack = ScreenStack(screen)
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { stack }) {
                    ScreenStackHost(stack)
                }
            }
        }
        onNodeWithText("Old action").assertExists()
        onNodeWithTag("softkey-F1").performTouchInput { down(center) }
        runOnIdle { screen.overrides = mapOf(BottomSoftKeySlot.F1 to replacement) }
        onNodeWithText("New action").assertExists()
        onNodeWithText("Old action").assertDoesNotExist()
        onNodeWithTag("softkey-F1").performTouchInput { up() }
        runOnIdle {
            assertEquals(0, oldCalls)
            assertEquals(0, newCalls)
        }
        onNodeWithTag("softkey-F1").performTouchInput { click() }
        runOnIdle { assertEquals(0, oldCalls); assertEquals(1, newCalls) }
    }

    @Test fun observableMutableOverrideMapUpdatesWithoutNavigation() = runComposeUiTest {
        val screen = object : Screen<Unit>(Unit) {
            override val bottomKeys = androidx.compose.runtime.mutableStateMapOf<BottomSoftKeySlot, SoftKeyOverride>(
                BottomSoftKeySlot.F1 to SoftKeyDefinition("Before", action = {}),
            )
            @Composable override fun Content() {}
        }
        val stack = ScreenStack(screen)
        setContent {
            MaterialTheme {
                SoftkeyTile(
                    BottomSoftKeySlot.F1, stack,
                    { stack }, Modifier.size(120.dp, 80.dp))
            }
        }
        onNodeWithText("Before").assertExists()
        runOnIdle {
            screen.bottomKeys[BottomSoftKeySlot.F1] = SoftKeyDefinition("After", action = {})
        }
        onNodeWithText("Before").assertDoesNotExist()
        onNodeWithText("After").assertExists()
        runOnIdle { assertSame(screen, stack.top) }
    }


    @Test fun pendingTouchDoesNotActivateAKeyOnAnotherMode() = runComposeUiTest {
        var programCalls = 0
        var manualCalls = 0
        val program = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(
                BottomSoftKeySlot.F1 to SoftKeyDefinition(
                    "Program",
                    action = { programCalls++ })
            )
            @Composable override fun Content() {}
        }
        val manual = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(
                BottomSoftKeySlot.F1 to SoftKeyDefinition(
                    "Manual",
                    action = { manualCalls++ })
            )
            @Composable override fun Content() {}
        }
        val programStack = ScreenStack(program)
        val manualStack = ScreenStack(manual)
        var activeStack by mutableStateOf(programStack)
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { activeStack }) {
                    ScreenStackHost(listOf(programStack, manualStack), activeStack)
                }
            }
        }
        onNodeWithTag("softkey-F1").performTouchInput { down(center) }
        runOnIdle { activeStack = manualStack }
        onNodeWithText("Manual").assertExists()
        onNodeWithTag("softkey-F1").performTouchInput { up() }
        runOnIdle { assertEquals(0, programCalls); assertEquals(0, manualCalls) }
        onNodeWithTag("softkey-F1").performTouchInput { click() }
        runOnIdle { assertEquals(0, programCalls); assertEquals(1, manualCalls) }
    }

    @Test fun popAndReopenSameScreenInstanceCancelsPendingTouch() = runComposeUiTest {
        var rootCalls = 0
        var childCalls = 0
        val root = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(BottomSoftKeySlot.F1 to SoftKeyDefinition("Root", action = { rootCalls++ }))
            @Composable override fun Content() {}
        }
        val child = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(
                BottomSoftKeySlot.F1 to SoftKeyDefinition(
                    "Child",
                    action = { childCalls++ })
            )
            @Composable override fun Content() {}
        }
        val stack = ScreenStack(root)
        stack.push(child)
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { stack }) {
                    ScreenStackHost(stack)
                }
            }
        }
        onNodeWithTag("softkey-F1").performTouchInput { down(center) }
        runOnIdle { stack.pop() }
        onNodeWithText("Root").assertExists()
        runOnIdle { stack.push(child) }
        onNodeWithText("Child").assertExists()
        onNodeWithTag("softkey-F1").performTouchInput { up() }
        runOnIdle { assertEquals(0, rootCalls); assertEquals(0, childCalls) }
        onNodeWithTag("softkey-F1").performTouchInput { click() }
        runOnIdle { assertEquals(0, rootCalls); assertEquals(1, childCalls) }
    }

    @Test fun softkeyActionCanOpenOverlayWithoutBlockingItsOwnClick() = runComposeUiTest {
        var opens = 0
        lateinit var stack: ScreenStack
        val overlay = object : Screen<Unit>(Unit) {
            @Composable override fun Content() {}
        }
        val root = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(BottomSoftKeySlot.F1 to SoftKeyDefinition("Open overlay", action = {
                opens++
                stack.push(overlay)
            }))
            @Composable override fun Content() {}
        }
        stack = ScreenStack(root)
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { stack }) {
                    ScreenStackHost(stack)
                }
            }
        }
        onNodeWithTag("softkey-F1").performTouchInput { click() }
        runOnIdle { assertSame(overlay, stack.top); assertEquals(1, opens) }
        onNodeWithTag("softkey-F1").assertIsNotEnabled()
    }

    @Test fun keyboardKeyUpIsNotAnotherActivation() = runComposeUiTest {
        var calls = 0
        val screen = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(BottomSoftKeySlot.F1 to SoftKeyDefinition("Key", action = { calls++ }))
            @Composable override fun Content() {}
        }
        val stack = ScreenStack(screen)
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { stack }) {
                    ScreenStackHost(stack, Modifier.testTag("workspace"))
                }
            }
        }
        onNodeWithTag("workspace").performKeyInput {
            keyDown(Key.F1)
            keyUp(Key.F1)
        }
        runOnIdle { assertEquals(1, calls) }
        onNodeWithTag("workspace").performKeyInput { pressKey(Key.F1) }
        runOnIdle { assertEquals(2, calls) }
    }
}
