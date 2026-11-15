package no.grblcommander.machine.session

/** Lines observed while awaiting the acknowledgment can include unsolicited messages. */
data class CommandAcknowledgment(val errorCode: Int?, val responseLines: List<String>) {
    val accepted: Boolean get() = errorCode == null
}
