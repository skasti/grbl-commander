package no.grblcommander.core.connection

/** Shared serial/TCP preferences. Reset always requires consent for this connection attempt. */
data class PostConnectActions(
    val resetController: Boolean = false,
    val firmwareInfo: FirmwareInfo = FirmwareInfo.BASIC,
    val parameters: Boolean = true,
    val parserState: Boolean = true,
) {
    enum class FirmwareInfo(val command: String?) { OFF(null), BASIC("\$I"), EXTENDED("\$I+") }
    fun queries(): List<String> = buildList {
        firmwareInfo.command?.let(::add)
        if (parameters) add("$#")
        if (parserState) add("\$G")
    }
}
