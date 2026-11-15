package no.grblcommander.configuration

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ControllerEndpointPreferencesTest {
    private fun preferences(test: (Preferences) -> Unit) {
        val node = Preferences.userRoot().node("no/grblcommander/tests/${UUID.randomUUID()}")
        try { test(node) } finally { node.removeNode(); Preferences.userRoot().flush() }
    }

    @Test fun `storage preserves exact record and endpoint key across fresh adapter instances`() = preferences { node ->
        assertNull(ControllerEndpointPreferences(node).load())
        for (record in listOf("version=1\ntransport=TCP\nhost=localhost\nport=4321", "unsupported or invalid data")) {
            ControllerEndpointPreferences(node).save(record)
            node.sync()
            assertEquals(record, node.get("endpoint", null))
            assertEquals(record, ControllerEndpointPreferences(Preferences.userRoot().node(node.absolutePath())).load())
        }
    }

    @Test fun `oversized storage record is rejected without replacing previous data`() = preferences { node ->
        val storage = ControllerEndpointPreferences(node)
        storage.save("retained")
        assertFailsWith<IllegalArgumentException> { storage.save("x".repeat(Preferences.MAX_VALUE_LENGTH + 1)) }
        assertEquals("retained", storage.load())
    }
}
