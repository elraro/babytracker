package eu.elraro.babytracker.ui.stats

import eu.elraro.babytracker.domain.ChartDay
import eu.elraro.babytracker.domain.ChartSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SleepChartImageTest {
    private val text =
        ChartImageText(
            title = "Sleep day by day",
            legendSleep = "Sleep",
            legendAwake = "Awake between sleeps",
            legendBottle = "Bottle",
            hours = listOf("0", "6", "12", "18"),
            note = "Times are shown in this phone's time zone.",
            month = { "$it months old" },
            date = LocalDate::toString,
            total = { "${it / 3600} h" },
        )
    private val sleepColor = 0xFF2A78D6.toInt()
    private val colors =
        ChartImageColors(
            background = 0xFFFFFFFF.toInt(),
            text = 0xFF000000.toInt(),
            muted = 0xFF555555.toInt(),
            heading = 0xFF2A78D6.toInt(),
            sleep = sleepColor,
            awake = 0xFFEDA100.toInt(),
            bottle = 0xFF000000.toInt(),
            track = 0xFFEEEEEE.toInt(),
            grid = 0xFFCCCCCC.toInt(),
        )

    /** [count] days, newest first, each asleep all day and changing month every 30 days. */
    private fun days(count: Int): List<ChartDay> {
        val today = LocalDate.of(2026, 11, 11)
        return (0 until count).map {
            ChartDay(
                date = today.minusDays(it.toLong()),
                ageMonths = (count - it) / 30,
                sleep = listOf(ChartSpan(0, 86_400)),
                awake = emptyList(),
                bottles = listOf(36_000),
                bottleMl = 120,
                sleepS = 86_400,
                naps = 0,
            )
        }
    }

    @Test
    fun `the image grows with the days and stops at the limit`() {
        val week = SleepChartImage.render(days(7), text, colors)
        val month = SleepChartImage.render(days(30), text, colors)
        assertEquals(1080, week.width)
        assertTrue(month.height > week.height)

        val limit = SleepChartImage.MAX_DAYS
        val full = SleepChartImage.render(days(limit), text, colors)
        val more = SleepChartImage.render(days(limit + 100), text, colors)
        assertEquals(full.height, more.height)
    }

    @Test
    fun `sleep is drawn in the sleep colour across the row`() {
        val image = SleepChartImage.render(days(1), text, colors)
        // Somewhere in the middle of the track of the only row there is sleep colour.
        val found =
            (0 until image.height).any { y ->
                image.getPixel(image.width / 2, y) == sleepColor
            }
        assertTrue(found)
    }
}
