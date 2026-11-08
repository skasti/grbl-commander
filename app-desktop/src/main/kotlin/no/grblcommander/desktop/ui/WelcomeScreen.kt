package no.grblcommander.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.grblcommander.desktop.ui.layout.ShellColors

@Composable
fun WelcomeScreen() {
    Column(
        modifier = Modifier
            .padding(24.dp)
            .border(1.dp, ShellColors.outline, RoundedCornerShape(12.dp))
            .background(ShellColors.header, RoundedCornerShape(12.dp))
            .padding(horizontal = 48.dp, vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "GRBL COMMANDER",
            color = ShellColors.text,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            "Operatørgrensesnitt",
            color = ShellColors.accent,
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
        )
        Text(
            "Dette er en foreløpig startskjerm. Ingen maskinfunksjoner er aktive.",
            color = ShellColors.muted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}
