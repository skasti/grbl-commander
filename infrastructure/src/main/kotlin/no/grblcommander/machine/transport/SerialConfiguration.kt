package no.grblcommander.machine.transport

/** Explicit serial settings; no software/hardware flow control or reset by default. */
data class SerialConfiguration(
    val port: String,
    val baudRate: Int = 115_200,
    val dataBits: Int = 8,
    val stopBits: StopBits = StopBits.ONE,
    val parity: Parity = Parity.NONE,
    val flowControl: FlowControl = FlowControl.NONE,
    val dtr: Boolean = false,
    val rts: Boolean = false,
) {
    enum class StopBits { ONE, TWO }
    enum class Parity { NONE, ODD, EVEN }
    enum class FlowControl { NONE, RTS_CTS }

    init {
        require(port.isNotBlank())
        require(baudRate > 0)
        require(dataBits in 5..8)
    }
}
