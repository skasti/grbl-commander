package no.grblcommander.desktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import no.grblcommander.desktop.ui.navigation.*

/** Six touch rows per page. Discovery is refreshed for each new selector instance. */
internal class ConnectionChoice(
    private val title: String,
    private val load: suspend () -> List<Pair<String, String>>,
) : Screen<String?>(null) {
    private var choices by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var page by mutableStateOf(0)
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private val rows get() = choices.drop(page * 6).take(6)
    private fun choose(index: Int) { rows.getOrNull(index)?.let { finish(it.first) } }
    override val bottomKeys get() = buildMap {
        repeat(6) { row ->
            put(BottomSoftKeySlot.entries[row], SoftKeyDefinition("Select ${row + 1}", action = { choose(row) },
                enabled = { rows.size > row }, repeatable = false))
        }
        if (choices.size > 6) put(BottomSoftKeySlot.F7, SoftKeyDefinition("Next page", action = {
            page = (page + 1) % ((choices.size + 5) / 6)
        }, repeatable = false))
        put(BottomSoftKeySlot.F8, SoftKeyDefinition("Cancel", action = { finish(null) }, repeatable = false))
    }
    @Composable override fun Content() {
        LaunchedEffect(Unit) {
            try { choices = load(); page = 0 }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "Discovery unavailable: ${failure.message}. Use manual entry." }
            catch (failure: LinkageError) { error = "Discovery unavailable: ${failure.message}. Use manual entry." }
            finally { loading = false }
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (loading) Text("Loading…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!loading && choices.isEmpty() && error == null) Text("No serial ports found. Use manual entry.")
                rows.forEachIndexed { index, choice ->
                    OutlinedButton(onClick = { choose(index) }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                        Text("${listOf("1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣")[index]}  ${choice.second}")
                    }
                }
                if (choices.size > 6) Text("Page ${page + 1}/${(choices.size + 5) / 6}")
            }
        }
    }
}
