package no.grblcommander.desktop.connection

import no.grblcommander.machine.transport.SerialConfiguration

/** Editable values; creating or validating a draft never touches a controller. */
data class ControllerEndpointDraft(
    val transport: Transport = Transport.SERIAL,
    val host: String = "",
    val tcpPort: String = "",
    val serialPort: String = "",
    val baudRate: String = "115200",
    val dataBits: String = "8",
    val stopBits: SerialConfiguration.StopBits = SerialConfiguration.StopBits.ONE,
    val parity: SerialConfiguration.Parity = SerialConfiguration.Parity.NONE,
    val flowControl: SerialConfiguration.FlowControl = SerialConfiguration.FlowControl.NONE,
    val dtr: Boolean = false,
    val rts: Boolean = false,
) {
    enum class Transport { SERIAL, TCP }

    fun endpoint(): ControllerEndpoint = when (transport) {
        Transport.TCP -> ControllerEndpoint.Tcp(host.trim(), number(tcpPort, "TCP port", 1..65535))
        Transport.SERIAL -> ControllerEndpoint.Serial(SerialConfiguration(
            port = serialPort.trim(),
            baudRate = number(baudRate, "Baud rate", 1..Int.MAX_VALUE),
            dataBits = number(dataBits, "Data bits", 5..8),
            stopBits = stopBits, parity = parity, flowControl = flowControl, dtr = dtr, rts = rts,
        ))
    }

    private fun number(text: String, name: String, range: IntRange): Int {
        val value = text.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
        require(value != null && value in range) { "$name must be a number between ${range.first} and ${range.last}" }
        return value
    }

    companion object {
        fun from(endpoint: ControllerEndpoint): ControllerEndpointDraft = when (endpoint) {
            is ControllerEndpoint.Tcp -> ControllerEndpointDraft(
                transport = Transport.TCP, host = endpoint.host, tcpPort = endpoint.port.toString(),
            )
            is ControllerEndpoint.Serial -> endpoint.configuration.let {
                ControllerEndpointDraft(
                    serialPort = it.port, baudRate = it.baudRate.toString(), dataBits = it.dataBits.toString(),
                    stopBits = it.stopBits, parity = it.parity, flowControl = it.flowControl, dtr = it.dtr, rts = it.rts,
                )
            }
        }
    }
}
