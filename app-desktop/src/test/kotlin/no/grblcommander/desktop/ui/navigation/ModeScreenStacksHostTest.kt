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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ModeScreenStacksHostTest {
    private enum class Mode { Program, Manual }

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
        val modes = ModeScreenStacks(Mode.Program) {
            if (it == Mode.Program) program else manual
        }
        setContent { ModeScreenStacksHost(modes) }

        onNodeWithText("Program count 0").performTouchInput { click() }
        runOnIdle { modes.selectMode(Mode.Manual) }
        onNodeWithText("Program count 1").assertDoesNotExist()
        onNodeWithText("Manual count 0").performTouchInput { click() }
        runOnIdle { modes.selectMode(Mode.Program) }
        onNodeWithText("Program count 1").assertExists()
        onNodeWithText("Manual count 1").assertDoesNotExist()
        runOnIdle {
            assertEquals(1, rememberedObjects.size)
            assertEquals(0, disposed)
            assertSame(program, modes.activeStack.top)
        }
        runOnIdle { modes.selectMode(Mode.Manual) }
        onNodeWithText("Manual count 1").assertExists()
        runOnIdle { assertEquals(0, disposed) }
    }

    @Test
    fun nestedOverlaysAndTypedResultsStayOnTheirOriginalStack() = runComposeUiTest {
        val editor = EditorScreen()
        val manual = ManualModeScreen()
        val modes = ModeScreenStacks(Mode.Program) {
            if (it == Mode.Program) editor else manual
        }
        val programStack = modes.activeStack
        val outerResults = mutableListOf<FileExplorerResult>()
        val innerResults = mutableListOf<FileExplorerResult>()
        val outer = FileExplorer()
        val inner = FileExplorer()
        setContent { ModeScreenStacksHost(modes) }
        runOnIdle {
            editor.draft = "Unfinished work"
            outer.open(editor, outerResults::add)
            inner.open(outer, innerResults::add)
        }
        onNodeWithText("Choose an example file").assertExists()
        runOnIdle { modes.selectMode(Mode.Manual) }
        onNodeWithText("Open manual details").performTouchInput { click() }
        runOnIdle {
            assertEquals(emptyList(), outerResults)
            assertEquals(emptyList(), innerResults)
            assertSame(inner, programStack.top)
            assertTrue(modes.activeStack.top is ManualDetailsScreen)
        }
        runOnIdle { modes.selectMode(Mode.Program) }
        onNodeWithText("Choose an example file").assertExists()
        runOnIdle {
            assertSame(programStack, modes.activeStack)
            assertSame(inner, modes.activeStack.top)
            assertEquals(3, modes.activeStack.size)
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
        runOnIdle { modes.selectMode(Mode.Manual) }
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
        val modes = ModeScreenStacks(Mode.Program) {
            if (it == Mode.Program) program else manual
        }
        setContent { ModeScreenStacksHost(modes, Modifier.testTag("multi-host")) }
        onNodeWithTag("program-editor").performClick()
        runOnIdle { assertTrue(editorFocused) }
        val coveredPoint = onNodeWithTag("program-click").fetchSemanticsNode().boundsInRoot.center

        runOnIdle { modes.selectMode(Mode.Manual) }
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

        runOnIdle { modes.selectMode(Mode.Program) }
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
        val first = ModeScreenStacks(Mode.Program) { mode ->
            if (mode == Mode.Program) firstRoot else root("first manual")
        }
        val second = ModeScreenStacks(Mode.Program) { mode ->
            if (mode == Mode.Program) secondRoot else root("second manual")
        }
        setContent {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { ModeScreenStacksHost(first) }
                Box(Modifier.weight(1f)) { ModeScreenStacksHost(second) }
            }
        }
        onNodeWithText("Open first").performTouchInput { click() }
        runOnIdle {
            assertTrue(first.activeStack.top is FileExplorer)
            assertSame(secondRoot, second.activeStack.top)
        }
        runOnIdle { first.selectMode(Mode.Manual) }
        onNodeWithText("Open first manual").assertExists()
        onNodeWithText("Open second").performTouchInput { click() }
        runOnIdle {
            assertEquals(Mode.Program, second.activeMode)
            assertTrue(second.activeStack.top is FileExplorer)
            assertSame(firstRoot, first.stackFor(Mode.Program).screens.first())
            assertEquals(2, first.stackFor(Mode.Program).size)
        }
        runOnIdle { first.selectMode(Mode.Program) }
        onAllNodesWithText("Choose an example file").assertCountEquals(2)
        // Each host still owns its own top screen and result delivery.
        runOnIdle {
            assertTrue((first.activeStack.top as FileExplorer).cancel())
            assertSame(firstRoot, first.activeStack.top)
            assertTrue(second.activeStack.top is FileExplorer)
        }
    }
}
