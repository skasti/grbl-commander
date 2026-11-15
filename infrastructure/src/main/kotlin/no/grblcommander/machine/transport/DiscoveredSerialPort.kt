package no.grblcommander.machine.transport

/** Discovery is advisory: a manually entered device need not appear in this list. */
data class DiscoveredSerialPort(val device: String, val description: String) {
    val label: String get() = if (description.isBlank() || description == device) device else "$device — $description"
}
