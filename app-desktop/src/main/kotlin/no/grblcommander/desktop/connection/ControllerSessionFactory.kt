package no.grblcommander.desktop.connection

import kotlinx.coroutines.CoroutineScope
import no.grblcommander.machine.session.GrblMachineSession
import no.grblcommander.machine.session.MachineSession
import no.grblcommander.machine.transport.MachineTransport
import no.grblcommander.machine.transport.SerialTransport
import no.grblcommander.machine.transport.TcpTransport

/** One fresh production transport per explicit connection attempt. */
internal fun controllerTransport(endpoint: ControllerEndpoint): MachineTransport = when (endpoint) {
    is ControllerEndpoint.Tcp -> TcpTransport(endpoint.host, endpoint.port)
    is ControllerEndpoint.Serial -> SerialTransport(endpoint.configuration)
}

internal fun controllerSession(endpoint: ControllerEndpoint, scope: CoroutineScope): MachineSession =
    GrblMachineSession(controllerTransport(endpoint), scope)
