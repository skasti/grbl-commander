package no.grblcommander.machine.session

/** Lines observed while awaiting the acknowledgment can include unsolicited messages. */
data class CommandAcknowledgment(
    val errorCode: Int?,
    val responseLines: List<String>,
    /** Last status report received before the correlated reply. */
    val reportSequence: Long? = null,
) {
    val accepted: Boolean get() = errorCode == null
}
