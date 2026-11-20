package no.grblcommander.desktop.mode.program

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.ScreenStackHost
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ProgramSoftKeyRenderingTest {
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

    @Test fun editorExplorerShowsIndependentBanksAndRestoresOnClose() = runComposeUiTest {
        val editor = Editor()
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
