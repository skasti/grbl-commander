package no.grblcommander.core.controller

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal data class SourceCommand(val position: Int, val text: String)

/** One producer and one dispatch worker per operation. Capacity excludes the worker's current command. */
internal class CommandBuffer(capacity: Int = 8) {
    init {
        require(capacity > 0 && capacity < Channel.UNLIMITED)
    }

    private val queue = Channel<SourceCommand>(capacity)

    suspend fun enqueue(command: SourceCommand) = queue.send(command)

    /** The dispatch callback settles the command and returns false on a terminal outcome. */
    suspend fun execute(
        produce: suspend () -> Unit,
        dispatch: suspend (SourceCommand) -> Boolean,
    ) = coroutineScope {
        val producer = launch {
            try {
                produce()
            } finally {
                queue.close()
            }
        }
        try {
            for (command in queue) {
                if (!dispatch(command)) break
            }
        } finally {
            producer.cancel()
            queue.cancel()
        }
    }
}
