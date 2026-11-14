package no.grblcommander.machine.transport

/** Native boundary only; protocol code depends on MachineTransport instead. */
internal interface SerialPortAdapter {
    fun open(configuration: SerialConfiguration): Boolean
    fun read(buffer: ByteArray): Int
    fun write(bytes: ByteArray, offset: Int): Int
    fun close()
}
