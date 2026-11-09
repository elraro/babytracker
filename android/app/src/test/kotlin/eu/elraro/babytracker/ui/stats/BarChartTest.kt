package eu.elraro.babytracker.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class BarChartTest {
    @Test
    fun `a touch selects the bar whose slot contains it`() {
        // Seven bars across 700 px: each slot is 100 px wide.
        assertEquals(0, barIndexAt(0f, 700f, 7))
        assertEquals(0, barIndexAt(99.9f, 700f, 7))
        assertEquals(1, barIndexAt(100f, 700f, 7))
        assertEquals(3, barIndexAt(350f, 700f, 7))
        assertEquals(6, barIndexAt(699f, 700f, 7))
    }

    @Test
    fun `a finger dragged past either end keeps the outermost bar`() {
        assertEquals(0, barIndexAt(-40f, 700f, 7))
        assertEquals(6, barIndexAt(700f, 700f, 7))
        assertEquals(6, barIndexAt(1200f, 700f, 7))
    }

    @Test
    fun `a chart not yet measured selects the first bar`() {
        assertEquals(0, barIndexAt(50f, 0f, 7))
    }
}
