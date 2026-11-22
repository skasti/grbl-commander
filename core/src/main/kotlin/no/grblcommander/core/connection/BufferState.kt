package no.grblcommander.core.connection

/** One-based batch positions. A controller N word is translated before publication. */
data class BufferState(
    val lastSentLineNumber: Int? = null,
    val reply: Reply = Reply.Empty,
    val executingLineNumber: Int? = null,
    /** Write failed after it may have put some or all bytes on the wire. */
    val uncertainWriteLineNumber: Int? = null,
) {
    sealed interface Reply {
        data object Empty : Reply
        data object Pending : Reply
        data object Ok : Reply
        data class Error(val code: Int) : Reply
        data object Unknown : Reply
    }
}
