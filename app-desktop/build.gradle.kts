import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    testImplementation(testFixtures(project(":core")))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.components.resources)
    // Confine live terminal updates to the desktop event-dispatch thread.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation(kotlin("test-junit5"))
    testImplementation(compose.desktop.uiTestJUnit4)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

compose.resources {
    publicResClass = true
    packageOfResClass = "no.grblcommander.desktop.generated.resources"
}

val uiScreenshots = providers.gradleProperty("uiScreenshots").map { it.isEmpty() || it.toBoolean() }.orElse(false)
val uiScreenshotDirectory = layout.buildDirectory.dir("ui-screenshots")

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
    inputs.property("uiScreenshots", uiScreenshots)
    if (uiScreenshots.get()) {
        val screenshots = uiScreenshotDirectory.get().asFile
        systemProperty("grblcommander.uiScreenshotDirectory", screenshots.absolutePath)
        outputs.dir(screenshots)
        doFirst { screenshots.deleteRecursively() }
    }
}

val releaseVersion = providers.environmentVariable("RELEASE_VERSION")
    .orElse(providers.gradleProperty("appVersion"))
    .get()

// The UI already uses this PNG. Generate a Windows-native multi-resolution ICO from
// the same asset so the packaged executable and its Start menu shortcut match it.
// No additional image processing tools are required to build locally.
val windowsIconSource = layout.projectDirectory.file(
    "src/main/composeResources/drawable/grbl_commander_icon.png"
)
val windowsIconOutput = layout.buildDirectory.file("generated/windows/grbl-commander.ico")
val generateWindowsIcon = tasks.register("generateWindowsIcon") {
    inputs.file(windowsIconSource)
    outputs.file(windowsIconOutput)

    doLast {
        val source = requireNotNull(ImageIO.read(windowsIconSource.asFile)) {
            "Unable to read GRBL Commander application icon"
        }
        val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
        val images = sizes.map { size ->
            val target = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val scale = size.toDouble() / maxOf(source.width, source.height)
            val width = (source.width * scale).roundToInt().coerceAtLeast(1)
            val height = (source.height * scale).roundToInt().coerceAtLeast(1)
            val graphics = target.createGraphics()
            try {
                graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                )
                graphics.drawImage(
                    source, (size - width) / 2, (size - height) / 2,
                    width, height, null,
                )
            } finally {
                graphics.dispose()
            }
            ByteArrayOutputStream().use { stream ->
                check(ImageIO.write(target, "png", stream))
                stream.toByteArray()
            }
        }

        // ICO directory plus PNG-compressed icon entries (supported by Windows).
        val headerLength = 6 + 16 * images.size
        val ico = ByteBuffer.allocate(headerLength + images.sumOf { it.size })
            .order(ByteOrder.LITTLE_ENDIAN)
        ico.putShort(0.toShort()) // reserved
        ico.putShort(1.toShort()) // ICO image type
        ico.putShort(images.size.toShort())
        var imageOffset = headerLength
        for ((index, png) in images.withIndex()) {
            val size = sizes[index]
            ico.put((if (size == 256) 0 else size).toByte())
            ico.put((if (size == 256) 0 else size).toByte())
            ico.put(0.toByte()) // palette size
            ico.put(0.toByte()) // reserved
            ico.putShort(1.toShort()) // color planes
            ico.putShort(32.toShort()) // RGBA bits per pixel
            ico.putInt(png.size)
            ico.putInt(imageOffset)
            imageOffset += png.size
        }
        images.forEach(ico::put)
        val output = windowsIconOutput.get().asFile
        output.parentFile.mkdirs()
        output.writeBytes(ico.array())
    }
}

// Compose's native packaging task consumes the generated file instead of
// relying on jpackage's default Java icon.
tasks.matching { it.name == "packageMsi" || it.name == "createDistributable" }
    .configureEach { dependsOn(generateWindowsIcon) }

compose.desktop {
    application {
        mainClass = "no.grblcommander.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi)
            licenseFile.set(rootProject.file("LICENSE.md"))
            appResourcesRootDir.set(project.layout.projectDirectory.dir("packaging"))
            packageName = "grbl-commander"
            packageVersion = releaseVersion
            description = "Desktop operator interface for GRBL-compatible CNC machines"

            linux {
                rpmLicenseType = "PolyForm-Noncommercial-1.0.0"
            }

            windows {
                // Add an entry in the Windows Start menu; no desktop shortcut.
                menu = true
                menuGroup = "GRBL Commander"
                iconFile.set(windowsIconOutput)
            }
        }
    }
}

