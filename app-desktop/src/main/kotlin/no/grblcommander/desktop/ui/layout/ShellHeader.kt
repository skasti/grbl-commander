package no.grblcommander.desktop.ui.layout

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.grblcommander.desktop.ui.BrandAssets

@Composable
internal fun ShellHeader(
    state: ShellState,
    modifier: Modifier = Modifier.Companion,
    headerControls: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.background(ShellColors.header).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Image(
                painter = painterResource(BrandAssets.ICON),
                contentDescription = "GRBL Commander logo",
                modifier = Modifier.width(72.dp).height(48.dp),
                contentScale = ContentScale.Fit,
            )
            Column {
                Text("GRBL", color = ShellColors.text, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text(
                    "COMMANDER",
                    color = ShellColors.accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.4.sp
                )
            }
        }
        Box(Modifier.width(1.dp).height(38.dp).background(ShellColors.outline))
        Column(Modifier.weight(1f)) {
            Text(state.title, color = ShellColors.text, fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
            Text(
                state.context,
                color = ShellColors.muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        headerControls()
        Box(
            Modifier
                .border(1.dp, ShellColors.outline, RoundedCornerShape(6.dp))
                .background(ShellColors.workspace, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(state.connectionStatus, color = ShellColors.muted, fontSize = 11.sp, maxLines = 1)
        }
    }
}