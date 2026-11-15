package no.grblcommander.desktop.connection

import no.grblcommander.machine.transport.SerialConfiguration

sealed interface ControllerEndpoint {
    data class Tcp(val host: String, val port: Int) : ControllerEndpoint {
        init { require(host.isNotBlank()); require(port in 1..65535) }
    }
    data class Serial(val configuration: SerialConfiguration) : ControllerEndpoint
}
