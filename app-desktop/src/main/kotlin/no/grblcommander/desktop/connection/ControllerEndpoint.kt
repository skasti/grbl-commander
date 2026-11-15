package no.grblcommander.desktop.connection

import java.net.URI
import no.grblcommander.machine.transport.SerialConfiguration

sealed interface ControllerEndpoint {
    data class Tcp(val host: String, val port: Int) : ControllerEndpoint {
        init {
            require(host.isNotBlank() && host.none { it.isWhitespace() || it.isISOControl() } &&
                host.none { it in "/\\?#@" }) { "Enter a hostname or IP address without a URL scheme or path" }
            require(port in 1..65535) { "TCP port must be between 1 and 65535" }
            require(runCatching {
                URI("tcp", null, host, port, null, null, null).parseServerAuthority().host != null
            }.getOrDefault(false)) { "Enter a valid hostname or IP address" }
        }
    }
    data class Serial(val configuration: SerialConfiguration) : ControllerEndpoint
}
