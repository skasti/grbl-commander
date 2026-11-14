plugins {
    kotlin("jvm")
    `java-library`
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation("com.fazecast:jSerialComm:2.11.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

// External simulator tests are opt-in and deliberately excluded from check/test.
val simulatorTest = sourceSets.create("simulatorTest")
configurations[simulatorTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[simulatorTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    add(simulatorTest.implementationConfigurationName, sourceSets.main.get().output)
}

val simulatorBinary = providers.gradleProperty("grblhalSimulator")
    .orElse(providers.environmentVariable("GRBLHAL_SIMULATOR"))

tasks.register<Test>("simulatorTest") {
    description = "Run production TCP/session tests against the official grblHAL Simulator (Linux)."
    group = "verification"
    testClassesDirs = simulatorTest.output.classesDirs
    classpath = simulatorTest.runtimeClasspath
    useJUnitPlatform()
    dependsOn(simulatorTest.classesTaskName)
    // The external process and fresh EEPROM are part of every requested run.
    outputs.upToDateWhen { false }
    doFirst {
        check(System.getProperty("os.name").startsWith("Linux")) { "Simulator tests require Linux" }
        val binary = simulatorBinary.orNull?.let { file(it) }
        check(binary != null && binary.isFile && binary.canExecute()) {
            "Build with bash scripts/build-grblhal-simulator.sh and set GRBLHAL_SIMULATOR or -PgrblhalSimulator"
        }
        systemProperty("grblhal.simulator", binary.absolutePath)
    }
}
