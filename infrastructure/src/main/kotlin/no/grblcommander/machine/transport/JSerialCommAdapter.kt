package no.grblcommander.machine.transport

import com.fazecast.jSerialComm.SerialPort
import com.fazecast.jSerialComm.SerialPortDataListener
import com.fazecast.jSerialComm.SerialPortEvent
import java.util.concurrent.atomic.AtomicBoolean

internal class JSerialCommAdapter(portName: String) : SerialPortAdapter {
    // Endpoint selection/session construction must not require an existing native port.
    private val nativePort = lazy { SerialPort.getCommPort(portName) }
    private val port by nativePort
    private val disconnected = AtomicBoolean()

    override fun open(configuration: SerialConfiguration): Boolean {
        val stop = when (configuration.stopBits) {
            SerialConfiguration.StopBits.ONE -> SerialPort.ONE_STOP_BIT
            SerialConfiguration.StopBits.TWO -> SerialPort.TWO_STOP_BITS
        }
        val parity = when (configuration.parity) {
            SerialConfiguration.Parity.NONE -> SerialPort.NO_PARITY
            SerialConfiguration.Parity.ODD -> SerialPort.ODD_PARITY
            SerialConfiguration.Parity.EVEN -> SerialPort.EVEN_PARITY
        }
        val flow = when (configuration.flowControl) {
            SerialConfiguration.FlowControl.NONE -> SerialPort.FLOW_CONTROL_DISABLED
            SerialConfiguration.FlowControl.RTS_CTS ->
                SerialPort.FLOW_CONTROL_RTS_ENABLED or SerialPort.FLOW_CONTROL_CTS_ENABLED
        }
        if (!port.setComPortParameters(configuration.baudRate, configuration.dataBits, stop, parity) ||
            !port.setFlowControl(flow) ||
            !port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0)) return false
        if (!openWithControlLines(configuration, port::openPort,
                { if (it) port.setDTR() else port.clearDTR() },
                { if (it) port.setRTS() else port.clearRTS() })) return false
        // isOpen alone does not detect USB unplug on every platform.
        return port.addDataListener(object : SerialPortDataListener {
            override fun getListeningEvents() = SerialPort.LISTENING_EVENT_PORT_DISCONNECTED
            override fun serialEvent(event: SerialPortEvent) { disconnected.set(true) }
        })
    }

    override fun read(buffer: ByteArray): Int =
        if (disconnected.get()) -1 else port.readBytes(buffer, buffer.size)

    override fun write(bytes: ByteArray, offset: Int): Int =
        if (disconnected.get()) -1 else port.writeBytes(bytes, bytes.size - offset, offset)

    override fun close() {
        if (!nativePort.isInitialized()) return
        port.removeDataListener()
        check(port.closePort()) { "Unable to close serial port ${port.systemPortName}" }
    }
}

