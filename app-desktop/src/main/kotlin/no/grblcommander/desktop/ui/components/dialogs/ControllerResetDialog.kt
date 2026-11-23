package no.grblcommander.desktop.ui.components.dialogs

import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot

internal fun controllerResetDialog(): ModalDialog<Boolean> =
    ModalDialog(
        "Reset controller?",
        "Soft reset interrupts active work and invalidates pending commands. Machine position and execution may be uncertain. Nothing is replayed, unlocked or resumed automatically.",
        false,
        mapOf(BottomSoftKeySlot.F1 to ModalDialog.Choice("Reset", true), BottomSoftKeySlot.F8 to ModalDialog.Choice("Cancel", false)),
    )
