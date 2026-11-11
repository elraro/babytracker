package eu.elraro.babytracker.domain

import eu.elraro.babytracker.stats.StatEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SleepChartTest {
    private val madrid = ZoneId.of("Europe/Madrid")
    private val born = LocalDate.of(2026, 7, 4)
    private var next = 0

    private fun sleep(
        from: String,
        to: String?,
        kind: String = "nap",
    ) = StatEvent(
        id = "e${next++}",
        type = "sleep",
        startedAt = Instant.parse(from),
        endedAt = to?.let(Instant::parse),
        timezone = madrid.id,
        sleepKind = kind,
    )

    private fun bottle(
        at: String,
        ml: Int,
        zone: String = madrid.id,
    ) = StatEvent(id = "e${next++}", type = "bottle", startedAt = Instant.parse(at), timezone = zone, amountMl = ml)

    private fun build(
        events: List<StatEvent>,
        now: String,
        dateOfBirth: LocalDate = born,
    ) = SleepChart.build(events, dateOfBirth, madrid, Instant.parse(now))

    private fun h(
        hours: Int,
        minutes: Int = 0,
    ) = (hours * 60 + minutes) * 60

    @Test
    fun `a night across midnight is drawn on both days`() {
        // 21:00 on 10 October to 06:00 on 11 October, Madrid summer time (UTC+2).
        val days = build(listOf(sleep("2026-10-10T19:00:00Z", "2026-10-11T04:00:00Z", "night")), "2026-10-11T10:00:00Z")
        assertEquals(listOf(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11)), days.map { it.date })
        assertEquals(listOf(ChartSpan(h(21), h(24))), days[0].sleep)
        assertEquals(h(3), days[0].sleepS)
        assertEquals(listOf(ChartSpan(0, h(6))), days[1].sleep)
        assertEquals(h(6), days[1].sleepS)
        assertEquals(0, days[0].naps)
    }

    @Test
    fun `on the day the clocks go forward the skipped hour stays empty`() {
        // 29 March 2026: 02:00 CET becomes 03:00 CEST. From 01:00 to 04:00 on the clock is 2 h.
        val day =
            build(
                listOf(sleep("2026-03-29T00:00:00Z", "2026-03-29T02:00:00Z")),
                "2026-03-29T12:00:00Z",
                dateOfBirth = LocalDate.of(2026, 3, 1),
            ).single()
        assertEquals(listOf(ChartSpan(h(1), h(2)), ChartSpan(h(3), h(4))), day.sleep)
        assertEquals(h(2), day.sleepS)
    }

    @Test
    fun `on the day the clocks go back the repeated hour is drawn over itself`() {
        // 25 October 2026: 03:00 CEST becomes 02:00 CET. From 02:30 CEST to 02:30 CET is 1 h.
        val day = build(listOf(sleep("2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z")), "2026-10-25T12:00:00Z").single()
        assertEquals(listOf(ChartSpan(h(2, 30), h(3)), ChartSpan(h(2), h(2, 30))), day.sleep)
        assertEquals(h(1), day.sleepS)
        // A whole 25-hour day asleep fills the row and counts 25 h.
        val long =
            build(listOf(sleep("2026-10-24T20:00:00Z", "2026-10-26T00:00:00Z", "night")), "2026-10-26T12:00:00Z")
        assertEquals(h(25), long.single { it.date == LocalDate.of(2026, 10, 25) }.sleepS)
    }

    @Test
    fun `a bottle recorded in another zone is placed by the phone's zone`() {
        // 18:30 in New York is 00:30 the next day in Madrid.
        val days =
            build(
                listOf(bottle("2026-10-10T22:30:00Z", 120, "America/New_York"), bottle("2026-10-11T08:00:00Z", 90)),
                "2026-10-11T12:00:00Z",
            )
        val day = days.single()
        assertEquals(LocalDate.of(2026, 10, 11), day.date)
        assertEquals(listOf(h(0, 30), h(10)), day.bottles)
        assertEquals(210, day.bottleMl)
    }

    @Test
    fun `overlapping sleeps from two phones are drawn and counted once`() {
        val day =
            build(
                listOf(
                    sleep("2026-10-10T11:00:00Z", "2026-10-10T12:00:00Z"),
                    sleep("2026-10-10T11:30:00Z", "2026-10-10T12:30:00Z"),
                ),
                "2026-10-10T18:00:00Z",
            ).single()
        assertEquals(listOf(ChartSpan(h(13), h(14, 30))), day.sleep)
        assertEquals(h(1, 30), day.sleepS)
        assertEquals(1, day.naps)
    }

    @Test
    fun `a sleep still going on runs up to now`() {
        val day = build(listOf(sleep("2026-10-10T08:00:00Z", null)), "2026-10-10T09:15:00Z").single()
        assertEquals(listOf(ChartSpan(h(10), h(11, 15))), day.sleep)
        assertEquals(h(1, 15), day.sleepS)
    }

    @Test
    fun `time awake is drawn between sleeps, but a gap over 16 hours is missing data`() {
        val days =
            build(
                listOf(
                    sleep("2026-10-10T08:00:00Z", "2026-10-10T09:00:00Z"),
                    sleep("2026-10-10T11:00:00Z", "2026-10-10T12:00:00Z"),
                    // Nothing recorded for 20 h.
                    sleep("2026-10-11T08:00:00Z", "2026-10-11T09:00:00Z"),
                ),
                "2026-10-11T12:00:00Z",
            )
        assertEquals(listOf(ChartSpan(h(11), h(13))), days[0].awake)
        assertTrue(days[1].awake.isEmpty())
    }

    @Test
    fun `rows run from the first record to today, and nothing recorded gives no rows`() {
        val days = build(listOf(bottle("2026-10-08T08:00:00Z", 100)), "2026-10-11T08:00:00Z")
        assertEquals(LocalDate.of(2026, 10, 8), days.first().date)
        assertEquals(LocalDate.of(2026, 10, 11), days.last().date)
        assertEquals(4, days.size)
        assertTrue(build(emptyList(), "2026-10-11T08:00:00Z").isEmpty())
        // A record dated before the birth (a wrong date of birth) adds no rows before it.
        val early = build(listOf(bottle("2026-06-30T08:00:00Z", 100)), "2026-07-05T08:00:00Z")
        assertEquals(born, early.first().date)
    }

    @Test
    fun `age goes up on each monthly birthday, counted from the date of birth`() {
        assertEquals(3, SleepChart.ageInMonths(born, LocalDate.of(2026, 11, 3)))
        assertEquals(4, SleepChart.ageInMonths(born, LocalDate.of(2026, 11, 4)))
        val lastOfMonth = LocalDate.of(2026, 1, 31)
        assertEquals(0, SleepChart.ageInMonths(lastOfMonth, LocalDate.of(2026, 2, 27)))
        assertEquals(1, SleepChart.ageInMonths(lastOfMonth, LocalDate.of(2026, 2, 28)))
        assertEquals(1, SleepChart.ageInMonths(lastOfMonth, LocalDate.of(2026, 3, 30)))
        assertEquals(2, SleepChart.ageInMonths(lastOfMonth, LocalDate.of(2026, 3, 31)))
        assertEquals(0, SleepChart.ageInMonths(born, born))
    }
}
