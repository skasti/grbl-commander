package no.grblcommander.desktop.ui.layout

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class GridUnitTest {
    @Test fun `the cap fits at the exact width and height thresholds`() = with(Density(1f)) {
        assertEquals(72.dp, workspaceGridUnit(1413, 979))
        assertEquals(71.dp, workspaceGridUnit(1412, 979))
        assertEquals(71.dp, workspaceGridUnit(1413, 978))
        assertEquals(44.dp, workspaceGridUnit(882, 548))
    }

    @Test fun `fractional density rounds each gap and fixed length separately`() = with(Density(1.1f)) {
        assertEquals(79, workspaceGridUnit(1548, 1076).roundToPx())
        assertEquals(78, workspaceGridUnit(1547, 1076).roundToPx())
        assertEquals(78, workspaceGridUnit(1548, 1075).roundToPx())
        assertEquals(49, workspaceGridUnit(882, 548).roundToPx())
    }
}
