package no.grblcommander.desktop.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals

private val screenshotDirectory = System.getProperty("grblcommander.uiScreenshotDirectory")?.let(::File)
private val screenshotSizes = listOf(
    Size(1024f, 720f),
    Size(1680f, 1050f),
)

@OptIn(ExperimentalTestApi::class)
internal fun runAtScreenshotSizes(
    capture: Boolean = true,
    test: suspend SkikoComposeUiTest.(screenshot: (String) -> Unit) -> Unit,
) {
    val directory = screenshotDirectory.takeIf { capture }
    // Resolve the synchronous caller before entering the suspending Compose runner.
    val source = if (directory == null) null else Throwable().stackTrace.first {
        it.fileName?.endsWith(".kt") == true && it.fileName != "UiScreenshots.kt"
    }.let { frame ->
        "app-desktop/src/test/kotlin/${frame.className.substringBeforeLast('.').replace('.', '/')}/${frame.fileName}"
    }
    val sizes = if (directory == null) listOf(Size(1024f, 768f)) else screenshotSizes
    for (size in sizes) {
        runSkikoComposeUiTest(size = size) {
            test { scenario ->
                if (directory != null) saveScreenshot(directory, scenario, size, requireNotNull(source))
            }
        }
    }
}

@OptIn(ExperimentalTestApi::class)
private fun SkikoComposeUiTest.saveScreenshot(directory: File, scenario: String, size: Size, source: String) {
    require(scenario.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "Invalid screenshot scenario: $scenario" }
    val image = captureToImage()
    val width = image.width
    val height = image.height
    assertEquals(size.width.toInt(), width, "$scenario screenshot width")
    assertEquals(size.height.toInt(), height, "$scenario screenshot height")
    val pixels = IntArray(width * height)
    image.readPixels(pixels)
    val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    output.setRGB(0, 0, width, height, pixels, 0, width)

    val file = directory.resolve(scenario).resolve("${width}x${height}.png")
    file.parentFile.mkdirs()
    val ownership = file.parentFile.resolve("capture-source.txt")
    check(!ownership.exists() || ownership.readText() == "$source\n") {
        "Screenshot scenario $scenario is shared by different capture sources"
    }
    ownership.writeText("$source\n")
    check(ImageIO.write(output, "png", file)) { "Unable to write screenshot to $file" }
}
