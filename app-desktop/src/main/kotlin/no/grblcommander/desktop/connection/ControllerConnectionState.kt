package no.grblcommander.desktop.connection

import no.grblcommander.machine.session.MachineSnapshot

data class ControllerConnectionState(
    val endpoint: ControllerEndpoint? = null,
    val snapshot: MachineSnapshot = MachineSnapshot(),
    val closing: Boolean = false,
)
