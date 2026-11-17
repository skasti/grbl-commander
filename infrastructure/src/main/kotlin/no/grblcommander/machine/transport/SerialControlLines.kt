package no.grblcommander.machine.transport

/** Seed native open state, then assert requested levels on the opened handle. No pulse or reset. */
internal fun openWithControlLines(
    configuration: SerialConfiguration,
    open: () -> Boolean,
    dtr: (Boolean) -> Boolean,
    rts: (Boolean) -> Boolean,
): Boolean {
    // jSerialComm retains these levels before open; native application is checked after open.
    dtr(configuration.dtr)
    rts(configuration.rts)
    if (!open()) return false
    val dtrApplied = dtr(configuration.dtr)
    val rtsApplied = rts(configuration.rts)
    return dtrApplied && rtsApplied
}
