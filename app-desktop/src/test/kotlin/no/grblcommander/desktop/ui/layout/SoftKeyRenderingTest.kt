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
import no.grblcommander.desktop.ui.navigation.FileExplorer
import no.grblcommander.desktop.ui.navigation.FileExplorerResult
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost
import no.grblcommander.desktop.ui.softkeys.*
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
                        SoftkeyTile(slot, stack.softKeyBinding(slot), { stack }, Modifier.size(120.dp, 80.dp))
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
        runOnIdle { assertSame(modified, stack.softKeyBinding(BottomSoftKeySlot.F1)!!.definition) }
        runOnIdle { allowed.value = false }
        onNodeWithTag("softkey-F1").assertIsNotEnabled().assertIsSelected()
        onNodeWithText("Choose").assertExists()
    }

    @Test fun shellPointerAndKeyboardUseActiveStackAndCurrentEnabledState() = runComposeUiTest {
        var programCalls = 0
        var manualCalls = 0
        val allowed = mutableStateOf(true)
        val program = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(BottomSoftKeySlot.F1 to SoftKeyDefinition("Program action", action = { programCalls++ }, enabled = { allowed.value }))
            override val rightKeys = mapOf(RightSoftKeySlot.R7 to SoftKeyDefinition("Program info", action = { programCalls++ }))
            @Composable override fun Content() {}
        }
        val manual = object : Screen<Unit>(Unit) {
            override val bottomKeys = mapOf(BottomSoftKeySlot.F1 to SoftKeyDefinition("Manual action", action = { manualCalls++ }))
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

    @Test fun explorerSoftkeysSelectAndCancelWithTypedInstanceResults() = runComposeUiTest {
        val root = object : Screen<Unit>(Unit) { @Composable override fun Content() {} }
        val stack = ScreenStack(root)
        val results = mutableListOf<FileExplorerResult>()
        val explorer = FileExplorer()
        explorer.open(stack) { results += it }
        val selectDefinition = explorer.bottomKeys.getValue(BottomSoftKeySlot.F1)
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), currentStack = { stack }) {
                    ScreenStackHost(stack, Modifier.testTag("workspace"))
                }
            }
        }
        onNodeWithTag("softkey-F1").assertIsNotEnabled()
        onNodeWithText("example-b.nc").performClick()
        onNodeWithTag("softkey-F1").assertIsEnabled()
        onNodeWithTag("softkey-R7").performTouchInput { click() }
        onNodeWithTag("softkey-R7").assertIsSelected()
        runOnIdle { assertSame(selectDefinition, explorer.bottomKeys.getValue(BottomSoftKeySlot.F1)) }
        onNodeWithTag("softkey-F1").performTouchInput { click() }
        runOnIdle {
            assertSame(root, stack.top)
            assertEquals(listOf<FileExplorerResult>(FileExplorerResult.Selected("example-b.nc")), results)
            FileExplorer().open(stack) { results += it }
        }
        onNodeWithTag("workspace").performKeyInput { pressKey(Key.F8) }
        runOnIdle {
            assertSame(root, stack.top)
            assertEquals(FileExplorerResult.Cancelled, results.last())
        }
    }
}
