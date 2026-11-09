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
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

val releaseVersion = providers.gradleProperty("appVersion").get()

compose.desktop {
    application {
        mainClass = "no.grblcommander.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "grbl-commander"
            packageVersion = releaseVersion
            description = "Desktop operator interface for GRBL-compatible CNC machines"

            // Compose Desktop 1.9 requires macOS package versions with major > 0.
            // The macOS native metadata uses a reversible major+1 mapping.
            // Configure it on the macOS host only; other hosts package their own formats.
            if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
                val parts = releaseVersion.split('.')
                require(parts.size == 3) { "Expected MAJOR.MINOR.PATCH: $releaseVersion" }
                val macVersion = listOf(
                    parts[0].toInt() + 1,
                    parts[1].toInt(),
                    parts[2].toInt(),
                ).joinToString(".")
                macOS {
                    packageVersion = macVersion
                    packageBuildVersion = macVersion
                }
            }
        }
    }
}
