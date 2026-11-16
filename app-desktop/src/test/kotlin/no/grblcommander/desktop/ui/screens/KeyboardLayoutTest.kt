package no.grblcommander.desktop.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import kotlinx.coroutines.*
import no.grblcommander.configuration.ControllerEndpointPreferences
import no.grblcommander.core.connection.ControllerConnection
import no.grblcommander.core.connection.ControllerSettings
import no.grblcommander.desktop.ui.layout.AppShell
import no.grblcommander.desktop.ui.layout.ShellState
import no.grblcommander.desktop.ui.navigation.*
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class KeyboardLayoutTest {
    @Test fun `terminal and MDI keyboard stay inside workspace at 1024 by 768`() = verifyLayout(1024f, 768f)
    @Test fun `terminal and MDI keyboard stay inside workspace at 800 by 600`() = verifyLayout(800f, 600f)

    private fun verifyLayout(width: Float, height: Float) = runSkikoComposeUiTest(size = Size(width, height)) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = ControllerConnection(scope)
        val node = Preferences.userRoot().node("no/grblcommander/tests/${UUID.randomUUID()}")
        try {
            val terminal = ScreenStack(Terminal(ControllerSettings(owner, ControllerEndpointPreferences(node))))
            val mdi = ScreenStack(MdiMode())
            var active by androidx.compose.runtime.mutableStateOf(terminal)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) { AppShell(ShellState.startup(), { active }) { ScreenStackHost(listOf(terminal, mdi), active) } }
            }
            fun check(input: SemanticsNodeInteraction) {
                val bottom = onNodeWithTag("softkey-F1").fetchSemanticsNode().boundsInRoot
                val right = onNodeWithTag("softkey-R1").fetchSemanticsNode().boundsInRoot
                val field = input.fetchSemanticsNode().boundsInRoot
                val first = onNodeWithTag("keypad-ABC-0").fetchSemanticsNode().boundsInRoot
                val last = onNodeWithTag("keypad-123-15").fetchSemanticsNode().boundsInRoot
                val footer = onNodeWithTag("keypad-123-next").fetchSemanticsNode().boundsInRoot
                assertTrue(field.height >= 48f, "Input remains a usable touch target: $field")
                assertTrue(first.left >= field.right, "Keyboard must stay to the right of the input")
                val lettersFooter = onNodeWithTag("keypad-ABC-next").fetchSemanticsNode().boundsInRoot
                val numbersFirst = onNodeWithTag("keypad-123-0").fetchSemanticsNode().boundsInRoot
                assertTrue(numbersFirst.top >= lettersFooter.bottom, "Numbers must be below letters")
                assertTrue(footer.bottom <= bottom.top, "Keyboard must not overlap bottom softkeys")
                assertTrue(first.left >= 0 && last.right <= right.left, "Keyboard must stay inside workspace")
                assertEquals(first.width, first.height, 1f)
                if (width >= 1024f) assertTrue(first.width >= 44f)
            }
            fun preview(name: String) {
                val image = captureToImage()
                val pixels = image.toPixelMap()
                val out = java.awt.image.BufferedImage(image.width, image.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until image.height) for (x in 0 until image.width) out.setRGB(x, y, pixels[x, y].toArgb())
                val file = java.io.File("build/keypad-previews/$name-${width.toInt()}.png")
                file.parentFile.mkdirs()
                javax.imageio.ImageIO.write(out, "png", file)
            }
            check(onNodeWithTag("terminal-prompt"))
            preview("terminal")
            runOnIdle { active = mdi }
            check(onNodeWithText("Unsent text"))
            preview("mdi")
        } finally {
            owner.close(); runBlocking { owner.awaitClosed() }; scope.cancel()
            node.removeNode(); Preferences.userRoot().flush()
        }
    }
}
