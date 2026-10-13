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
    private val hourH = ctx.dp(34).toFloat()
    private val gutter = ctx.dp(28).toFloat()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palm.INK; strokeWidth = ctx.dp(1.2f).toFloat() }
    private val thin = Paint().apply { color = Palm.LIGHT; strokeWidth = 1f }
    private val fill = Paint().apply { color = Palm.GRAY }
    private val shade = Paint().apply { color = 0x14000000 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; textSize = ctx.dp(10).toFloat(); typeface = Typeface.DEFAULT_BOLD
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; style = Paint.Style.STROKE; strokeWidth = ctx.dp(1.2f).toFloat()
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
        for (h in 0..24) {
            val y = h * hourH
            c.drawLine(gutter, y, width.toFloat(), y, thin)
            if (h < 24) {
                val lbl = if (Fmt.h24) "$h" else (if (h % 12 == 0) "12" else "${h % 12}")
                c.drawText(lbl, ctx2dp(3f), y + text.textSize + 2, text)
            }
        }
        for (i in 0..7) {
            val x = gutter + i * cw
            c.drawLine(x, 0f, x, height.toFloat(), if (i == 0) ink else thin)
        }
        boxes.clear()
        for (i in 0..6) {
            val (laneOf, nLanes) = laneInfo[i]
            val lw = (cw - ctx2dp(4f)) / nLanes
            for (o in timed[i]) {
                val e = o.event
                val lane = laneOf[o] ?: 0
                val l = gutter + i * cw + ctx2dp(2f) + lane * lw
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

    private fun ctx2dp(v: Float) = context.dp(v).toFloat()

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
    private val headH = ctx.dp(22).toFloat()
    private val line = Paint().apply { color = Palm.INK; strokeWidth = ctx.dp(1f).toFloat() }
    private val thick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; style = Paint.Style.STROKE; strokeWidth = ctx.dp(2.5f).toFloat()
    }
    private val fill = Paint().apply { color = Palm.INK }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; textSize = ctx.dp(Palm.textSp - 1).toFloat(); typeface = Typeface.DEFAULT_BOLD
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; textSize = ctx.dp(12).toFloat(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val dash = Paint().apply {
        color = Palm.INK; strokeWidth = ctx.dp(2f).toFloat(); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(ctx.dp(3).toFloat(), ctx.dp(3).toFloat()), 0f)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; textSize = ctx.dp(9).toFloat()
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

    private fun cellH() = (height - headH) / 6f

    override fun onDraw(c: Canvas) {
        c.drawColor(Palm.paper)
        val cw = width / 7f
        val ch = cellH()
        for (i in 0..6) c.drawText(Fmt.dowLetter[i], cw * i + cw / 2, headH - ctx(6f), head)
        for (r in 0..6) c.drawLine(0f, headH + r * ch, width.toFloat(), headH + r * ch, line)
        for (i in 0..7) c.drawLine(minOf(i * cw, width - 1f), headH, minOf(i * cw, width - 1f), height.toFloat(), line)
        val today = LocalDate.now().toEpochDay()
        val p = act.prefs
        for (n in 1..len) {
            val idx = n - 1 + lead
            val col = idx % 7
            val row = idx / 7
            val x = col * cw
            val y = headH + row * ch
            val ep = LocalDate.of(year, month, n).toEpochDay()
            val label = n.toString()
            if (ep == today) {
                val w = num.measureText(label)
                c.drawRect(x + ctx(2f), y + ctx(2f), x + w + ctx(7f), y + num.textSize + ctx(5f), fill)
                num.color = Palm.paper
                c.drawText(label, x + ctx(4.5f), y + num.textSize + ctx(2f), num)
                num.color = Palm.INK
            } else c.drawText(label, x + ctx(4.5f), y + num.textSize + ctx(2f), num)
            if (ep == act.day) c.drawRect(x + ctx(1.5f), y + ctx(1.5f), x + cw - ctx(1.5f), y + ch - ctx(1.5f), thick)

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
            val top = y + num.textSize + ctx(6f)
            val avail = y + ch - ctx(6f) - top
            for (b in 0..2) if (bands[b]) {
                val by = top + avail * b / 3f
                c.drawRect(x + cw - ctx(9f), by, x + cw - ctx(4f), by + minOf(ctx(5f), avail / 3f), fill)
            }
            if (untimed) {
                val cx = x + ctx(8f); val cy = y + ch - ctx(9f); val s = ctx(4f)
                c.drawLine(cx - s, cy, cx + s, cy, line)
                c.drawLine(cx, cy - s, cx, cy + s, line)
            }
            if (daily) c.drawLine(x + ctx(3f), y + ch - ctx(3f), x + cw - ctx(3f), y + ch - ctx(3f), dash)
            if (count > 1 && ch > ctx(56f)) c.drawText("$count", x + ctx(15f), y + ch - ctx(6f), small)
        }
    }

    private fun ctx(v: Float) = context.dp(v).toFloat()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gd.onTouchEvent(event) || super.onTouchEvent(event)
}

/** The four view icons at the bottom of the Date Book screens. */
@SuppressLint("ViewConstructor")
class ViewIcon(ctx: Context, private val kind: Int, private val active: Boolean, onClick: () -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ctx.dp(1.3f).toFloat() }

    init { setOnClickListener { onClick() }; contentDescription = listOf("Day", "Week", "Month", "Agenda")[kind] }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dp(40), context.dp(36))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val m = context.dp(4).toFloat()
        p.style = Paint.Style.FILL
        p.color = if (active) Palm.INK else Palm.paper
        c.drawRect(m, m, w - m, h - m, p)
        p.style = Paint.Style.STROKE
        p.color = Palm.INK
        c.drawRect(m, m, w - m, h - m, p)
        p.color = if (active) Palm.paper else Palm.INK
        val l = m + 3; val r = w - m - 3; val t = m + 3; val b = h - m - 3
        when (kind) {
            0 -> { // Day: lines of a page
                for (k in 0..3) { val y = t + (b - t) * k / 3f; c.drawLine(l, y, r, y, p) }
            }
            1 -> { // Week: seven columns
                for (k in 0..7) { val x = l + (r - l) * k / 7f; c.drawLine(x, t, x, b, p) }
            }
            2 -> { // Month: grid
                for (k in 0..3) { val y = t + (b - t) * k / 3f; c.drawLine(l, y, r, y, p) }
                for (k in 0..4) { val x = l + (r - l) * k / 4f; c.drawLine(x, t, x, b, p) }
            }
            else -> { // Agenda: list with a check box
                c.drawRect(l, t, l + 5, t + 5, p)
                c.drawLine(l + 8, t + 3, r, t + 3, p)
                c.drawLine(l, (t + b) / 2, r, (t + b) / 2, p)
                c.drawLine(l, b - 2, r, b - 2, p)
            }
        }
    }
}

/** The Menu and Find icons at the right end of the command row. */
@SuppressLint("ViewConstructor")
class SilkIcon(ctx: Context, private val find: Boolean, onClick: () -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; style = Paint.Style.STROKE; strokeWidth = ctx.dp(2f).toFloat()
    }

    init { setOnClickListener { onClick() }; contentDescription = if (find) "Find" else "Menu" }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dp(32), context.dp(36))

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = context.dp(7).toFloat()
        if (find) { // magnifier
            c.drawCircle(cx - r / 3, cy - r / 3, r, p)
            c.drawLine(cx + r * 0.4f, cy + r * 0.4f, cx + r * 1.3f, cy + r * 1.3f, p)
        } else for (k in -1..1) { // three lines
            c.drawLine(cx - r * 1.3f, cy + k * r * 0.8f, cx + r * 1.3f, cy + k * r * 0.8f, p)
        }
    }
}
