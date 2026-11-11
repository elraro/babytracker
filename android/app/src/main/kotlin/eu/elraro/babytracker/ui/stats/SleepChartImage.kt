package eu.elraro.babytracker.ui.stats

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import eu.elraro.babytracker.domain.ChartDay
import eu.elraro.babytracker.domain.ChartSpan
import java.time.LocalDate

/** Everything the image says, already in the app's language. */
class ChartImageText(
    val title: String,
    val legendSleep: String,
    val legendAwake: String,
    val legendBottle: String,
    /** Labels of the hours 0, 6, 12 and 18. */
    val hours: List<String>,
    val note: String,
    val month: (ageMonths: Int) -> String,
    val date: (LocalDate) -> String,
    val total: (sleepS: Int) -> String,
)

/** ARGB colours of the image, taken from the theme on screen. */
class ChartImageColors(
    val background: Int,
    val text: Int,
    val muted: Int,
    val heading: Int,
    val sleep: Int,
    val awake: Int,
    val bottle: Int,
    val track: Int,
    val grid: Int,
)

/**
 * Draws the day-by-day sleep chart as one image to share: the same rows as the screen,
 * newest first, with the legend, the hours and the note. It shows at most [MAX_DAYS] days.
 */
object SleepChartImage {
    const val MAX_DAYS = 366

    private const val WIDTH = 1080
    private const val PAD = 48f
    private const val DATE_WIDTH = 130f
    private const val TOTAL_WIDTH = 110f
    private const val TITLE_SIZE = 44f
    private const val LEGEND_SIZE = 26f
    private const val SMALL_SIZE = 22f
    private const val MONTH_SIZE = 28f
    private const val TITLE_GAP = 28f
    private const val LEGEND_HEIGHT = 52f
    private const val AXIS_HEIGHT = 36f
    private const val MONTH_HEIGHT = 56f
    private const val ROW_HEIGHT = 30f
    private const val DOT_RADIUS = 3.5f
    private const val BAR_TOP = 9f
    private const val BAR_BOTTOM = 25f
    private const val SWATCH_W = 32f
    private const val SWATCH_H = 18f
    private const val ITEM_GAP = 36f
    private const val MARK_GAP = 10f
    private const val SWATCH_RADIUS = 4f
    private const val MONTH_DESCENT = 16f
    private const val X_HEIGHT_CENTRE = 1 / 3f
    private const val NOTE_GAP = 24f
    private const val HOURS_PER_TICK = 6
    private const val HOURS_PER_DAY = 24
    private const val DAY_S = 86_400f
    private const val SECONDS_PER_HOUR = 3600

    /** [days] newest first, as on screen. */
    fun render(
        days: List<ChartDay>,
        text: ChartImageText,
        colors: ChartImageColors,
    ): Bitmap {
        val shown = days.take(MAX_DAYS)
        val months = shown.indices.count { it == 0 || shown[it - 1].ageMonths != shown[it].ageMonths }
        val note = noteLayout(text.note, colors)
        val height =
            PAD + TITLE_SIZE + TITLE_GAP + LEGEND_HEIGHT + AXIS_HEIGHT + months * MONTH_HEIGHT +
                shown.size * ROW_HEIGHT + NOTE_GAP + note.height + PAD
        val bitmap = createBitmap(WIDTH, height.toInt())
        val canvas = Canvas(bitmap)
        canvas.drawColor(colors.background)

        var y = PAD + TITLE_SIZE
        canvas.drawText(text.title, PAD, y, paint(colors.text, TITLE_SIZE, bold = true))
        y += TITLE_GAP
        drawLegend(canvas, y, text, colors)
        y += LEGEND_HEIGHT
        drawAxis(canvas, y, text, colors)
        y += AXIS_HEIGHT
        shown.forEachIndexed { index, day ->
            if (index == 0 || shown[index - 1].ageMonths != day.ageMonths) {
                canvas.drawText(
                    text.month(day.ageMonths),
                    PAD,
                    y + MONTH_HEIGHT - MONTH_DESCENT,
                    paint(colors.heading, MONTH_SIZE, bold = true),
                )
                y += MONTH_HEIGHT
            }
            drawRow(canvas, y, day, text, colors)
            y += ROW_HEIGHT
        }
        canvas.withTranslation(PAD, y + NOTE_GAP) { note.draw(this) }
        return bitmap
    }

