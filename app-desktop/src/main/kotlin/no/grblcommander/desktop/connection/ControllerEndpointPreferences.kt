package no.grblcommander.desktop.connection

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties
import java.util.prefs.Preferences
import no.grblcommander.machine.transport.SerialConfiguration

/** Selected endpoint only. Call on an I/O dispatcher; this API never connects or reconfigures. */
class ControllerEndpointPreferences(
    preferences: Preferences? = null,
) {
    private val preferences by lazy { preferences ?: Preferences.userRoot().node("no/grblcommander/controller") }
    /** Missing, incomplete, corrupt or unsupported records leave the endpoint unselected. */
    fun load(): ControllerEndpoint? {
        val record = preferences.get(KEY, null) ?: return null
        return try {
            val values = Properties().apply { load(StringReader(record)) }
            fun field(name: String) = requireNotNull(values.getProperty(name))
            require(field("version") == "1")
            when (field("transport")) {
                "TCP" -> ControllerEndpoint.Tcp(field("host"), field("port").toInt())
                "SERIAL" -> ControllerEndpoint.Serial(SerialConfiguration(
                    port = field("device"), baudRate = field("baud").toInt(), dataBits = field("bits").toInt(),
                    stopBits = SerialConfiguration.StopBits.valueOf(field("stop")),
                    parity = SerialConfiguration.Parity.valueOf(field("parity")),
                    flowControl = SerialConfiguration.FlowControl.valueOf(field("flow")),
                    dtr = field("dtr").toBooleanStrict(), rts = field("rts").toBooleanStrict(),
                ))
                else -> null
            }
        } catch (_: IllegalArgumentException) { null }
    }

    /** Flush failures propagate to the caller; a failed save must not be shown as successful. */
    fun save(endpoint: ControllerEndpoint) {
        val values = Properties().apply {
            setProperty("version", "1")
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
        // One versioned preference prevents partial per-field records when saving.
        val record = StringWriter().also { values.store(it, null) }.toString()
        require(record.length <= Preferences.MAX_VALUE_LENGTH) { "Controller settings are too long to save" }
        preferences.put(KEY, record)
        preferences.flush()
    }

    private companion object { const val KEY = "endpoint" }
}
