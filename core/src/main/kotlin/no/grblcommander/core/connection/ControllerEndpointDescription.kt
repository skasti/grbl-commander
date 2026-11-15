package no.grblcommander.core.connection

fun ControllerEndpoint.description(): String = when (this) {
    is ControllerEndpoint.Tcp -> "TCP ${if (host.contains(':') && !host.startsWith('[')) "[$host]" else host}:$port"
    is ControllerEndpoint.Serial -> "Serial ${configuration.port} · ${configuration.baudRate} baud"
}
