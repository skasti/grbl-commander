package no.grblcommander.desktop.ui.layout

import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import no.grblcommander.desktop.generated.resources.Res
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShellStateTest {
    @Test
    fun startupStateIsExplicitlyDisconnected() {
        assertEquals("Ingen maskin tilkoblet", ShellState.startup().connectionStatus)
    }
    @Test
    fun headerIconPreservesTransparentCorners() {
        val image = readImage("drawable/grbl_commander_icon.png")
        assertTrue(image.colorModel.hasAlpha(), "The application icon must retain an alpha channel")
        assertEquals(0, image.getRGB(0, 0) ushr 24, "The icon background must be transparent")
        assertTrue((image.getRGB(image.width / 2, image.height / 2) ushr 24) > 0)
    }

    @Test
    fun brandingImagesAreBundledAndReadable() {
        for (resource in listOf(
            "drawable/grbl_commander_icon.png",
            "drawable/grbl_commander_splash.jpg",
        )) {
            val image = readImage(resource)
            assertTrue(image.width > 0 && image.height > 0)
        }
    }

    private fun readImage(resource: String) = runBlocking {
        val bytes = Res.readBytes(resource)
        ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IOException("Cannot decode Compose resource: $resource")
    }
}
