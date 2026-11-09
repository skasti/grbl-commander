package no.grblcommander.desktop.ui.layout

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import no.grblcommander.desktop.ui.BrandAssets
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object ShellColors {
    val frame = Color(0xFF0D1420)
    val header = Color(0xFF1A293C)
    val workspace = Color(0xFF0E1826)
    val bottom = Color(0xFF132032)
    val right = Color(0xFF142136)
    val key = Color(0xFF233449)
    val outline = Color(0xFF3F5368)
    val text = Color(0xFFE7EFF8)
    val muted = Color(0xFF94A7BC)
    val accent = Color(0xFF56BBEF)
}

@Composable
fun AppShell(
    state: ShellState,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(ShellColors.frame)) {
        ShellHeader(state, Modifier.fillMaxWidth().height(78.dp))

        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().background(ShellColors.workspace),
                    contentAlignment = Alignment.Center,
                ) {
                    content()
                }
                BottomSoftkeys(state.bottomKeys, Modifier.fillMaxWidth().height(94.dp))
            }
            RightSoftkeys(state.rightKeys, Modifier.width(142.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun ShellHeader(state: ShellState, modifier: Modifier = Modifier) {
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
                Text("COMMANDER", color = ShellColors.accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.4.sp)
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
        Box(
            Modifier
                .border(1.dp, ShellColors.outline, RoundedCornerShape(6.dp))
                .background(ShellColors.workspace, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(state.connectionStatus, color = ShellColors.muted, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun BottomSoftkeys(keys: List<SoftkeySlot>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.background(ShellColors.bottom).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        keys.forEach { key ->
            SoftkeyTile(key, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun RightSoftkeys(keys: List<SoftkeySlot>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(ShellColors.right).padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        keys.forEach { key ->
            SoftkeyTile(key, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun SoftkeyTile(slot: SoftkeySlot, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .border(1.dp, ShellColors.outline, RoundedCornerShape(6.dp))
            .background(ShellColors.key, RoundedCornerShape(6.dp))
            .padding(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(slot.shortcut, color = ShellColors.muted, fontSize = 10.sp)
        Spacer(Modifier.height(7.dp))
        Text(
            slot.label,
            color = ShellColors.muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
