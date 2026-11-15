plugins {
    kotlin("jvm")
    `java-library`
    `java-test-fixtures`
}

kotlin { jvmToolchain(21) }

dependencies {
    // Public core APIs retain the established immutable machine/serial model types.
    api(project(":infrastructure"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
