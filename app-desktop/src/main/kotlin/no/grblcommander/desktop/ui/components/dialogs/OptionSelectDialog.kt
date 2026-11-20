package no.grblcommander.desktop.ui.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.SoftKeyDefinition

/** Typed workspace selector. Loading runs for the lifetime of its screen composition. */
class OptionSelectDialog<R>(
    private val title: String,
    cancellationResult: R,
    private val loadOptions: suspend () -> Options<R>,
) : Screen<R>(cancellationResult) {
    data class Option<out R>(val label: String, val result: R, val icon: String? = null)

    /** The caller supplies empty or failure content appropriate to its options. */
    data class Options<out R>(
        val items: List<Option<R>>,
        val emptyContent: @Composable () -> Unit = {},
    )

    private var options by mutableStateOf<Options<R>?>(null)
    private var page by mutableStateOf(0)
    private val items get() = options?.items.orEmpty()
    private val pageCount get() = (items.size + ROW_NUMBERS.size - 1) / ROW_NUMBERS.size
    private val firstIndex get() = page * ROW_NUMBERS.size

    private fun choose(row: Int) {
        items.getOrNull(firstIndex + row)?.let { finish(it.result) }
    }

    private val selectionKeys = buildMap {
        ROW_NUMBERS.indices.forEach { row ->
            put(
                BottomSoftKeySlot.entries[row],
                SoftKeyDefinition(
                    "Select ${row + 1}",
                    action = { choose(row) },
                    enabled = { firstIndex + row < items.size },
                    repeatable = false,
                ),
            )
        }
        put(BottomSoftKeySlot.F8, SoftKeyDefinition("Cancel", action = { finish(cancellationResult) }, repeatable = false))
    }
    private val pagedKeys = selectionKeys + (BottomSoftKeySlot.F7 to SoftKeyDefinition(
        "Next page",
        action = { if (pageCount > 1) page = (page + 1) % pageCount },
        repeatable = false,
    ))

    override val bottomKeys get() = if (pageCount > 1) pagedKeys else selectionKeys

    @Composable
    override fun Content() {
        LaunchedEffect(Unit) {
            page = 0
            options = null
            options = loadOptions()
        }

        BaseDialog(title) {
            val loaded = options
            if (loaded == null) {
                Text("Loading…")
            } else if (loaded.items.isEmpty()) {
                loaded.emptyContent()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val endIndex = minOf(firstIndex + ROW_NUMBERS.size, loaded.items.size)
                    loaded.items.subList(firstIndex, endIndex).forEachIndexed { row, option ->
                        OutlinedButton(
                            onClick = { choose(row) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        ) {
                            val icon = option.icon?.let { "$it  " }.orEmpty()
                            Text("${ROW_NUMBERS[row]}  $icon${option.label}")
                        }
                    }
                    if (pageCount > 1) Text("Page ${page + 1}/$pageCount")
                }
            }
        }
    }

    private companion object {
        val ROW_NUMBERS = listOf("1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣")
    }
}
