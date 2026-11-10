package no.grblcommander.desktop.ui.navigation

/** The navigation owner for an opened screen or an independent screen stack. */
interface NavigationParent {
    val screenStack: ScreenStack
}
