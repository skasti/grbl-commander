package no.grblcommander.core.connection

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import no.grblcommander.configuration.ControllerEndpointPreferences
import no.grblcommander.machine.transport.SerialConfiguration

class ControllerEndpointSettingsTest {
    private fun preferences(test: (Preferences) -> Unit) {
        val node = Preferences.userRoot().node("no/grblcommander/tests/${UUID.randomUUID()}")
        try { test(node) } finally { node.removeNode(); Preferences.userRoot().flush() }
    }

    @Test fun `TCP settings survive a fresh preferences instance without a fixed port`() = preferences { node ->
        assertNull(ControllerEndpointRecord.decode(ControllerEndpointPreferences(node).load()))
        for (port in listOf(1, 65535, 4321)) {
            val endpoint = ControllerEndpoint.Tcp("controller.local", port)
            ControllerEndpointPreferences(node).save(ControllerEndpointRecord.encode(endpoint))
            node.sync()
            val reopened = Preferences.userRoot().node(node.absolutePath())
            assertEquals(endpoint, ControllerEndpointRecord.decode(ControllerEndpointPreferences(reopened).load()))
        }
    }

    @Test fun `serial defaults and every advanced option round trip including unavailable device`() = preferences { node ->
        for (stop in SerialConfiguration.StopBits.entries) {
            for (parity in SerialConfiguration.Parity.entries) {
                for (flow in SerialConfiguration.FlowControl.entries) {
                    for (line in listOf(false, true)) {
                        val endpoint = ControllerEndpoint.Serial(SerialConfiguration(
                            "COM987", 230400, 7, stop, parity, flow, dtr = line, rts = !line,
                        ))
                        ControllerEndpointPreferences(node).save(ControllerEndpointRecord.encode(endpoint))
                        node.sync()
                        assertEquals(endpoint, ControllerEndpointRecord.decode(ControllerEndpointPreferences(node).load()))
                    }
                }
            }
        }
        val defaults = ControllerEndpointDraft(serialPort = "/dev/absent-device").endpoint()
        assertEquals(ControllerEndpoint.Serial(SerialConfiguration("/dev/absent-device")), defaults)
        assertTrue((defaults as ControllerEndpoint.Serial).configuration.dtr)
        assertFalse(defaults.configuration.rts)
        ControllerEndpointPreferences(node).save(ControllerEndpointRecord.encode(defaults))
        assertEquals(defaults, ControllerEndpointRecord.decode(ControllerEndpointPreferences(node).load()))
    }

    @Test fun `invalid stored records leave endpoint unselected`() = preferences { node ->
        val valid = "version=1\ntransport=SERIAL\ndevice=COM3\nbaud=115200\nbits=8\nstop=ONE\nparity=NONE\nflow=NONE\ndtr=false\nrts=false"
        val records = listOf(
            "", "garbage", "version=99\ntransport=TCP\nhost=localhost\nport=1234",
            "version=1\ntransport=TCP\nhost=localhost\nport=0",
            "version=1\ntransport=TCP\nhost=\nport=1234",
            "version=1\ntransport=TCP\nhost=localhost\nport=65536",
            "version=1\ntransport=TCP\nhost=localhost\nport=abc",
            valid.replace("baud=115200", "baud=-1"), valid.replace("bits=8", "bits=9"),
            valid.replace("device=COM3", "device="), valid.replace("parity=NONE", "parity=OTHER"),
            valid.replace("dtr=false", "dtr=yes"), valid.replace("rts=false", ""),
            "version=1\ntransport=OTHER", "version=\\uINVALID",
        )
        for (record in records) {
            node.put("endpoint", record)
            assertNull(ControllerEndpointRecord.decode(ControllerEndpointPreferences(node).load()), record)
        }
    }

    @Test fun `legacy explicit false survives and action preferences round trip for both transports`() {
        val legacy = "version=1\ntransport=SERIAL\ndevice=COM3\nbaud=115200\nbits=8\nstop=ONE\nparity=NONE\nflow=NONE\ndtr=false\nrts=false"
        val decoded = ControllerEndpointRecord.decode(legacy) as ControllerEndpoint.Serial
        assertFalse(decoded.configuration.dtr)
        assertEquals(PostConnectActions(), decoded.postConnect)
        for (firmware in PostConnectActions.FirmwareInfo.entries) for (flag in listOf(false, true)) {
            val actions = PostConnectActions(flag, firmware, flag, !flag)
            for (endpoint in listOf(ControllerEndpoint.Tcp("localhost", 1234, actions), decoded.copy(postConnect = actions))) {
                assertEquals(endpoint, ControllerEndpointRecord.decode(ControllerEndpointRecord.encode(endpoint)))
                assertEquals(endpoint, ControllerEndpointDraft.from(endpoint).endpoint())
            }
        }
        assertNull(ControllerEndpointRecord.decode(legacy + "\npost.reset=maybe"))
        assertNull(ControllerEndpointRecord.decode(legacy + "\npost.firmware=UNKNOWN"))
    }

    @Test fun `complete saved draft retains both transports and invalid v2 falls back`() {
        val draft = ControllerEndpointDraft(
            transport = ControllerEndpointDraft.Transport.TCP, host = "localhost", tcpPort = "1234",
            serialPort = "COM42", baudRate = "230400", dtr = false,
            postConnect = PostConnectActions(parameters = false),
        )
        val record = ControllerEndpointRecord.encode(draft.endpoint(), draft)
        assertEquals(draft, ControllerEndpointRecord.decodeDraft(record))
        assertNull(ControllerEndpointRecord.decodeDraft(record.replace("draft.dtr=false", "draft.dtr=invalid")))
        assertNull(ControllerEndpointRecord.decode("version=2\ntransport=TCP\nhost=localhost\nport=1234"))
    }

    @Test fun `draft validates only selected transport and accepts manual Windows and Linux devices`() {
        for (device in listOf("COM3", "/dev/ttyACM0", "/dev/ttyUSB0", "/dev/serial/by-id/usb-test")) {
            val endpoint = ControllerEndpointDraft(serialPort = device).endpoint()
            assertEquals(endpoint, ControllerEndpointDraft.from(endpoint).endpoint())
        }
        for (host in listOf("localhost", "controller.local", "192.168.1.20", "::1", "[2001:db8::1]")) {
            val endpoint = ControllerEndpointDraft(ControllerEndpointDraft.Transport.TCP, host, "1234").endpoint()
            assertEquals(endpoint, ControllerEndpointDraft.from(endpoint).endpoint())
        }
        for (host in listOf("", "   ", "host name", "host\n", "tcp://host", "host/path", "host?query", "host:1234")) {
            assertFailsWith<IllegalArgumentException>(host) { ControllerEndpoint.Tcp(host, 1234) }
        }
        for (port in listOf("", "-1", "0", "65536", "abc", "1.5", "9999999999999")) {
            assertFailsWith<IllegalArgumentException>(port) {
                ControllerEndpointDraft(ControllerEndpointDraft.Transport.TCP, "localhost", port).endpoint()
            }
        }
        for (draft in listOf(
            ControllerEndpointDraft(), ControllerEndpointDraft(serialPort = "COM\n3"),
            ControllerEndpointDraft(serialPort = "COM3", baudRate = "0"),
            ControllerEndpointDraft(serialPort = "COM3", baudRate = "fast"),
            ControllerEndpointDraft(serialPort = "COM3", dataBits = "4"),
            ControllerEndpointDraft(serialPort = "COM3", dataBits = "9"),
        )) assertFailsWith<IllegalArgumentException> { draft.endpoint() }
    }
}
