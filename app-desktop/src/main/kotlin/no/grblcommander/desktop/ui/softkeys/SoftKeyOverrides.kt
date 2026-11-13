package no.grblcommander.desktop.ui.softkeys

/**
 * Create a definition map from complete definitions in this override map.
 * Modifiers without an existing definition are ignored.
 */
fun <K : SoftKeySlot> Map<K, SoftKeyOverride>.definitions(): MutableMap<K, SoftKeyDefinition> {
    val result = mutableMapOf<K, SoftKeyDefinition>()
    for ((slot, override) in this) {
        val definition = when (override) {
            is SoftKeyDefinition -> override
            is SoftKeyModifier -> result[slot]?.let(override.modify)
        }
        if (definition != null) {
            result[slot] = definition
        }
    }
    return result
}

/** Apply one screen's overrides to this effective definition map in place. */
fun <K : SoftKeySlot> MutableMap<K, SoftKeyDefinition>.applyOverrides(
    keyOverrides: Map<K, SoftKeyOverride>,
) {
    for ((slot, override) in keyOverrides) {
        val current = this[slot]
        val newDefinition = when (override) {
            is SoftKeyDefinition -> override
            is SoftKeyModifier -> current?.let(override.modify)
        }
        if (newDefinition != null) {
            this[slot] = newDefinition
        } else {
            this.remove(slot)
        }
    }
}

/** Apply screen override maps to this effective definition map in order. */
fun <K : SoftKeySlot> MutableMap<K, SoftKeyDefinition>.applyAllOverrides(
    overrides: List<Map<K, SoftKeyOverride>>,
): Map<K, SoftKeyDefinition> {
    for (overrideMap in overrides) {
        applyOverrides(overrideMap)
    }
    return this
}
