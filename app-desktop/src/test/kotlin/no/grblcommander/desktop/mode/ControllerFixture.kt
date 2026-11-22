package no.grblcommander.desktop.mode

import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.*
import no.grblcommander.configuration.ControllerEndpointPreferences
import no.grblcommander.core.connection.*
import no.grblcommander.machine.session.GrblMachineSession

internal open class ControllerFixture : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val transport = ConnectionTestTransport()
    val owner = ControllerConnection(scope, { _, child -> GrblMachineSession(transport, child) })
    private val node = Preferences.userRoot().node("no/grblcommander/tests/${UUID.randomUUID()}")
    val settings = ControllerSettings(owner, ControllerEndpointPreferences(node))
    fun connect() = runBlocking { owner.connect(ControllerEndpoint.Tcp("localhost", 4321, no.grblcommander.core.connection.noPostConnectActions())) }
    override fun close() {
        owner.close(); runBlocking { owner.awaitClosed() }; scope.cancel()
        node.removeNode(); Preferences.userRoot().flush()
    }
}
