package no.grblcommander.core.connection

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties
import no.grblcommander.machine.transport.SerialConfiguration

/** Versioned endpoint configuration mapping, independent of the persistence mechanism. */
internal object ControllerEndpointRecord {
    fun decode(record: String?): ControllerEndpoint? {
        if (record == null) return null
        return try {
            val values = Properties().apply { load(StringReader(record)) }
            fun field(name: String) = requireNotNull(values.getProperty(name))
            require(field("version") in setOf("1", "2"))
            fun optionalBoolean(name: String, fallback: Boolean) = values.getProperty(name)?.toBooleanStrict() ?: fallback
            val actions = PostConnectActions(
                resetController = optionalBoolean("post.reset", false),
                firmwareInfo = values.getProperty("post.firmware")?.let(PostConnectActions.FirmwareInfo::valueOf)
                    ?: PostConnectActions.FirmwareInfo.BASIC,
                parameters = optionalBoolean("post.parameters", true),
                parserState = optionalBoolean("post.parser", true),
            )
            when (field("transport")) {
                "TCP" -> ControllerEndpoint.Tcp(field("host"), field("port").toInt(), actions)
                "SERIAL" -> ControllerEndpoint.Serial(SerialConfiguration(
                    port = field("device"), baudRate = field("baud").toInt(), dataBits = field("bits").toInt(),
                    stopBits = SerialConfiguration.StopBits.valueOf(field("stop")),
                    parity = SerialConfiguration.Parity.valueOf(field("parity")),
                    flowControl = SerialConfiguration.FlowControl.valueOf(field("flow")),
                    dtr = field("dtr").toBooleanStrict(), rts = field("rts").toBooleanStrict(),
                ), actions)
                else -> null
            }
        } catch (_: IllegalArgumentException) { null }
    }

    fun encode(endpoint: ControllerEndpoint): String {
        val values = Properties().apply {
            setProperty("version", "2")
            with(endpoint.postConnect) {
                setProperty("post.reset", resetController.toString())
                setProperty("post.firmware", firmwareInfo.name)
                setProperty("post.parameters", parameters.toString())
                setProperty("post.parser", parserState.toString())
            }
            when (endpoint) {
                is ControllerEndpoint.Tcp -> {
                    setProperty("transport", "TCP")
                    setProperty("host", endpoint.host)
                    setProperty("port", endpoint.port.toString())
                }
                is ControllerEndpoint.Serial -> with(endpoint.configuration) {
                    setProperty("transport", "SERIAL")
                    setProperty("device", port)
                    setProperty("baud", baudRate.toString())
                    setProperty("bits", dataBits.toString())
                    setProperty("stop", stopBits.name)
                    setProperty("parity", parity.name)
                    setProperty("flow", flowControl.name)
                    setProperty("dtr", dtr.toString())
                    setProperty("rts", rts.toString())
                }
            }
        }
        return StringWriter().also { values.store(it, null) }.toString()
    }
}
