package no.grblcommander.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.ScreenStack
import no.grblcommander.desktop.ui.navigation.EditorScreen
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
class WelcomeScreen : Screen {
    @Composable
    override fun Content(stack: ScreenStack) {
        Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(BrandAssets.SPLASH),
            contentDescription = "GRBL Commander splash screen",
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentScale = ContentScale.Fit,
        )
            Button(
                onClick = { stack.push(EditorScreen()) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) { Text("Open navigation example") }
        }
    }
}

