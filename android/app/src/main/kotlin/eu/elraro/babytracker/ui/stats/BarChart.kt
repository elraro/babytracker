package eu.elraro.babytracker.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import eu.elraro.babytracker.R

/**
 * A plain single-series bar chart. Tapping a bar, or sliding a finger along the chart,
 * shows that bar's label and value in the header in place of the scale; tapping the
 * selected bar again clears it. For a screen reader the whole chart is one element
 * described by its title, lowest and highest value.
 */
@Composable
fun BarChart(
    title: String,
    bars: List<Bar>,
    formatValue: @Composable (Float) -> String,
    modifier: Modifier = Modifier,
) {
    if (bars.isEmpty()) return
    val max = bars.maxOf { it.value }
    val min = bars.minOf { it.value }
    val description = stringResource(R.string.stats_chart_description, title, formatValue(min), formatValue(max))
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    // New data (another range, a new record) clears the selection.
    var selected by remember(bars) { mutableStateOf<Int?>(null) }
    val selectedBar = selected?.let { bars.getOrNull(it) }

    Column(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Title on the left; on the right the selected bar, or the tallest bar's value as the scale.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selectedBar != null) {
                Text(
                    stringResource(R.string.stats_chart_selected, selectedBar.detail, formatValue(selectedBar.value)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    formatValue(max),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(CHART_HEIGHT.dp)
                    .pointerInput(bars) {
                        detectTapGestures { offset ->
                            val index = barIndexAt(offset.x, size.width.toFloat(), bars.size)
                            selected = if (selected == index) null else index
                        }
                    }.pointerInput(bars) {
                        detectHorizontalDragGestures(
                            onDragStart = { selected = barIndexAt(it.x, size.width.toFloat(), bars.size) },
                        ) { change, _ ->
                            selected = barIndexAt(change.position.x, size.width.toFloat(), bars.size)
                        }
                    },
        ) {
            val slot = size.width / bars.size
            val barWidth = slot * BAR_FILL
            val radius = CornerRadius(barWidth / 4)
            bars.forEachIndexed { index, bar ->
                val left = index * slot + (slot - barWidth) / 2
                // A faint full-height track keeps empty days visible as days.
                drawRoundRect(trackColor, Offset(left, 0f), Size(barWidth, size.height), radius, alpha = TRACK_ALPHA)
                if (max > 0f && bar.value > 0f) {
                    val height = size.height * (bar.value / max)
                    // While one bar is selected, the others fade so it stands out.
                    val alpha = if (selected == null || selected == index) 1f else UNSELECTED_ALPHA
                    drawRoundRect(
                        barColor,
                        Offset(left, size.height - height),
                        Size(barWidth, height),
                        radius,
                        alpha = alpha,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(bars.first(), bars[bars.size / 2], bars.last()).forEach {
                Text(
                    it.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One bar. [label] is shown under the first, middle and last bars only, to stay legible;
 * [detail] names the bar when it is selected (defaults to [label]).
 */
data class Bar(
    val value: Float,
    val label: String,
    val detail: String = label,
)

/** The bar under horizontal position [x] of a chart [width] wide with [count] equal slots, clamped to the ends. */
internal fun barIndexAt(
    x: Float,
    width: Float,
    count: Int,
): Int = if (width <= 0f) 0 else (x / width * count).toInt().coerceIn(0, count - 1)

private const val CHART_HEIGHT = 96
private const val BAR_FILL = 0.7f
private const val TRACK_ALPHA = 0.5f
private const val UNSELECTED_ALPHA = 0.35f