    private const val TRACK_LEFT = PAD + DATE_WIDTH
    private const val TRACK_RIGHT = WIDTH - PAD - TOTAL_WIDTH

    private fun x(second: Int): Float = TRACK_LEFT + (TRACK_RIGHT - TRACK_LEFT) * second / DAY_S

    private fun paint(
        color: Int,
        size: Float,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        textAlign = align
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun drawLegend(
        canvas: Canvas,
        top: Float,
        text: ChartImageText,
        colors: ChartImageColors,
    ) {
        val label = paint(colors.text, LEGEND_SIZE)
        val baseline = top + LEGEND_SIZE
        // Marks sit on the middle of lower-case letters, about a third of the size above the baseline.
        val middle = baseline - LEGEND_SIZE * X_HEIGHT_CENTRE
        var x = PAD
        for ((name, color) in listOf(text.legendSleep to colors.sleep, text.legendAwake to colors.awake)) {
            canvas.drawRoundRect(
                RectF(x, middle - SWATCH_H / 2, x + SWATCH_W, middle + SWATCH_H / 2),
                SWATCH_RADIUS,
                SWATCH_RADIUS,
                fill(color),
            )
            x += SWATCH_W + MARK_GAP
            canvas.drawText(name, x, baseline, label)
            x += label.measureText(name) + ITEM_GAP
        }
        // The legend's dot is twice the size of the chart's, to be seen next to the text.
        val radius = DOT_RADIUS * 2
        canvas.drawCircle(x + radius, middle, radius, fill(colors.bottle))
        canvas.drawText(text.legendBottle, x + radius * 2 + MARK_GAP, baseline, label)
    }

    private fun drawAxis(
        canvas: Canvas,
        top: Float,
        text: ChartImageText,
        colors: ChartImageColors,
    ) {
        val label = paint(colors.muted, SMALL_SIZE)
        text.hours.forEachIndexed { index, hour ->
            canvas.drawText(hour, x(index * HOURS_PER_TICK * SECONDS_PER_HOUR), top + SMALL_SIZE, label)
        }
    }

    private fun drawRow(
        canvas: Canvas,
        top: Float,
        day: ChartDay,
        text: ChartImageText,
        colors: ChartImageColors,
    ) {
        val baseline = top + BAR_BOTTOM - 1f
        canvas.drawText(text.date(day.date), PAD, baseline, paint(colors.muted, SMALL_SIZE))
        canvas.drawText(
            text.total(day.sleepS),
            WIDTH - PAD,
            baseline,
            paint(colors.text, SMALL_SIZE, align = Paint.Align.RIGHT),
        )
        canvas.drawRect(TRACK_LEFT, top + BAR_TOP, TRACK_RIGHT, top + BAR_BOTTOM, fill(colors.track))
        val grid = fill(colors.grid).apply { strokeWidth = 1f }
        for (hour in HOURS_PER_TICK until HOURS_PER_DAY step HOURS_PER_TICK) {
            val at = x(hour * SECONDS_PER_HOUR)
            canvas.drawLine(at, top + BAR_TOP, at, top + BAR_BOTTOM, grid)
        }

        fun bar(
            span: ChartSpan,
            paint: Paint,
        ) {
            // A very short span still shows as a hairline.
            val right = maxOf(x(span.toS), x(span.fromS) + 1f)
            canvas.drawRect(x(span.fromS), top + BAR_TOP, right, top + BAR_BOTTOM, paint)
        }
        val awake = fill(colors.awake)
        day.awake.forEach { bar(it, awake) }
        val sleep = fill(colors.sleep)
        day.sleep.forEach { bar(it, sleep) }
        val dot = fill(colors.bottle)
        day.bottles.forEach {
            canvas.drawCircle(
                x(it).coerceIn(TRACK_LEFT + DOT_RADIUS, TRACK_RIGHT - DOT_RADIUS),
                top + DOT_RADIUS,
                DOT_RADIUS,
                dot,
            )
        }
    }

    private fun noteLayout(
        note: String,
        colors: ChartImageColors,
    ): StaticLayout {
        val paint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.muted
                textSize = SMALL_SIZE
            }
        return StaticLayout.Builder
            .obtain(note, 0, note.length, paint, (WIDTH - 2 * PAD).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
    }
}
