package no.grblcommander.desktop.ui.components.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ModalDialogTest {
    private class Root : Screen<Unit>(Unit) {
        val answers = mutableListOf<String>()
        var underlyingActions = 0
        override val bottomKeys = mapOf(
            BottomSoftKeySlot.F2 to SoftKeyDefinition("Underlying action", action = { underlyingActions++ }),
        )
        override val rightKeys = mapOf(
            RightSoftKeySlot.R1 to SoftKeyDefinition("Underlying right action", action = { underlyingActions++ }),
        )

        fun dialog() = ModalDialog(
            title = "Interrupt current connection?",
            message = "This closes the current controller session. Pending commands fail and machine execution may continue with an unknown outcome. Nothing is automatically stopped, reset or replayed.",
            cancellationResult = "cancelled",
            choices = mapOf(
                BottomSoftKeySlot.F1 to ModalDialog.Choice("Confirm", "confirmed"),
                BottomSoftKeySlot.F8 to ModalDialog.Choice("Cancel", "cancelled"),
            ),
        )

        fun openDialog() {
            dialog().open(this) { answer ->
                assertSame(this, screenStack.top)
                answers += answer
            }
        }

        @Composable override fun Content() {
            var draft by remember { mutableStateOf("") }
            Column {
                OutlinedTextField(draft, { draft = it }, label = { Text("Retained draft") })
            }
        }
    }

    @Test fun `choices return typed results once and replace both banks until the parent is restored`() {
        val root = Root()
        val stack = ScreenStack(root)
        val results = mutableListOf<Int>()
        val dialog = ModalDialog(
            "Choose", "Choose a value", cancellationResult = -1,
            choices = mapOf(
                BottomSoftKeySlot.F1 to ModalDialog.Choice("First", 42),
                BottomSoftKeySlot.F3 to ModalDialog.Choice("Another", 99),
                BottomSoftKeySlot.F8 to ModalDialog.Choice("Cancel", -1),
            ),
        )
        dialog.open(root) { result -> assertSame(root, stack.top); results += result }
        assertEquals(setOf(BottomSoftKeySlot.F1, BottomSoftKeySlot.F3, BottomSoftKeySlot.F8), stack.effectiveBottomKeys.keys)
        assertTrue(stack.effectiveRightKeys.isEmpty())
        assertFalse(activateSoftKey({ stack }, BottomSoftKeySlot.F2))
        assertFalse(activateSoftKey({ stack }, RightSoftKeySlot.R1))
        val staleChoice = stack.softKeyDefinition(BottomSoftKeySlot.F3)!!.action!!
        assertTrue(activateSoftKey({ stack }, BottomSoftKeySlot.F3))
        staleChoice()
        assertEquals(listOf(99), results)
        assertEquals(0, root.underlyingActions)
        assertEquals("Underlying action", stack.softKeyDefinition(BottomSoftKeySlot.F2)!!.label)
        assertEquals("Underlying right action", stack.softKeyDefinition(RightSoftKeySlot.R1)!!.label)
    }

    @Test fun `stack cancellation delivers the cancellation result without confirming`() {
        val root = Root()
        val stack = ScreenStack(root)
        root.openDialog()
        val staleConfirm = stack.softKeyDefinition(BottomSoftKeySlot.F1)!!.action!!
        stack.pop()
        staleConfirm()
        assertEquals(listOf("cancelled"), root.answers)
        assertSame(root, stack.top)
    }

    @Test fun `modal uses shell softkeys for pointer and keyboard choices without workspace buttons`() = runComposeUiTest {
        val root = Root()
        val stack = ScreenStack(root)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppShell(ShellState.startup(), { stack }) { ScreenStackHost(stack) }
            }
        }
        onNodeWithText("Retained draft").performTextReplacement("Keep these edits")
        runOnIdle { root.openDialog() }
        onNodeWithText("Interrupt current connection?").assertExists()
        onNodeWithText("Retained draft").assertDoesNotExist()
        // The only visible choice labels belong to the persistent bottom softkey bank.
        onNodeWithTag("softkey-F1").assertTextContains("Confirm")
        onNodeWithTag("softkey-F8").assertTextContains("Cancel")
        onAllNodes(hasClickAction() and isEnabled()).assertCountEquals(2)
        onNodeWithTag("softkey-F1").performClick()
        runOnIdle { assertEquals(listOf("confirmed"), root.answers) }
        onNodeWithText("Keep these edits").assertExists()
        runOnIdle { root.openDialog() }
        onRoot().performKeyInput { pressKey(Key.F8) }
        runOnIdle { assertEquals(listOf("confirmed", "cancelled"), root.answers) }
        runOnIdle { root.openDialog() }
        onRoot().performKeyInput { pressKey(Key.F1) }
        runOnIdle { assertEquals(listOf("confirmed", "cancelled", "confirmed"), root.answers) }
    }

    @Test fun `mode switches retain a pending modal and do not deliver its result in another mode`() = runComposeUiTest {
        val root = Root()
        val util = ScreenStack(root)
        val other = ScreenStack(Root())
        var active by mutableStateOf(util)
        setContent {
            MaterialTheme {
                AppShell(ShellState.startup(), { active }) { ScreenStackHost(listOf(util, other), active) }
            }
        }
        onNodeWithText("Retained draft").performTextReplacement("Retained while covered")
        runOnIdle { root.openDialog() }
        val modal = util.top
        runOnIdle { active = other }
        onNodeWithText("Interrupt current connection?").assertDoesNotExist()
        runOnIdle {
            activateSoftKey({ active }, BottomSoftKeySlot.F2)
            assertTrue(root.answers.isEmpty())
            assertSame(modal, util.top)
            active = util
        }
        onNodeWithText("Interrupt current connection?").assertExists()
        onNodeWithTag("softkey-F8").performClick()
        onNodeWithText("Retained while covered").assertExists()
        runOnIdle { assertEquals(listOf("cancelled"), root.answers); assertSame(root, util.top) }
    }
}
