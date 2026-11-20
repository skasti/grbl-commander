package no.grblcommander.desktop.mode.program

/** In-memory fixture, not a filesystem adapter or an executable machine program. */
data class ExampleFile(val name: String, val contents: String)

internal val exampleFiles = listOf(
    ExampleFile("example-a.nc", "; Example A\n; Preview only"),
    ExampleFile("example-b.nc", "; Example B\n; Preview only"),
)