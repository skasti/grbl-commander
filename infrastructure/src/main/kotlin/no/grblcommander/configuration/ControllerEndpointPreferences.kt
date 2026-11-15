package no.grblcommander.configuration

import java.util.prefs.Preferences

/** JVM storage for one opaque controller-settings record; interpretation belongs to core. */
class ControllerEndpointPreferences(preferences: Preferences? = null) {
    private val preferences by lazy { preferences ?: Preferences.userRoot().node("no/grblcommander/controller") }

    fun load(): String? = preferences.get(KEY, null)

    /** One value preserves record consistency; storage/flush failures propagate to the caller. */
    fun save(record: String) {
        require(record.length <= Preferences.MAX_VALUE_LENGTH) { "Controller settings are too long to save" }
        preferences.put(KEY, record)
        preferences.flush()
    }

    private companion object { const val KEY = "endpoint" }
}
