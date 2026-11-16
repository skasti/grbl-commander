package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import kotlin.test.*

class SoftKeyPagesTest {
    @Test fun `bottom pages wrap through the normal activation path and preserve common reactive keys`() {
        val allowed = mutableStateOf(false)
        var first = 0
        var second = 0
        val common = SoftKeyDefinition("Connection", action = {}, enabled = { allowed.value })
        val pages = SoftKeyPages(
            pages = listOf(
                mapOf(BottomSoftKeySlot.F1 to SoftKeyDefinition("First", action = { first++ })),
                mapOf(BottomSoftKeySlot.F2 to SoftKeyDefinition("Second", action = { second++ })),
            ),
            nextSlot = BottomSoftKeySlot.F8,
            commonKeys = mapOf(BottomSoftKeySlot.F5 to common),
        )
        val screen = object : Screen<Unit>(Unit) {
            override val bottomKeys get() = pages.keys
            @Composable override fun Content() {}
        }
        val stack = ScreenStack(screen)
        assertTrue(activateSoftKey({ stack }, BottomSoftKeySlot.F1))
        assertFalse(stack.softKeyDefinition(BottomSoftKeySlot.F5)!!.enabled())
        assertTrue(activateSoftKey({ stack }, BottomSoftKeySlot.F8))
        assertNull(stack.softKeyDefinition(BottomSoftKeySlot.F1))
        assertTrue(activateSoftKey({ stack }, BottomSoftKeySlot.F2))
        allowed.value = true
        assertSame(common, stack.softKeyDefinition(BottomSoftKeySlot.F5))
        assertTrue(stack.softKeyDefinition(BottomSoftKeySlot.F5)!!.enabled())
        assertFalse(stack.softKeyDefinition(BottomSoftKeySlot.F8)!!.repeatable)
        assertTrue(activateSoftKey({ stack }, BottomSoftKeySlot.F8))
        assertEquals(0, pages.pageIndex)
        assertEquals(1, first)
        assertEquals(1, second)
    }

    @Test fun `right bank uses R7 independently and a single page has no decorative next action`() {
        val pages = SoftKeyPages(
            listOf(mapOf(RightSoftKeySlot.R1 to SoftKeyDefinition("One")), mapOf(RightSoftKeySlot.R2 to SoftKeyDefinition("Two"))),
            RightSoftKeySlot.R7,
        )
        (pages.keys.getValue(RightSoftKeySlot.R7) as SoftKeyDefinition).action!!.invoke()
        assertTrue(RightSoftKeySlot.R2 in pages.keys)
        pages.next()
        assertTrue(RightSoftKeySlot.R1 in pages.keys)
        val single = SoftKeyPages(listOf(mapOf(RightSoftKeySlot.R1 to SoftKeyDefinition("Only"))), RightSoftKeySlot.R7)
        assertNull(single.keys[RightSoftKeySlot.R7])
        single.next()
        assertEquals(0, single.pageIndex)
    }

    @Test fun `paging and common positions cannot be overridden by page content`() {
        assertFailsWith<IllegalArgumentException> { SoftKeyPages(emptyList(), BottomSoftKeySlot.F8) }
        assertFailsWith<IllegalArgumentException> {
            SoftKeyPages(listOf(mapOf(BottomSoftKeySlot.F8 to SoftKeyDefinition("Conflict"))), BottomSoftKeySlot.F8)
        }
        assertFailsWith<IllegalArgumentException> {
            SoftKeyPages(listOf(mapOf(BottomSoftKeySlot.F5 to SoftKeyDefinition("Conflict"))), BottomSoftKeySlot.F8,
                mapOf(BottomSoftKeySlot.F5 to SoftKeyDefinition("Common")))
        }
    }
}
