package no.grblcommander.machine.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SerialPortDiscoveryTest {
    @Test fun `refresh enumerates anew and preserves native paths and descriptive labels`() = runBlocking {
        var calls = 0
        val discovery = SerialPortDiscovery {
            calls++
            if (calls == 1) listOf(
                DiscoveredSerialPort("COM7", "USB controller"), DiscoveredSerialPort("COM3", ""),
                DiscoveredSerialPort("COM7", "duplicate"),
            ) else listOf(DiscoveredSerialPort("/dev/ttyACM0", "USB Serial"))
        }
        val first = discovery.discover().getOrThrow()
        assertEquals(listOf("COM3", "COM7"), first.map { it.device })
        assertEquals("COM3", first[0].label)
        assertEquals("COM7 — USB controller", first[1].label)
        assertEquals("/dev/ttyACM0", discovery.discover().getOrThrow().single().device)
        assertEquals(2, calls)
    }

    @Test fun `empty discovery is successful and failures remain distinguishable`() = runBlocking {
        assertTrue(SerialPortDiscovery { emptyList() }.discover().getOrThrow().isEmpty())
        for (error in listOf(SecurityException("Permission denied"), UnsatisfiedLinkError("Native library unavailable"))) {
            assertSame(error, SerialPortDiscovery { throw error }.discover().exceptionOrNull())
        }
    }

    @Test fun `discovery cancellation propagates`() = runBlocking {
        assertFailsWith<CancellationException> {
            SerialPortDiscovery { throw CancellationException("Cancelled refresh") }.discover()
        }
    }
}
