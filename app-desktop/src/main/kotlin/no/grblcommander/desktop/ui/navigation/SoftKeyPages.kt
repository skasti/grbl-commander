package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Screen-owned pages for one bank; common keys keep their positions on every page. */
class SoftKeyPages<S : SoftKeySlot>(
    pages: List<Map<S, SoftKeyOverride>>,
    nextSlot: S,
    commonKeys: Map<S, SoftKeyOverride> = emptyMap(),
) {
    init {
        require(pages.isNotEmpty()) { "At least one softkey page is required" }
        require(nextSlot !in commonKeys && pages.all { nextSlot !in it }) { "The paging slot is reserved" }
        require(pages.all { page -> page.keys.none { it in commonKeys } }) { "Pages cannot replace common keys" }
        require((pages.flatMap { it.keys } + commonKeys.keys).all { it::class == nextSlot::class }) {
            "Pages must belong to a single softkey bank"
        }
    }

    var pageIndex by mutableStateOf(0)
        private set
    val pageCount = pages.size
    private val bindings = pages.mapIndexed { index, page ->
        commonKeys + page + if (pageCount > 1) mapOf(
            nextSlot to SoftKeyDefinition("Next page\n${index + 1}/$pageCount", action = ::next, repeatable = false),
        ) else emptyMap()
    }

    /** Read from Screen.bottomKeys/rightKeys so existing resolution observes page changes. */
    val keys: Map<S, SoftKeyOverride> get() = bindings[pageIndex]

    fun next() { pageIndex = (pageIndex + 1) % pageCount }
}
