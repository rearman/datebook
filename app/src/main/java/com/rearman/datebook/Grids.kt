package com.rearman.datebook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.time.LocalDate

/** Assigns side-by-side lanes to overlapping timed events. Returns lane per occurrence and lane count. */
fun lanes(occs: List<Occ>): Pair<Map<Occ, Int>, Int> {
    val laneEnd = mutableListOf<Int>()
    val out = HashMap<Occ, Int>()
    for (o in occs.sortedWith(compareBy<Occ>({ it.event.start }, { it.event.end }))) {
        val e = o.event
        val endMin = maxOf(e.end, e.start + 15)
        var lane = laneEnd.indexOfFirst { it <= e.start }
        if (lane < 0) { laneEnd.add(endMin); lane = laneEnd.size - 1 } else laneEnd[lane] = endMin
        out[o] = lane
    }
    return Pair(out, maxOf(1, laneEnd.size))
}

/** The Week view time grid: seven columns, gray blocks for events. */
@SuppressLint("ViewConstructor")
class WeekGrid(
    ctx: Context,
    private val act: MainActivity,
    private val weekStart: Long,
    private val onSelect: (Occ?) -> Unit
) : View(ctx) {
    private val hourH = ctx.ppf(11f)
    private val gutter = ctx.ppf(12f)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palm.INK; strokeWidth = ctx.ppf(1f) }
    private val thin = Paint().apply { color = Palm.INK; strokeWidth = 1f }
    private val fill = Paint().apply { color = Palm.GRAY }
    private val shade = Paint().apply { color = 0x14000000 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; textSize = ctx.ppf(7f); typeface = Typeface.DEFAULT_BOLD
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; style = Paint.Style.STROKE; strokeWidth = ctx.ppf(1f)
    }
    private val days = (0..6).map { weekStart + it }
    private val timed = days.map { d -> act.dayOccs(d).filter { !it.event.untimed } }
    private val laneInfo = timed.map { lanes(it) }
    var selected: Occ? = null
    private val boxes = mutableListOf<Pair<RectF, Occ>>()

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val hit = boxes.lastOrNull { it.first.contains(e.x, e.y) }?.second
            if (hit != null) {
                if (act.isMasked(hit.event)) { act.revealAsk(hit.event); return true }
                if (selected == hit) act.goDay(hit.day, hit)
                else { selected = hit; onSelect(hit); invalidate() }
            } else {
                val col = colAt(e.x)
                if (col >= 0) act.goDay(days[col], null)
            }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val hit = boxes.lastOrNull { it.first.contains(e.x, e.y) }?.second ?: return
            if (!act.isMasked(hit.event)) act.editEvent(hit)
        }
    })

    private fun colW() = (width - gutter) / 7f
    private fun colAt(x: Float): Int = if (x < gutter) -1 else ((x - gutter) / colW()).toInt().coerceIn(0, 6)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (hourH * 24).toInt() + 2)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Palm.paper)
        val cw = colW()
        val p = act.prefs
        // Shade the hours outside the day's start and end times.
        c.drawRect(gutter, 0f, width.toFloat(), p.dayStart * hourH, shade)
        c.drawRect(gutter, p.dayEnd * hourH, width.toFloat(), 24 * hourH, shade)
        // A line and a label at each even hour only.
        for (h in 0..24 step 2) {
            val y = h * hourH
            c.drawLine(gutter, y, width.toFloat(), y, thin)
            if (h < 24) {
                val lbl = if (Fmt.h24) "$h" else (if (h % 12 == 0) "12" else "${h % 12}")
                c.drawText(lbl, context.ppf(1f), y + text.textSize + 2, text)
            }
        }
        for (i in 0..7) {
            val x = gutter + i * cw
            c.drawLine(x, 0f, x, height.toFloat(), if (i == 0) ink else thin)
        }
        boxes.clear()
        for (i in 0..6) {
            val (laneOf, nLanes) = laneInfo[i]
            val lw = (cw - context.ppf(2f)) / nLanes
            for (o in timed[i]) {
                val e = o.event
                val lane = laneOf[o] ?: 0
                val l = gutter + i * cw + context.ppf(1f) + lane * lw
                val top = e.start / 60f * hourH
                val bot = maxOf(e.end, e.start + 15) / 60f * hourH
                val r = RectF(l, top + 1, l + lw - 1, bot - 1)
                fill.color = when {
                    act.isMasked(e) -> Palm.LIGHT
                    o == selected -> Palm.INK
                    else -> Palm.GRAY
                }
                c.drawRect(r, fill)
                c.drawRect(r, stroke)
                boxes.add(r to o)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gd.onTouchEvent(event) || super.onTouchEvent(event)
}

/** The Month view: a calendar grid with Palm marks for timed, untimed and daily events. */
@SuppressLint("ViewConstructor")
class MonthGrid(
    ctx: Context,
    private val act: MainActivity,
    private val year: Int,
    private val month: Int
) : View(ctx) {
    private val first = LocalDate.of(year, month, 1)
    private val lead = Recur.dow(first)
    private val len = first.lengthOfMonth()
    private val headH = ctx.ppf(11f)
    private val line = Paint().apply { color = Palm.INK; strokeWidth = ctx.ppf(1f) }
    private val thick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; style = Paint.Style.STROKE; strokeWidth = ctx.ppf(1.5f)
    }
    private val fill = Paint().apply { color = Palm.INK }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; textSize = ctx.ppf(Palm.text - 1); typeface = Typeface.DEFAULT_BOLD
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; textSize = ctx.ppf(Palm.text - 1); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val dash = Paint().apply {
        color = Palm.INK; strokeWidth = ctx.ppf(1f); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(ctx.ppf(1.5f), ctx.ppf(1.5f)), 0f)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; textSize = ctx.ppf(6f)
    }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.y < headH) return true
            val col = (e.x / (width / 7f)).toInt().coerceIn(0, 6)
            val row = ((e.y - headH) / cellH()).toInt().coerceIn(0, 5)
            val n = row * 7 + col - lead + 1
            if (n in 1..len) act.goDay(LocalDate.of(year, month, n).toEpochDay(), null)
            return true
        }
    })

    private fun cellH() = (height - headH - line.strokeWidth) / 6f

    // Palm month grid: an 11 px head and six 20 px weeks, plus one line width so the outer lines
    // stay inside the view. It shrinks to fit when the screen has less room.
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(
        MeasureSpec.getSize(w),
        resolveSize((headH + 6 * context.ppf(20f) + line.strokeWidth).toInt(), h)
    )

    override fun onDraw(c: Canvas) {
        c.drawColor(Palm.paper)
        val sw = line.strokeWidth
        val cw = (width - sw) / 7f
        val ch = cellH()
        for (i in 0..6) c.drawText(Fmt.dowLetter[i], sw / 2 + cw * i + cw / 2, headH - ctx(2.5f), head)
        for (r in 0..6) { val y = headH + sw / 2 + r * ch; c.drawLine(0f, y, width.toFloat(), y, line) }
        for (i in 0..7) { val x = sw / 2 + i * cw; c.drawLine(x, headH, x, height.toFloat(), line) }
        val today = LocalDate.now().toEpochDay()
        val p = act.prefs
        for (n in 1..len) {
            val idx = n - 1 + lead
            val col = idx % 7
            val row = idx / 7
            val x = sw / 2 + col * cw
            val y = headH + sw / 2 + row * ch
            val ep = LocalDate.of(year, month, n).toEpochDay()
            val label = n.toString()
            if (ep == today) {
                val w = num.measureText(label)
                c.drawRect(x + ctx(1f), y + ctx(1f), x + w + ctx(3f), y + num.textSize + ctx(2f), fill)
                num.color = Palm.paper
                c.drawText(label, x + ctx(1.5f), y + num.textSize + ctx(1f), num)
                num.color = Palm.INK
            } else c.drawText(label, x + ctx(1.5f), y + num.textSize + ctx(1f), num)
            if (ep == act.day) c.drawRect(x + ctx(0.75f), y + ctx(0.75f), x + cw - ctx(0.75f), y + ch - ctx(0.75f), thick)

            val occs = act.dayOccs(ep)
            var daily = false
            var untimed = false
            val bands = BooleanArray(3)
            var count = 0
            for (o in occs) {
                val e = o.event
                if (Recur.isDaily(e)) { if (p.monthDaily) daily = true; continue }
                count++
                if (e.untimed) { if (p.monthUntimed) untimed = true }
                else if (p.monthTimed) bands[when { e.start < 12 * 60 -> 0; e.start < 18 * 60 -> 1; else -> 2 }] = true
            }
            val top = y + num.textSize + ctx(2.5f)
            val avail = y + ch - ctx(2.5f) - top
            for (b in 0..2) if (bands[b]) {
                val by = top + avail * b / 3f
                c.drawRect(x + cw - ctx(4f), by, x + cw - ctx(1.5f), by + minOf(ctx(2f), avail / 3f), fill)
            }
            if (untimed) {
                val cx = x + ctx(3.5f); val cy = y + ch - ctx(4f); val s = ctx(1.5f)
                c.drawLine(cx - s, cy, cx + s, cy, line)
                c.drawLine(cx, cy - s, cx, cy + s, line)
            }
            if (daily) c.drawLine(x + ctx(1f), y + ch - ctx(1f), x + cw - ctx(1f), y + ch - ctx(1f), dash)
            if (count > 1 && ch > ctx(22f)) c.drawText("$count", x + ctx(6f), y + ch - ctx(2.5f), small)
        }
    }

    private fun ctx(v: Float) = context.ppf(v)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gd.onTouchEvent(event) || super.onTouchEvent(event)
}

