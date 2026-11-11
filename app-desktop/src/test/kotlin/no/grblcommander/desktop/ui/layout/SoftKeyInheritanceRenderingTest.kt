package no.grblcommander.desktop.ui.layout

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import no.grblcommander.desktop.ui.navigation.*
import no.grblcommander.desktop.ui.softkeys.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SoftKeyInheritanceRenderingTest {
    @Test fun inheritedBindingsObserveOwnerStateAndReplaceWithoutStaleClicks() = runComposeUiTest {
        val allowed = mutableStateOf(false)
        val highlighted = mutableStateOf(false)
        var originalCalls = 0
        var replacementCalls = 0
        var latestCalls = 0
        val baseKeys = mutableStateMapOf<BottomSoftKeySlot, SoftKeyOverride>(
            BottomSoftKeySlot.F1 to SoftKeyDefinition(
                "Original", action = { originalCalls++ }, enabled = { allowed.value },
                active = { highlighted.value },
            ),
            BottomSoftKeySlot.F2 to SoftKeyDefinition("Hidden", action = { originalCalls++ }),
        )
        val root = object : Screen<Unit>(Unit) {
            override val bottomKeys = baseKeys
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
        val program = ScreenStack(root)
        program.push(overlay)
        var manualCalls = 0
        val manual = ScreenStack(object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(
                BottomSoftKeySlot.F1 to SoftKeyDefinition("Manual", action = { manualCalls++ }),
            )
            @Composable override fun Content() {}
        })
        var selected by mutableStateOf(program)

        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { selected }) {
                    ScreenStackHost(listOf(program, manual), selected, Modifier.testTag("workspace"))
                }
            }
        }
        try {
        onNodeWithText("Overlay Original").assertExists()
        onNodeWithTag("softkey-F1").assertIsNotEnabled().assertIsNotSelected()
        onNodeWithTag("softkey-F2").assertIsNotEnabled()
        onNodeWithText("F2").assertDoesNotExist()
        } catch (e: Throwable) { throw IllegalStateException("initial softkey rendering", e) }

        try {
        runOnIdle { allowed.value = true; highlighted.value = true }
        onNodeWithTag("softkey-F1").assertIsEnabled().assertIsSelected().performTouchInput { click() }
        runOnIdle {
            assertEquals(1, originalCalls)
            baseKeys[BottomSoftKeySlot.F1] = SoftKeyDefinition(
                "Replacement", action = { replacementCalls++ }, enabled = { allowed.value },
            )
        }
        onNodeWithText("Overlay Replacement").assertExists()
        } catch (e: Throwable) { throw IllegalStateException("predicate and structural reactivity", e) }
        try {
        onNodeWithTag("softkey-F1").performTouchInput { down(center) }
        runOnIdle {
            baseKeys[BottomSoftKeySlot.F1] = SoftKeyDefinition(
                "Latest", action = { latestCalls++ }, enabled = { allowed.value },
            )
        }
        onNodeWithText("Overlay Latest").assertExists()
        onNodeWithTag("softkey-F1").performTouchInput { up() }
        runOnIdle {
            assertEquals(0, replacementCalls)
            assertEquals(0, latestCalls)
        }
        } catch (e: Throwable) { throw IllegalStateException("pending touch cancel", e) }
        try {
        onNodeWithTag("workspace").performKeyInput { pressKey(Key.F1) }
        runOnIdle { assertEquals(1, latestCalls); selected = manual }

        onNodeWithText("Manual").assertExists()
        onNodeWithTag("softkey-F1").performTouchInput { click() }
        runOnIdle {
            assertEquals(1, manualCalls)
            assertEquals(1, latestCalls)
            selected = program
        }
        onNodeWithText("Overlay Latest").assertExists()
        } catch (e: Throwable) { throw IllegalStateException("keyboard and mode switch", e) }
        try {
        runOnIdle { assertSame(overlay, program.pop()) }
        onNodeWithText("Latest").assertExists()
        onNodeWithText("Overlay Latest").assertDoesNotExist()
        onNodeWithTag("softkey-F2").assertIsEnabled()
        onNodeWithTag("softkey-F2").performTouchInput { click() }
        runOnIdle { assertEquals(2, originalCalls) }
        } catch (e: Throwable) { throw IllegalStateException("pop restores root", e) }
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
