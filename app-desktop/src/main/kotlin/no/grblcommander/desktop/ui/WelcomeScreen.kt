package no.grblcommander.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.EditorScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * Welcome artwork and entry point for the in-memory navigation example.
 *
 * This screen never claims controller connectivity or implies machine operation.
 */
class WelcomeScreen : Screen<Unit>(Unit) {
    @Composable
    override fun Content() {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(BrandAssets.SPLASH),
                contentDescription = "GRBL Commander splash screen",
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentScale = ContentScale.Fit,
            )
            Button(
                onClick = { EditorScreen().open(this@WelcomeScreen) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) { Text("Open navigation example") }
        }
    }
}

