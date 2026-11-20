package no.grblcommander.desktop.ui.components.controls

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** One retained, independently paged cluster. Construct another state to replace its layout. */
class KeyClusterState(val layout: KeypadLayout) {
    var pageIndex by mutableIntStateOf(0)
        private set
    val keys get() = layout.pages[pageIndex]
    fun next() { pageIndex = (pageIndex + 1) % layout.pages.size }
}
