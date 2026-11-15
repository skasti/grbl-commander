package no.grblcommander.machine.transport

import com.fazecast.jSerialComm.SerialPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Enumerates on the I/O pool without opening ports or changing DTR/RTS. */
class SerialPortDiscovery internal constructor(private val enumerate: () -> List<DiscoveredSerialPort>) {
    constructor() : this({
        SerialPort.getCommPorts().map { port ->
            DiscoveredSerialPort(port.systemPortPath, port.descriptivePortName.orEmpty())
        }
    })

    /** Failure is distinct from an empty list; neither prevents manual device entry. */
    suspend fun discover(): Result<List<DiscoveredSerialPort>> = withContext(Dispatchers.IO) {
        try {
            val ports = enumerate().distinctBy { it.device }.sortedBy { it.device }
            ensureActive()
            Result.success(ports)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        } catch (error: LinkageError) {
            // Optional native discovery can be unavailable even when manual settings remain editable.
            Result.failure(error)
        }
    }
}
