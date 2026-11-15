package no.grblcommander.machine.session

enum class RealtimeCommand(internal val byte: Byte) {
    STATUS_QUERY('?'.code.toByte()),
    /** grblHAL extension: requests optional alarm codes and all status fields. */
    STATUS_QUERY_ALL(0x87.toByte()),
    FEED_HOLD('!'.code.toByte()),
    CYCLE_START('~'.code.toByte()),
    /** Explicit reset and bounded re-handshake through MachineSession.reset(). */
    SOFT_RESET(0x18),
}
