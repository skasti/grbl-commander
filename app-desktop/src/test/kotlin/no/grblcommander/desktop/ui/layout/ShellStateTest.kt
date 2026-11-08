package no.grblcommander.desktop.ui.layout

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
