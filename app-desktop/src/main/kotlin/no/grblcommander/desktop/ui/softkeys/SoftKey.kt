package no.grblcommander.desktop.ui.softkeys

import androidx.compose.ui.graphics.vector.ImageVector

/** Physical positions are fixed, even when a slot has no definition. */
sealed interface SoftKeySlot {
    val shortcut: String
}

enum class BottomSoftKeySlot : SoftKeySlot {
    F1, F2, F3, F4, F5, F6, F7, F8;
    override val shortcut: String get() = name
}

enum class RightSoftKeySlot : SoftKeySlot {
    R1, R2, R3, R4, R5, R6, R7;
    override val shortcut: String get() = name
}

sealed interface SoftKeyOverride

/** Stable screen-owned definition. Predicates read observable state at use time. */
data class SoftKeyDefinition(
    val label: String,
    val icon: ImageVector? = null,
    val action: (() -> Unit)? = null,
    val enabled: () -> Boolean = { true },
    val active: () -> Boolean = { false },
) : SoftKeyOverride {
    companion object {
        val Blank = SoftKeyDefinition(label = "", enabled = { false })
    }
}

/** Transform definitions with copy(), retaining fields not explicitly replaced. */
data class SoftKeyModifier(
    val modify: (SoftKeyDefinition) -> SoftKeyDefinition,
) : SoftKeyOverride
