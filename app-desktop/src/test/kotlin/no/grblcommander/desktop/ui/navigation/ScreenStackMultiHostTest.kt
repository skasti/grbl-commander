package no.grblcommander.desktop.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.screens.EditorScreen
import no.grblcommander.desktop.ui.screens.FileExplorer
import no.grblcommander.desktop.ui.screens.FileExplorerResult
import no.grblcommander.desktop.ui.screens.ManualDetailsScreen
import no.grblcommander.desktop.ui.screens.ManualModeScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ScreenStackMultiHostTest {

    @Test
    fun nonSaveableRememberAndScreenCompositionsSurviveRoundTrip() = runComposeUiTest {
        val rememberedObjects = mutableListOf<Any>()
        var disposed = 0
        val program = object : Screen<Unit>(Unit) {
            @Composable override fun Content() {
                remember { Any().also(rememberedObjects::add) }
                var count by remember { mutableStateOf(0) }
                DisposableEffect(Unit) { onDispose { disposed++ } }
                Button(onClick = { count++ }) { Text("Program count $count") }
            }
        }
        val manual = object : Screen<Unit>(Unit) {
            @Composable override fun Content() {
                var count by remember { mutableStateOf(0) }
                Button(onClick = { count++ }) { Text("Manual count $count") }
            }
        }
        val programStack = ScreenStack(program)
        val manualStack = ScreenStack(manual)
        var activeStack by mutableStateOf(programStack)
        val stacks = listOf(programStack, manualStack)
        setContent { ScreenStackHost(stacks, activeStack) }

        onNodeWithText("Program count 0").performTouchInput { click() }
        runOnIdle { activeStack = manualStack }
        onNodeWithText("Program count 1").assertDoesNotExist()
        onNodeWithText("Manual count 0").performTouchInput { click() }
        runOnIdle { activeStack = programStack }
        onNodeWithText("Program count 1").assertExists()
        onNodeWithText("Manual count 1").assertDoesNotExist()
        runOnIdle {
            assertEquals(1, rememberedObjects.size)
            assertEquals(0, disposed)
            assertSame(program, activeStack.top)
        }
        runOnIdle { activeStack = manualStack }
        onNodeWithText("Manual count 1").assertExists()
        runOnIdle { assertEquals(0, disposed) }
    }

    @Test
    fun nestedOverlaysAndTypedResultsStayOnTheirOriginalStack() = runComposeUiTest {
        val editor = EditorScreen()
        val manual = ManualModeScreen()
        val programStack = ScreenStack(editor)
        val manualStack = ScreenStack(manual)
        var activeStack by mutableStateOf(programStack)
        val stacks = listOf(programStack, manualStack)
        val outerResults = mutableListOf<FileExplorerResult>()
        val innerResults = mutableListOf<FileExplorerResult>()
        val outer = FileExplorer()
        val inner = FileExplorer()
        setContent { ScreenStackHost(stacks, activeStack) }
        runOnIdle {
            editor.draft = "Unfinished work"
            outer.open(editor, outerResults::add)
            inner.open(outer, innerResults::add)
        }
        onNodeWithText("Choose an example file").assertExists()
        runOnIdle { activeStack = manualStack }
        onNodeWithText("Open manual details").performTouchInput { click() }
        runOnIdle {
            assertEquals(emptyList(), outerResults)
            assertEquals(emptyList(), innerResults)
            assertSame(inner, programStack.top)
            assertTrue(activeStack.top is ManualDetailsScreen)
        }
        runOnIdle { activeStack = programStack }
        onNodeWithText("Choose an example file").assertExists()
        runOnIdle {
            assertSame(programStack, activeStack)
            assertSame(inner, activeStack.top)
            assertEquals(3, activeStack.size)
            assertTrue(inner.cancel())
            assertSame(outer, programStack.top)
            assertTrue(outer.selectFile("example-a.nc"))
        }
        onNodeWithText("Unfinished work").assertExists()
        runOnIdle {
            assertEquals(listOf<FileExplorerResult>(FileExplorerResult.Cancelled), innerResults)
            assertEquals(listOf<FileExplorerResult>(FileExplorerResult.Selected("example-a.nc")), outerResults)
            assertFalse(inner.cancel())
            assertFalse(outer.cancel())
            assertSame(editor, programStack.top)
            assertEquals("Unfinished work", editor.draft)
        }
        runOnIdle { activeStack = manualStack }
        onNodeWithText("Back to Manual").assertExists()
    }

    @Test
    fun inactiveModeHasNoPointerKeyboardFocusOrAccessibilityAccess() = runComposeUiTest {
        var programClicks = 0
        var editorFocused = false
        var programText = "draft"
        val program = object : Screen<Unit>(Unit) {
            @Composable override fun Content() {
                var text by remember { mutableStateOf(programText) }
                Column {
                    BasicTextField(
                        text,
                        { text = it; programText = it },
                        Modifier.testTag("program-editor").onFocusChanged { editorFocused = it.isFocused },
                    )
                    Box(Modifier.size(100.dp).testTag("program-click").clickable { programClicks++ })
                }
            }
        }
        val manual = object : Screen<Unit>(Unit) {
            @Composable override fun Content() {
                Text("Manual interactive workspace")
            }
        }
        val programStack = ScreenStack(program)
        val manualStack = ScreenStack(manual)
        var activeStack by mutableStateOf(programStack)
        val stacks = listOf(programStack, manualStack)
        setContent { ScreenStackHost(stacks, activeStack, Modifier.testTag("multi-host")) }
        onNodeWithTag("program-editor").performClick()
        runOnIdle { assertTrue(editorFocused) }
        val coveredPoint = onNodeWithTag("program-click").fetchSemanticsNode().boundsInRoot.center

        runOnIdle { activeStack = manualStack }
        onNodeWithText("Manual interactive workspace").assertExists()
        onNodeWithTag("program-editor").assertDoesNotExist()
        onNodeWithTag("program-click").assertDoesNotExist()
        onNodeWithTag("multi-host").performTouchInput { click(coveredPoint) }
        onNodeWithTag("multi-host").performMouseInput { click(coveredPoint) }
        onNodeWithTag("multi-host").performKeyInput { pressKey(Key.A) }
        repeat(4) { onNodeWithTag("multi-host").performKeyInput { pressKey(Key.Tab) } }
        runOnIdle {
            assertEquals(0, programClicks)
            assertEquals("draft", programText)
            assertFalse(editorFocused)
        }

        runOnIdle { activeStack = programStack }
        onNodeWithTag("program-editor").assertExists()
        onNodeWithTag("program-click").performTouchInput { click() }
        runOnIdle { assertEquals(1, programClicks) }
    }

    @Test
    fun twoWindowHostsKeepDifferentModesAndNavigationBindings() = runComposeUiTest {
        fun root(label: String) = object : Screen<Unit>(Unit) {
            @Composable override fun Content() {
                Button(onClick = { FileExplorer().open(this) }) { Text("Open $label") }
            }
        }
        val firstRoot = root("first")
        val secondRoot = root("second")
        val firstProgramStack = ScreenStack(firstRoot)
        val firstManualStack = ScreenStack(root("first manual"))
        val secondProgramStack = ScreenStack(secondRoot)
        val secondManualStack = ScreenStack(root("second manual"))
        var firstActive by mutableStateOf(firstProgramStack)
        var secondActive by mutableStateOf(secondProgramStack)
        setContent {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { ScreenStackHost(listOf(firstProgramStack, firstManualStack), firstActive) }
                Box(Modifier.weight(1f)) { ScreenStackHost(listOf(secondProgramStack, secondManualStack), secondActive) }
            }
        }
        onNodeWithText("Open first").performTouchInput { click() }
        runOnIdle {
            assertTrue(firstActive.top is FileExplorer)
            assertSame(secondRoot, secondActive.top)
        }
        runOnIdle { firstActive = firstManualStack }
        onNodeWithText("Open first manual").assertExists()
        onNodeWithText("Open second").performTouchInput { click() }
        runOnIdle {
            assertSame(secondProgramStack, secondActive)
            assertTrue(secondActive.top is FileExplorer)
            assertSame(firstRoot, firstProgramStack.screens.first())
            assertEquals(2, firstProgramStack.size)
        }
        runOnIdle { firstActive = firstProgramStack }
        onAllNodesWithText("Choose an example file").assertCountEquals(2)
        // Each host still owns its own top screen and result delivery.
        runOnIdle {
            assertTrue((firstActive.top as FileExplorer).cancel())
            assertSame(firstRoot, firstActive.top)
            assertTrue(secondActive.top is FileExplorer)
        }
    }

    @Test
    fun inactiveHostsNeverStealFocusWhenTheirTopScreenChanges() = runComposeUiTest {
        var manualFocused = false
        val program = object : Screen<Unit>(Unit) {
            @Composable override fun Content() { Text("Program root") }
        }
        val manual = object : Screen<Unit>(Unit) {
            @Composable override fun Content() {
                BasicTextField(
                    "Manual draft",
                    onValueChange = {},
                    modifier = Modifier.testTag("manual-input").onFocusChanged {
                        manualFocused = it.isFocused
                    },
                )
            }
        }
        val programStack = ScreenStack(program)
        val manualStack = ScreenStack(manual)
        var activeStack by mutableStateOf(programStack)
        val stacks = listOf(programStack, manualStack)
        setContent { ScreenStackHost(stacks, activeStack) }
        runOnIdle { activeStack = manualStack }
        onNodeWithTag("manual-input").performClick()
        runOnIdle { assertTrue(manualFocused) }

        // Direct-stack opening is allowed; it must neither redirect navigation
        // into Manual nor activate or focus the inactive Program host.
        val explorer = FileExplorer()
        runOnIdle { explorer.open(programStack) }
        onNodeWithTag("manual-input").assertExists()
        onNodeWithText("Choose an example file").assertDoesNotExist()
        runOnIdle {
            assertTrue(manualFocused)
            assertSame(explorer, programStack.top)
            assertSame(manual, activeStack.top)
        }

        runOnIdle { activeStack = programStack }
        onNodeWithText("Choose an example file").assertExists()
        runOnIdle { assertFalse(manualFocused) }
    }
}
