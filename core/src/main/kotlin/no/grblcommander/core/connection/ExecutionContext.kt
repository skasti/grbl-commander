package no.grblcommander.core.connection

/** Identity of one occurrence of application-owned controller work. */
open class ExecutionContext internal constructor(
    val sessionId: Long,
    val description: String,
    internal val permitsHeldResume: Boolean = false,
    internal val permitsIdleStart: Boolean = false,
) {
    internal class Manual(sessionId: Long) :
        ExecutionContext(sessionId, "manual command acknowledgment", true, true)

    internal class PostConnect(sessionId: Long) : ExecutionContext(sessionId, "post-connect actions")
}