/**
 * The icons in the bottom row: the four views (0 to 3), then Menu (4) and Find (5).
 * Each icon is 12 Palm px wide and 13 tall, with an 11 px tall box. The box has no side
 * margin, so icons placed with a -1 px margin share one border line, as Palm push buttons do.
 */
@SuppressLint("ViewConstructor")
class ViewIcon(ctx: Context, private val kind: Int, private val active: Boolean, onClick: () -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ctx.ppf(1f) }

    init { setOnClickListener { onClick() }; contentDescription = listOf("Day", "Week", "Month", "Agenda", "Menu", "Find")[kind] }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.pp(12), context.pp(13))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val m = context.ppf(1f)
        val mx = p.strokeWidth / 2
        p.style = Paint.Style.FILL
        p.color = if (active) Palm.INK else Palm.paper
        c.drawRect(mx, m, w - mx, h - m, p)
        p.style = Paint.Style.STROKE
        p.color = Palm.INK
        c.drawRect(mx, m, w - mx, h - m, p)
        p.color = if (active) Palm.paper else Palm.INK
        val g = context.ppf(1f)
        val l = mx + g; val r = w - mx - g; val t = m + g; val b = h - m - g
        val dr = context.ppf(0.8f)
        fun dot(x: Float, y: Float) { p.style = Paint.Style.FILL; c.drawCircle(x, y, dr, p) }
        when (kind) {
            0 -> dot(w / 2, h / 2) // Day: one dot in the center
            1 -> for (k in 0..3) dot(l + (r - l) * (k + 0.5f) / 4, h / 2) // Week: four dots across
            2 -> for (i in 0..3) for (j in 0..3) // Month: 4 by 4 grid of dots
                dot(l + (r - l) * (i + 0.5f) / 4, t + (b - t) * (j + 0.5f) / 4)
            3 -> { // Agenda: a dot with two lines under it
                dot(w / 2, t + (b - t) * 0.2f)
                p.style = Paint.Style.STROKE
                c.drawLine(l, t + (b - t) * 0.6f, r, t + (b - t) * 0.6f, p)
                c.drawLine(l, t + (b - t) * 0.9f, r, t + (b - t) * 0.9f, p)
            }
            4 -> { // Menu: three lines
                p.style = Paint.Style.STROKE
                for (k in 0..2) { val y = t + (b - t) * (0.2f + 0.3f * k); c.drawLine(l, y, r, y, p) }
            }
            else -> { // Find: magnifier
                p.style = Paint.Style.STROKE
                val rr = (b - t) * 0.28f
                val cx = w / 2 - rr / 3; val cy = h / 2 - rr / 3
                c.drawCircle(cx, cy, rr, p)
                c.drawLine(cx + rr * 0.7f, cy + rr * 0.7f, cx + rr * 1.7f, cy + rr * 1.7f, p)
            }
        }
    }
}
