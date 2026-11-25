package no.grblcommander.core.controller

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandBufferTest {
    @Test
    fun rejectionDiscardsQueuedWorkAndWakesProducer() = runTest {
        val buffer = CommandBuffer(1)
        val reject = CompletableDeferred<Unit>()
        val queueFull = CompletableDeferred<Unit>()
        val accepted = mutableListOf<Int>()
        val sent = mutableListOf<Int>()
        val job = launch {
            buffer.execute(produce = {
                for (line in 1..4) {
                    buffer.enqueue(SourceCommand(line, "G1 X$line"))
                    accepted += line
                    if (line == 2) queueFull.complete(Unit)
                }
            }) {
                sent += it.position
                reject.await()
                false
            }
        }
        queueFull.await()
        assertEquals(listOf(1, 2), accepted)
        reject.complete(Unit)
        job.join()
        assertEquals(listOf(1), sent)
        assertEquals(listOf(1, 2), accepted)
    }
}
