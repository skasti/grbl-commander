package no.grblcommander.core.connection

/** Identity of one occurrence of application-owned controller work. */
open class ExecutionContext internal constructor(
    val sessionId: Long,
    val description: String,
    internal val permitsHeldResume: Boolean = false,
    internal val permitsIdleStart: Boolean = false,
) {
    class Manual internal constructor(sessionId: Long) :
        ExecutionContext(sessionId, "manual command acknowledgment", true, true)

    class PostConnect internal constructor(sessionId: Long) : ExecutionContext(sessionId, "post-connect actions")
}
