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
            val version = field("version")
            require(version in setOf("1", "2"))
            fun optionalBoolean(name: String, fallback: Boolean) = if (version == "2") field(name).toBooleanStrict() else values.getProperty(name)?.toBooleanStrict() ?: fallback
            val actions = PostConnectActions(
                resetController = optionalBoolean("post.reset", false),
                firmwareInfo = (if (version == "2") field("post.firmware") else values.getProperty("post.firmware"))?.let(PostConnectActions.FirmwareInfo::valueOf)
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

    fun encode(endpoint: ControllerEndpoint, draft: ControllerEndpointDraft? = null): String {
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
        draft?.let { setDraft(values, it) }
        return StringWriter().also { values.store(it, null) }.toString()
    }
    /** Restore the complete saved form, including unselected transport fields. */
    fun decodeDraft(record: String?): ControllerEndpointDraft? {
        val endpoint = decode(record) ?: return null
        return try {
            val values = Properties().apply { load(StringReader(record!!)) }
            val base = ControllerEndpointDraft.from(endpoint)
            if (values.stringPropertyNames().none { it.startsWith("draft.") }) return base
            fun field(name: String) = requireNotNull(values.getProperty("draft." + name))
            val draft = base.copy(
                host = field("host"), tcpPort = field("tcpPort"), serialPort = field("serialPort"),
                baudRate = field("baudRate"), dataBits = field("dataBits"),
                stopBits = SerialConfiguration.StopBits.valueOf(field("stopBits")),
                parity = SerialConfiguration.Parity.valueOf(field("parity")),
                flowControl = SerialConfiguration.FlowControl.valueOf(field("flowControl")),
                dtr = field("dtr").toBooleanStrict(), rts = field("rts").toBooleanStrict(),
            )
            require(draft.endpoint() == endpoint)
            draft
        } catch (_: IllegalArgumentException) { null }
    }

    private fun setDraft(values: Properties, draft: ControllerEndpointDraft) = with(draft) {
        values.setProperty("draft.host", host)
        values.setProperty("draft.tcpPort", tcpPort)
        values.setProperty("draft.serialPort", serialPort)
        values.setProperty("draft.baudRate", baudRate)
        values.setProperty("draft.dataBits", dataBits)
        values.setProperty("draft.stopBits", stopBits.name)
        values.setProperty("draft.parity", parity.name)
        values.setProperty("draft.flowControl", flowControl.name)
        values.setProperty("draft.dtr", dtr.toString())
        values.setProperty("draft.rts", rts.toString())
    }
}
