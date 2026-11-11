package eu.elraro.babytracker.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.elraro.babytracker.R
import eu.elraro.babytracker.domain.ChartDay
import eu.elraro.babytracker.domain.ChartSpan
import eu.elraro.babytracker.ui.common.EmptyState
import eu.elraro.babytracker.ui.common.Formatters
import eu.elraro.babytracker.ui.common.durationText
import eu.elraro.babytracker.ui.common.rememberFormatters
import java.time.Duration

private const val DAY_S = 24 * 60 * 60f
private const val SECONDS_PER_HOUR = 3600
private const val TENTHS = 10
private const val HOURS_PER_TICK = 6
private val DATE_WIDTH = 60.dp
private val TOTAL_WIDTH = 52.dp
private val ROW_HEIGHT = 32.dp

/** The colours of the chart: theme roles only, so light, dark and dynamic colour all work. */
private class ChartColors(
    val sleep: Color,
    val awake: Color,
    val bottle: Color,
    val track: Color,
    val grid: Color,
)

@Composable
private fun chartColors() =
    ChartColors(
        sleep = MaterialTheme.colorScheme.primary,
        // A container role: lighter than primary in the light theme and darker in the dark one.
        awake = MaterialTheme.colorScheme.tertiaryContainer,
        bottle = MaterialTheme.colorScheme.onSurface,
        track = MaterialTheme.colorScheme.surfaceVariant,
        grid = MaterialTheme.colorScheme.outlineVariant,
    )

@Composable
fun SleepChartScreen(
    onBack: () -> Unit,
    viewModel: SleepChartViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SleepChartContent(state, onBack)
}

/**
 * Every day since the first record as a row of the 24 hours: sleep, time awake between
 * sleeps and bottles, newest day first, grouped by months of age. Tapping a day shows its
 * totals under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepChartContent(
    state: SleepChartUiState,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sleep_chart_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when {
            state.loading -> Unit
            state.days.isEmpty() ->
                EmptyState(Icons.Outlined.Bedtime, R.string.stats_empty_title, R.string.sleep_chart_empty, modifier)
            else -> ChartList(state.days, modifier)
        }
    }
}

@Composable
private fun ChartList(
    days: List<ChartDay>,
    modifier: Modifier,
) {
    val formatters = rememberFormatters()
    val colors = chartColors()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Legend(colors)
            HourAxis(formatters)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            days.forEachIndexed { index, day ->
                if (index == 0 || days[index - 1].ageMonths != day.ageMonths) monthHeader(day)
                item(key = day.date.toString()) {
                    val date = day.date.toString()
                    DayRow(day, formatters, colors, selected = selected == date) {
                        selected = if (selected == date) null else date
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.sleep_chart_note),
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun LazyListScope.monthHeader(day: ChartDay) {
    // A month of age lies in one block of rows, so its first row (the newest) keys it.
    stickyHeader(key = "age-${day.ageMonths}") {
        Text(
            text = pluralStringResource(R.plurals.sleep_chart_age, day.ageMonths, day.ageMonths),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(top = 12.dp, bottom = 4.dp)
                    .semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun Legend(colors: ChartColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(R.string.sleep_chart_legend_sleep) { Swatch(colors.sleep) }
        LegendItem(R.string.sleep_chart_legend_awake) { Swatch(colors.awake) }
        LegendItem(R.string.sleep_chart_legend_bottle) {
            Box(Modifier.size(BOTTLE_DOT * 2).background(colors.bottle, CircleShape))
        }
    }
}

@Composable
private fun LegendItem(
    label: Int,
    mark: @Composable () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        mark()
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(width = 16.dp, height = 10.dp).background(color, MaterialTheme.shapes.extraSmall))
}

/** Hour labels, each starting at its tick (every 6 hours) over the rows' tracks. */
@Composable
private fun HourAxis(formatters: Formatters) {
    Row(modifier = Modifier.fillMaxWidth().clearAndSetSemantics {}) {
        Spacer(Modifier.width(DATE_WIDTH))
        Row(modifier = Modifier.weight(1f)) {
            for (hour in 0 until HOURS_PER_DAY step HOURS_PER_TICK) {
                Text(
                    text = formatters.hour(hour),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(TOTAL_WIDTH))
    }
}

@Composable
private fun DayRow(
    day: ChartDay,
    formatters: Formatters,
    colors: ChartColors,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val detail =
        stringResource(
            R.string.sleep_chart_detail,
            durationText(Duration.ofSeconds(day.sleepS.toLong())),
            pluralStringResource(R.plurals.stats_naps, day.naps, day.naps),
            pluralStringResource(R.plurals.sleep_chart_bottles, day.bottles.size, day.bottles.size, day.bottleMl),
        )
    val description = stringResource(R.string.sleep_chart_row_description, formatters.date(day.date), detail)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable(onClick = onClick)
                .clearAndSetSemantics { contentDescription = description },
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatters.dayMonth(day.date),
                modifier = Modifier.width(DATE_WIDTH),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Canvas(modifier = Modifier.weight(1f).height(ROW_HEIGHT)) { drawDay(day, colors) }
            Text(
                text = stringResource(R.string.sleep_chart_hours, formatters.tenths(hoursX10(day.sleepS))),
                modifier = Modifier.width(TOTAL_WIDTH),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
            )
        }
        if (selected) {
            Text(
                text = detail,
                modifier = Modifier.padding(start = DATE_WIDTH, bottom = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** Hours in tenths, rounded half up: 13 h 50 min is 138. */
private fun hoursX10(seconds: Int): Int = (seconds * TENTHS + SECONDS_PER_HOUR / 2) / SECONDS_PER_HOUR

/** Bottles as dots along the top; under them the 24-hour track with time awake and sleep. */
private fun DrawScope.drawDay(
    day: ChartDay,
    colors: ChartColors,
) {
    val dotStrip = (BOTTLE_DOT * 2 + BAR_GAP).toPx()
    // The space under the bar keeps each row's bottles closer to it than to the row above.
    val barHeight = size.height - dotStrip - ROW_GAP.toPx()
    val x = { second: Int -> size.width * second / DAY_S }
    drawRect(colors.track, Offset(0f, dotStrip), Size(size.width, barHeight))
    for (hour in HOURS_PER_TICK until HOURS_PER_DAY step HOURS_PER_TICK) {
        val at = x(hour * SECONDS_PER_HOUR)
        drawLine(colors.grid, Offset(at, dotStrip), Offset(at, dotStrip + barHeight), strokeWidth = 1.dp.toPx())
    }

    fun bar(
        span: ChartSpan,
        color: Color,
    ) {
        val left = x(span.fromS)
        // A very short span still shows as a hairline.
        val width = (x(span.toS) - left).coerceAtLeast(1.dp.toPx())
        drawRect(color, Offset(left, dotStrip), Size(width, barHeight))
    }
    day.awake.forEach { bar(it, colors.awake) }
    day.sleep.forEach { bar(it, colors.sleep) }
    val radius = BOTTLE_DOT.toPx()
    day.bottles.forEach {
        drawCircle(
            colors.bottle,
            radius,
            Offset(x(it).coerceIn(radius, size.width - radius), radius),
        )
    }
}

private const val HOURS_PER_DAY = 24
private val BOTTLE_DOT: Dp = 2.5.dp
private val BAR_GAP: Dp = 2.dp
private val ROW_GAP: Dp = 8.dp
