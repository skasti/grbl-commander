package no.grblcommander.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import java.time.Instant
import kotlinx.coroutines.delay

/** Freshness must update even when no controller reports arrive. */
@Composable
internal fun rememberCurrentTime(): Instant {
    val now by produceState(Instant.now()) {
        while (true) { delay(1_000); value = Instant.now() }
    }
    return now
}
