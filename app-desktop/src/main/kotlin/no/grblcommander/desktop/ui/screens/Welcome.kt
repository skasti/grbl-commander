package no.grblcommander.desktop.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import no.grblcommander.desktop.generated.resources.Res
import no.grblcommander.desktop.generated.resources.grbl_commander_splash
import no.grblcommander.desktop.ui.navigation.BottomSoftKeySlot
import no.grblcommander.desktop.ui.navigation.Screen
import no.grblcommander.desktop.ui.navigation.SoftKeyDefinition
import org.jetbrains.compose.resources.painterResource

/**
 * Welcome artwork and entry point for the in-memory navigation example.
 *
 * This screen never claims controller connectivity or implies machine operation.
 */
class Welcome : Screen<Unit>(Unit) {
    private fun openEditor() { Editor().open(this) }

    override val bottomKeys = mapOf(
        BottomSoftKeySlot.F1 to
            SoftKeyDefinition("Open editor", action = ::openEditor),
    )

    @Composable
    override fun Content() {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(Res.drawable.grbl_commander_splash),
                contentDescription = "GRBL Commander splash screen",
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentScale = ContentScale.Fit,
            )
            Button(
                onClick = ::openEditor,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) { Text("Open navigation example") }
        }
    }
}
