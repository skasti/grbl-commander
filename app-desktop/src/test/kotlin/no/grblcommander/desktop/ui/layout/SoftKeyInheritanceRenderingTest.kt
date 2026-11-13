package no.grblcommander.desktop.ui.layout

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import no.grblcommander.desktop.ui.navigation.*
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.SoftKeyDefinition
import no.grblcommander.desktop.ui.navigation.SoftKeyModifier
import no.grblcommander.desktop.ui.navigation.SoftKeyOverride
import no.grblcommander.desktop.ui.screens.EditorScreen
import no.grblcommander.desktop.ui.screens.FileExplorer
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SoftKeyInheritanceRenderingTest {
    @Test fun inheritedBindingsRenderOwnerPredicatesAndStructuralUpdates() = runComposeUiTest {
        val allowed = mutableStateOf(false)
        val highlighted = mutableStateOf(false)
        var calls = 0
        val rootKeys = mutableStateMapOf<BottomSoftKeySlot, SoftKeyOverride>(
            BottomSoftKeySlot.F1 to SoftKeyDefinition(
                "Original", action = { calls++ },
                enabled = { allowed.value }, active = { highlighted.value },
            ),
            BottomSoftKeySlot.F2 to SoftKeyDefinition("Covered", action = {}),
        )
        val root = object : Screen<Unit>(Unit) {
            override val bottomKeys = rootKeys
            @Composable override fun Content() {}
        }
        val overlay = object : Screen<Unit>(Unit) {
            override val inheritBottomKeys = true
            override val bottomKeys = mapOf(
                BottomSoftKeySlot.F1 to SoftKeyModifier { it.copy(label = "Overlay " + it.label) },
                BottomSoftKeySlot.F2 to SoftKeyDefinition.Blank,
            )
            @Composable override fun Content() {}
        }
        val stack = ScreenStack(root)
        stack.push(overlay)
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { stack }) {
                    ScreenStackHost(stack)
                }
            }
        }
        onNodeWithText("Overlay Original").assertExists()
        onNodeWithTag("softkey-F1").assertIsNotEnabled().assertIsNotSelected()
        onNodeWithTag("softkey-F2").assertIsNotEnabled()
        onNodeWithText("F2").assertDoesNotExist()

        runOnIdle { allowed.value = true; highlighted.value = true }
        onNodeWithTag("softkey-F1").assertIsEnabled().assertIsSelected()
        onNodeWithTag("softkey-F1").performTouchInput { click() }
        runOnIdle { assertEquals(1, calls) }

        runOnIdle {
            rootKeys[BottomSoftKeySlot.F1] = SoftKeyDefinition("Changed", action = { calls++ })
        }
        onNodeWithText("Overlay Changed").assertExists()
        onNodeWithText("Overlay Original").assertDoesNotExist()
        onNodeWithTag("softkey-F1").performTouchInput { click() }
        runOnIdle { assertEquals(2, calls) }
    }

    @Test fun inheritedDefinitionReplacementCancelsPendingTouch() = runComposeUiTest {
        var previousCalls = 0
        var currentCalls = 0
        val rootKeys = mutableStateMapOf<BottomSoftKeySlot, SoftKeyOverride>(
            BottomSoftKeySlot.F1 to SoftKeyDefinition("Previous", action = { previousCalls++ }),
        )
        val root = object : Screen<Unit>(Unit) {
            override val bottomKeys = rootKeys
            @Composable override fun Content() {}
        }
        val overlay = object : Screen<Unit>(Unit) {
            override val inheritBottomKeys = true
            override val bottomKeys = mapOf(
                BottomSoftKeySlot.F1 to SoftKeyModifier { it.copy(label = "Inherited " + it.label) },
            )
            @Composable override fun Content() {}
        }
        val stack = ScreenStack(root)
        stack.push(overlay)
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { stack }) {
                    ScreenStackHost(stack)
                }
            }
        }
        onNodeWithText("Inherited Previous").assertExists()
        onNodeWithTag("softkey-F1").performTouchInput { down(center) }
        runOnIdle {
            rootKeys[BottomSoftKeySlot.F1] = SoftKeyDefinition("Current", action = { currentCalls++ })
        }
        onNodeWithText("Inherited Current").assertExists()
        onNodeWithTag("softkey-F1").performTouchInput { up() }
        runOnIdle {
            assertEquals(0, previousCalls)
            assertEquals(0, currentCalls)
        }
        onNodeWithTag("softkey-F1").performTouchInput { click() }
        runOnIdle { assertEquals(1, currentCalls) }
    }

    @Test fun activeModeSelectionAndPopRestoreCurrentBindings() = runComposeUiTest {
        var programCalls = 0
        var manualCalls = 0
        val program = ScreenStack(object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(
                BottomSoftKeySlot.F1 to SoftKeyDefinition("Program", action = { programCalls++ }),
            )
            @Composable override fun Content() {}
        })
        val overlay = object : Screen<Unit>(Unit) {
            override val inheritBottomKeys = true
            override val bottomKeys = mapOf(
                BottomSoftKeySlot.F1 to SoftKeyModifier { it.copy(label = "Inherited program") },
            )
            @Composable override fun Content() {}
        }
        program.push(overlay)
        val manual = ScreenStack(object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(
                BottomSoftKeySlot.F1 to SoftKeyDefinition("Manual", action = { manualCalls++ }),
            )
            @Composable override fun Content() {}
        })
        var active by mutableStateOf(program)
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { active }) {
                    ScreenStackHost(listOf(program, manual), active, Modifier.testTag("workspace"))
                }
            }
        }
        onNodeWithText("Inherited program").assertExists()
        onNodeWithTag("workspace").performKeyInput { pressKey(Key.F1) }
        runOnIdle { assertEquals(1, programCalls); active = manual }
        onNodeWithText("Manual").assertExists()
        onNodeWithTag("softkey-F1").performTouchInput { click() }
        runOnIdle {
            assertEquals(1, manualCalls)
            assertEquals(1, programCalls)
            active = program
        }
        onNodeWithText("Inherited program").assertExists()
        runOnIdle { assertSame(overlay, program.pop()) }
        onNodeWithText("Program").assertExists()
        onNodeWithText("Inherited program").assertDoesNotExist()
        onNodeWithTag("softkey-F1").performTouchInput { click() }
        runOnIdle { assertEquals(2, programCalls) }
    }

    @Test fun editorExplorerShowsIndependentBanksAndRestoresOnClose() = runComposeUiTest {
        val editor = EditorScreen()
        val stack = ScreenStack(editor)
        val explorer = FileExplorer()
        explorer.open(editor)
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { stack }) {
                    ScreenStackHost(stack, Modifier.testTag("workspace"))
                }
            }
        }
        onNodeWithText("Select").assertExists()
        onNodeWithTag("softkey-F1").assertIsNotEnabled()
        onNodeWithText("Editor unavailable").assertExists()
        onNodeWithTag("softkey-R1").assertIsNotEnabled()
        onNodeWithTag("softkey-R2").assertIsNotEnabled()
        onNodeWithText("R2").assertDoesNotExist()
        onNodeWithTag("softkey-R7").performTouchInput { click() }
        onNodeWithTag("softkey-R7").assertIsSelected()
        onNodeWithText("example-a.nc").performClick()
        onNodeWithTag("softkey-F1").assertIsEnabled()
        onNodeWithTag("workspace").performKeyInput { pressKey(Key.F8) }
        onNodeWithText("Editor unavailable").assertDoesNotExist()
        onAllNodesWithText("Open file").assertCountEquals(2)
        onNodeWithTag("softkey-R1").assertIsEnabled()
        onNodeWithTag("softkey-R2").assertIsEnabled()
        onNodeWithTag("softkey-R7").assertIsNotEnabled()
    }
}
