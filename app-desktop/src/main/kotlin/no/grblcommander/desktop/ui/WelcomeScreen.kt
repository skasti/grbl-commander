package no.grblcommander.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * A static placeholder until mode-specific workspaces are implemented.
 *
 * This screen never claims controller connectivity or implies machine operation.
 */
@Composable
fun WelcomeScreen() {
    Image(
        painter = painterResource(BrandAssets.SPLASH),
        contentDescription = "GRBL Commander splash screen",
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentScale = ContentScale.Fit,
    )
}
