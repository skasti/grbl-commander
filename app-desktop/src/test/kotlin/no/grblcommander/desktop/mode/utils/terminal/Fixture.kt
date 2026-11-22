package no.grblcommander.desktop.mode.utils.terminal

import no.grblcommander.desktop.mode.ControllerFixture
import no.grblcommander.desktop.ui.navigation.ScreenStack

internal class Fixture : ControllerFixture() {
    val terminal = Terminal(settings)
    val util = ScreenStack(terminal)
}
