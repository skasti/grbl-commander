package no.grblcommander.desktop.ui.layout

import java.io.IOException
import javax.imageio.ImageIO
import no.grblcommander.desktop.ui.BrandAssets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShellStateTest {
    @Test
    fun startupLayoutDefinesAllSoftkeyPositions() {
        val state = ShellState.startup()

        assertEquals((1..8).map { "F$it" }, state.bottomKeys.map { it.shortcut })
        assertEquals((1..7).map { "R$it" }, state.rightKeys.map { it.shortcut })
    }

    @Test
    fun startupStateIsExplicitlyDisconnectedAndDoesNotAdvertiseActions() {
        val state = ShellState.startup()

        assertEquals("Ingen maskin tilkoblet", state.connectionStatus)
        assertTrue((state.bottomKeys + state.rightKeys).all { it.label == "—" })
    }
    @Test
    fun headerIconPreservesTransparentCorners() {
        val stream = javaClass.classLoader.getResourceAsStream(BrandAssets.ICON)
        assertNotNull(stream, "Missing icon resource")
        stream.use {
            val image = ImageIO.read(it) ?: throw IOException("Cannot decode icon resource")
            assertTrue(image.colorModel.hasAlpha(), "The application icon must retain an alpha channel")
            assertEquals(0, image.getRGB(0, 0) ushr 24, "The icon background must be transparent")
            assertTrue((image.getRGB(image.width / 2, image.height / 2) ushr 24) > 0)
        }
    }

    @Test
    fun brandingImagesAreBundledAndReadable() {
        for (resource in listOf(BrandAssets.ICON, BrandAssets.SPLASH)) {
            val stream = javaClass.classLoader.getResourceAsStream(resource)
            assertNotNull(stream, "Missing image resource: $resource")
            stream.use {
                val image = ImageIO.read(it) ?: throw IOException("Cannot decode image resource: $resource")
                assertTrue(image.width > 0 && image.height > 0)
            }
        }
    }
}
