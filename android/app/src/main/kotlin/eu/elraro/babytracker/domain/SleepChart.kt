package eu.elraro.babytracker.domain

import eu.elraro.babytracker.stats.StatEvent
import eu.elraro.babytracker.stats.WAKE_WINDOW_MAX
import eu.elraro.babytracker.stats.normalize
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset

/** A stretch of a chart row, in seconds of wall-clock time after the day's midnight (0 – 86 400). */
data class ChartSpan(
    val fromS: Int,
    val toS: Int,
)

/** One row of the day-by-day sleep chart. */
data class ChartDay(
    val date: LocalDate,
    /** Whole months of age; it goes up on each monthly birthday. */
    val ageMonths: Int,
    val sleep: List<ChartSpan>,
    /** Time awake between two sleeps. Before the first and after the last sleep nothing is known. */
    val awake: List<ChartSpan>,
    /** Wall-clock second of each bottle. */
    val bottles: List<Int>,
    val bottleMl: Int,
    /** Real seconds asleep during the day; a sleep still going on counts up to now. */
    val sleepS: Int,
    /** Daytime sleeps that start during the day. */
    val naps: Int,
)

/**
 * Lays out recorded sleep and bottles as one row per day, from the first record to today,
 * for the day-by-day chart. This draws the records; it computes no statistic.
 *
 * - Sleeps are merged like the statistics do (docs/STATISTICS.md §6), and a gap longer than
 *   16 h is missing data (§7), so it is not drawn as time awake.
 * - Days and positions are in [zone]. A position is the wall-clock time: on the day the
 *   clocks go back the repeated hour is drawn twice over the same place, and on the day they
 *   go forward the skipped hour stays empty. Durations ([ChartDay.sleepS]) use real time.
 */
object SleepChart {
    private const val DAY_S = 24 * 60 * 60
    private const val SLEEP = "sleep"
    private const val BOTTLE = "bottle"

    private class Stretch(
        val start: Instant,
        val end: Instant,
        val night: Boolean,
    )

    fun build(
        events: List<StatEvent>,
        dateOfBirth: LocalDate,
        zone: ZoneId,
        now: Instant,
    ): List<ChartDay> {
        val sleeps =
            normalize(events.filter { it.type == SLEEP })
                .map { Stretch(it.start, it.end ?: now, it.night) }
                .filter { it.end.isAfter(it.start) }
        val awake =
            sleeps.zipWithNext().mapNotNull { (before, after) ->
                val gap = Duration.between(before.end, after.start)
                if (gap.isNegative ||
                    gap.isZero ||
                    gap > WAKE_WINDOW_MAX
                ) {
                    null
                } else {
                    Stretch(before.end, after.start, false)
                }
            }
        val bottles = events.filter { it.type == BOTTLE }.sortedBy { it.startedAt }

        val first =
            (sleeps.map { it.start } + bottles.map { it.startedAt }).minOrNull()?.atZone(zone)?.toLocalDate()
                ?: return emptyList()
        val today = now.atZone(zone).toLocalDate()
        val days = mutableListOf<ChartDay>()
        var date = maxOf(first, dateOfBirth)
        while (!date.isAfter(today)) {
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()
            val midnight = date.atStartOfDay()
            val inDay = bottles.filter { !it.startedAt.isBefore(start) && it.startedAt.isBefore(end) }
            days +=
                ChartDay(
                    date = date,
                    ageMonths = ageInMonths(dateOfBirth, date),
                    sleep = spans(sleeps, start, end, midnight, zone),
                    awake = spans(awake, start, end, midnight, zone),
                    bottles = inDay.map { wallSecond(it.startedAt, zone.rules.getOffset(it.startedAt), midnight) },
                    bottleMl = inDay.sumOf { it.amountMl ?: 0 },
                    sleepS =
                        sleeps.sumOf {
                            val from = maxOf(it.start, start)
                            val to = minOf(it.end, end)
                            if (to.isAfter(from)) Duration.between(from, to).seconds.toInt() else 0
                        },
                    naps = sleeps.count { !it.night && !it.start.isBefore(start) && it.start.isBefore(end) },
                )
            date = date.plusDays(1)
        }
        return days
    }

    /**
     * Whole months from [dateOfBirth] to [date]. Each monthly birthday is counted from the
     * date of birth, so a baby born on 31 January is one month old on 28 February.
     */
    fun ageInMonths(
        dateOfBirth: LocalDate,
        date: LocalDate,
    ): Int {
        if (date.isBefore(dateOfBirth)) return 0
        val months = Period.between(dateOfBirth, date).toTotalMonths()
        return (if (dateOfBirth.plusMonths(months + 1).isAfter(date)) months else months + 1).toInt()
    }

    /** The parts of [stretches] inside [start, end), as wall-clock spans of that day. */
    private fun spans(
        stretches: List<Stretch>,
        start: Instant,
        end: Instant,
        midnight: LocalDateTime,
        zone: ZoneId,
    ): List<ChartSpan> =
        stretches
            .filter { it.end.isAfter(start) && it.start.isBefore(end) }
            .flatMap { wallSpans(maxOf(it.start, start), minOf(it.end, end), midnight, zone) }

    /** Splits [from, to) at clock changes, so that each piece has one offset and runs forward. */
    private fun wallSpans(
        from: Instant,
        to: Instant,
        midnight: LocalDateTime,
        zone: ZoneId,
    ): List<ChartSpan> {
        val out = mutableListOf<ChartSpan>()
        var at = from
        while (at.isBefore(to)) {
            val offset = zone.rules.getOffset(at)
            val change = zone.rules.nextTransition(at)?.instant
            val until = if (change != null && change.isBefore(to)) change else to
            out += ChartSpan(wallSecond(at, offset, midnight), wallSecond(until, offset, midnight))
            at = until
        }
        return out
    }

    private fun wallSecond(
        at: Instant,
        offset: ZoneOffset,
        midnight: LocalDateTime,
    ): Int =
        Duration
            .between(midnight, at.atOffset(offset).toLocalDateTime())
            .seconds
            .toInt()
            .coerceIn(0, DAY_S)
}
