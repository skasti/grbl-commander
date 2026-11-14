package no.grblcommander.machine.session

enum class RealtimeCommand(internal val byte: Byte) {
    STATUS_QUERY('?'.code.toByte()),
    FEED_HOLD('!'.code.toByte()),
    CYCLE_START('~'.code.toByte()),
    /** Reset invalidates correlation and terminates the session; it never reconnects. */
    SOFT_RESET(0x18),
}
