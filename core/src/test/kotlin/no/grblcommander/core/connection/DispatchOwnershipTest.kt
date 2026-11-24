package no.grblcommander.core.connection

import java.io.IOException
import kotlinx.coroutines.*
import no.grblcommander.machine.session.GrblMachineSession
import kotlin.test.*

class DispatchOwnershipTest {
    private val endpoint = ControllerEndpoint.Tcp("localhost", 1234, noPostConnectActions())
    private val context = { id: Long -> ExecutionContext(id, "storage listing") }

    @Test fun severalCommandsAndRestorationShareOneReservation() = runBlocking {
        withTimeout(5000) {
            val transport = ConnectionTestTransport()
            val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(transport, scope) })
            try {
                owner.connect(endpoint)
                val workflow = async {
                    runCatching {
                        owner.executeWorkflow(context) { send ->
                            send("CHANGE")
                            try {
                                check(send("LIST").accepted)
                            } finally {
                                send("RESTORE")
                            }
                        }
                    }
                }
                for (command in listOf("CHANGE", "LIST", "RESTORE")) {
                    assertEquals("$command\n", transport.nextNonStatusWrite())
                    assertSame(owner.state.value.dispatchOwner, owner.state.value.activeExecution)
                    assertFailsWith<IOException> { owner.executeWorkflow(context) { } }
                    assertFailsWith<IOException> { owner.executeLine("MANUAL") }
                    assertFailsWith<IOException> { owner.startOperation(Operation.Source.MDI, "draft", listOf("G0 X1")) }
                    transport.emit(if (command == "LIST") "error:3\n" else "ok\n")
                }
                assertIs<IllegalStateException>(workflow.await().exceptionOrNull())
                assertNull(owner.state.value.dispatchOwner)
            } finally {
                owner.close()
                owner.awaitClosed()
            }
        }
    }

    @Test fun resetWaitsForCancelledWorkflowCleanup() = checkInterruption(replace = false)
    @Test fun replacementWaitsForCancelledWorkflowCleanup() = checkInterruption(replace = true)

    private fun checkInterruption(replace: Boolean) = runBlocking {
        withTimeout(5000) {
            val owner = ControllerConnection(this, { _, scope -> GrblMachineSession(ConnectionTestTransport(), scope) })
            val entered = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            try {
                owner.connect(endpoint)
                val workflow = async {
                    runCatching {
                        owner.executeWorkflow(context) {
                            entered.complete(Unit)
                            try { awaitCancellation() }
                            finally { withContext(NonCancellable) { cleanup.complete(Unit); finish.await() } }
                        }
                    }
                }
                entered.await()
                val transition = async {
                    if (replace) owner.connect(endpoint) else owner.reset()
                }
                cleanup.await()
                assertFalse(transition.isCompleted)
                assertFailsWith<IOException> { owner.executeWorkflow(context) { } }
                finish.complete(Unit)
                transition.await()
                assertIs<CancellationException>(workflow.await().exceptionOrNull())
                assertNull(owner.state.value.dispatchOwner)
                assertEquals("ready", owner.executeWorkflow(context) { "ready" })
            } finally {
                finish.complete(Unit)
                owner.close()
                owner.awaitClosed()
            }
        }
    }
}
