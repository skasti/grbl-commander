package no.grblcommander.desktop.ui.components.dialogs

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class BaseDialogTest {
    @Test fun `dialog body scrolls within the available workspace`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                BaseDialog("Title", Modifier.size(width = 360.dp, height = 240.dp)) {
                    Text("Start of body")
                    Spacer(Modifier.height(400.dp))
                    Text("End of body")
                }
            }
        }

        onNodeWithText("Title").assertIsDisplayed()
        onNodeWithText("Start of body").assertIsDisplayed()
        val endOfBody = onNodeWithText("End of body")
        endOfBody.assertIsNotDisplayed()
        endOfBody.performScrollTo().assertIsDisplayed()
    }
}
