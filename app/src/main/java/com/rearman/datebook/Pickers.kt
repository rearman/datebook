package com.rearman.datebook

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate

/** Palm "Go to Date" / "Set Date" form: year arrows, month buttons, day grid. */
class DateDialog(
    private val ctx: Context,
    title: String,
    initial: Long,
    private val onPick: (Long) -> Unit
) : PalmDialog(ctx, title, scroll = false, height = 156) {
    private var year: Int
    private var month: Int
    private val selected: Long = initial
    private val yearLabel = ctx.label("", bold = true, size = Palm.text)
    private val months: PushGroup
    private val grid = ctx.vbox()

    init {
        val d = LocalDate.ofEpochDay(initial)
        year = d.year
        month = d.monthValue
        val yr = ctx.hbox(Gravity.CENTER)
        yr.addView(ctx.palmButton("◀") { year--; draw() })
        yr.addView(yearLabel, lp().apply { marginStart = ctx.pp(7); marginEnd = ctx.pp(7) })
        yr.addView(ctx.palmButton("▶") { year++; draw() })
        add(yr)
        months = PushGroup(ctx, Fmt.monthShort.toList(), month - 1) { g -> month = g.selected + 1; draw() }
        // Show the 12 month buttons in 2 rows of 6, as on the Palm.
        val rows = ctx.vbox()
        val cells = (0 until months.view.childCount).map { months.view.getChildAt(it) }
        months.view.removeAllViews()
        for (r in 0 until 2) {
            val row = ctx.hbox()
            for (c in 0 until 6) row.addView(cells[r * 6 + c], LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginEnd = ctx.pp(1); topMargin = ctx.pp(1)
            })
            rows.addView(row, lp(MATCH))
        }
        add(rows, 1)
        // The day grid takes the rest of the form, so the form fits without scrolling.
        body.addView(grid, LP(MATCH, 0, 1f).apply { topMargin = ctx.pp(2) })
        button("Cancel") { dismiss() }
        button("Today") { dismiss(); onPick(LocalDate.now().toEpochDay()) }
        draw()
    }

    private fun draw() {
        yearLabel.text = year.toString()
        months.select(month - 1)
        grid.removeAllViews()
        val head = ctx.hbox()
        for (i in 0..6) head.addView(ctx.label(Fmt.dowLetter[i], bold = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        grid.addView(head, lp(MATCH))
        val first = LocalDate.of(year, month, 1)
        val lead = Recur.dow(first)
        val days = first.lengthOfMonth()
        val today = LocalDate.now().toEpochDay()
        var n = 1 - lead
        while (n <= days) {
            val row = ctx.hbox()
            for (c in 0..6) {
                val cell: TextView
                if (n in 1..days) {
                    val ep = LocalDate.of(year, month, n).toEpochDay()
                    cell = ctx.label(n.toString()).apply {
                        gravity = Gravity.CENTER
                        when {
                            ep == selected -> { background = ctx.box(Palm.INK, Palm.INK, 0f, 1f); setTextColor(Palm.paper) }
                            ep == today -> background = ctx.box(android.graphics.Color.TRANSPARENT, Palm.INK, 1f, 1f)
                        }
                        setOnClickListener { dismiss(); onPick(ep) }
                    }
                } else cell = ctx.label("")
                row.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f))
                n++
            }
            grid.addView(row, LP(MATCH, 0, 1f))
        }
    }
}

/**
 * Palm "Set Time" form: start and end boxes on the left, scrolling hour and
 * minute lists on the right, No Time.
 */
class TimeDialog(
    private val ctx: Context,
    untimed: Boolean,
    start: Int,
    end: Int,
    private val onPick: (untimed: Boolean, start: Int, end: Int) -> Unit
) : PalmDialog(ctx, "Set Time", height = 156) {
    private var s = if (untimed) 8 * 60 else start
    private var e = if (untimed) 9 * 60 else end
    private var editingEnd = false
    private val startBox = ctx.label("", bold = true)
    private val endBox = ctx.label("", bold = true)
    private val hourValues = if (Fmt.h24) (0..23).toList() else (1..12).toList()
    private val minValues = (0..55 step 5).toList()
    private val hourRows = mutableListOf<TextView>()
    private val minRows = mutableListOf<TextView>()
    private var hourTop = 0
    private var minTop = 0
    // True while an arrow button scrolls a list. draw() then keeps the window as set.
    private var scrolling = false
    private var ampm: PushGroup? = null

    init {
        val left = ctx.vbox()
        left.addView(ctx.label("Start Time:", size = Palm.text))
        left.addView(startBox, lp(MATCH))
        left.addView(ctx.label("End Time:", size = Palm.text), lp().apply { topMargin = ctx.pp(3) })
        left.addView(endBox, lp(MATCH))
        for ((i, b) in listOf(startBox, endBox).withIndex()) {
            b.setPadding(ctx.pp(3), ctx.pp(2.5f), ctx.pp(3), ctx.pp(2.5f))
            b.gravity = Gravity.CENTER
            b.setOnClickListener { editingEnd = i == 1; draw() }
        }

        val hourCol = column(hourRows,
            up = { scroll { hourTop = (hourTop - 1).coerceAtLeast(0) } },
            down = { scroll { hourTop = (hourTop + 1).coerceAtMost(hourValues.size - 6) } },
            pick = { pickHour(it) })
        if (!Fmt.h24) {
            val g = PushGroup(ctx, listOf("AM", "PM"), 0) { g -> pickAmPm(g.selected == 1) }
            ampm = g
            hourCol.addView(g.view, lp(MATCH).apply { topMargin = ctx.pp(2) })
        }
        val minCol = column(minRows,
            up = { scroll { minTop = (minTop - 1).coerceAtLeast(0) } },
            down = { scroll { minTop = (minTop + 1).coerceAtMost(minValues.size - 6) } },
            pick = { pickMinute(it) })

        val right = ctx.hbox()
        right.addView(hourCol, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = ctx.pp(2) })
        right.addView(minCol, LinearLayout.LayoutParams(0, WRAP, 1f))

        val form = ctx.hbox(Gravity.TOP)
        form.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = ctx.pp(4) })
        form.addView(right, LinearLayout.LayoutParams(0, WRAP, 1f))
        add(form)

        button("OK") { dismiss(); onPick(false, s, maxOf(e, s)) }
        button("Cancel") { dismiss() }
        button("No Time") { dismiss(); onPick(true, s, e) }
        draw()
    }

    // One list: an up arrow, 6 visible rows, a down arrow. A row click picks its value.
    private fun column(
        rows: MutableList<TextView>,
        up: () -> Unit,
        down: () -> Unit,
        pick: (Int) -> Unit
    ): LinearLayout = ctx.vbox().apply {
        addView(ctx.palmButton("▲") { up() }, lp(MATCH))
        repeat(6) {
            val r = ctx.label("", bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, ctx.pp(1), 0, ctx.pp(1))
                setOnClickListener { (tag as? Int)?.let(pick) }
            }
            rows.add(r)
            addView(r, lp(MATCH))
        }
        addView(ctx.palmButton("▼") { down() }, lp(MATCH))
    }

    private fun scroll(move: () -> Unit) {
        scrolling = true
        move()
        draw()
        scrolling = false
    }

    // Returns the first visible index that keeps the selected index in the 6-row window.
    private fun reveal(index: Int, top: Int): Int = when {
        index < 0 -> top
        index < top -> index
        index >= top + 6 -> index - 5
        else -> top
    }

    private fun cur() = if (editingEnd) e else s

    private fun set(v: Int) {
        val t = v.coerceIn(0, 24 * 60 - 1)
        if (editingEnd) {
            e = maxOf(t, s)
        } else {
            val dur = e - s
            s = t
            e = minOf(s + dur, 24 * 60 - 1)
        }
        draw()
    }

    private fun pickHour(h: Int) {
        val m = cur() % 60
        val hour = if (Fmt.h24) h else {
            val pm = cur() / 60 >= 12
            (h % 12) + if (pm) 12 else 0
        }
        set(hour * 60 + m)
    }

    private fun pickAmPm(pm: Boolean) {
        val h = cur() / 60
        val m = cur() % 60
        val nh = (h % 12) + if (pm) 12 else 0
        set(nh * 60 + m)
    }

    private fun pickMinute(m: Int) = set((cur() / 60) * 60 + m)

    private fun draw() {
        startBox.text = Fmt.time(s)
        endBox.text = Fmt.time(e)
        for ((i, b) in listOf(startBox, endBox).withIndex()) {
            val sel = (i == 1) == editingEnd
            b.background = ctx.box(if (sel) Palm.INK else Palm.paper, Palm.INK, 1f, 1f)
            b.setTextColor(if (sel) Palm.paper else Palm.INK)
        }
        val v = cur()
        val h = v / 60
        val shownHour = if (Fmt.h24) h else (if (h % 12 == 0) 12 else h % 12)
        val selMin = (v % 60) - (v % 60) % 5
        if (!scrolling) {
            hourTop = reveal(hourValues.indexOf(shownHour), hourTop)
            minTop = reveal(minValues.indexOf(selMin), minTop)
        }
        fill(hourRows, hourValues, hourTop, shownHour) { it.toString() }
        fill(minRows, minValues, minTop, selMin) { "%02d".format(it) }
        ampm?.select(if (h >= 12) 1 else 0)
    }

    private fun fill(rows: List<TextView>, values: List<Int>, top: Int, sel: Int, text: (Int) -> String) {
        rows.forEachIndexed { i, r ->
            val value = values.getOrNull(top + i)
            r.tag = value
            r.text = value?.let(text) ?: ""
            mark(r, value == sel)
        }
    }

    private fun mark(tv: TextView, sel: Boolean) {
        tv.background = if (sel) ctx.box(Palm.INK, Palm.INK, 0f, 0f) else null
        tv.setTextColor(if (sel) Palm.paper else Palm.INK)
    }
}

/** Palm "Change Repeat" form. Edits a copy of the event and returns it. */
class RepeatDialog(
    private val ctx: Context,
    source: Event,
    private val onPick: (Event) -> Unit
) : PalmDialog(ctx, "Change Repeat", height = 156) {
    private val ev = source.deepCopy()
    private val tabs: PushGroup
    private val area = ctx.vbox()
    private val summary = ctx.label("")
    private var every = ctx.numberField(maxOf(1, ev.every))

    init {
        val tabIndex = when (ev.repeat) {
            Repeat.DAY -> 1; Repeat.WEEK -> 2; Repeat.MONTH_DAY, Repeat.MONTH_DATE -> 3; Repeat.YEAR -> 4; else -> 0
        }
        tabs = PushGroup(ctx, listOf("None", "Day", "Week", "Month", "Year"), tabIndex) { g ->
            ev.repeat = when (g.selected) {
                1 -> Repeat.DAY; 2 -> Repeat.WEEK; 3 -> Repeat.MONTH_DATE; 4 -> Repeat.YEAR; else -> Repeat.NONE
            }
            if (ev.repeat == Repeat.WEEK && ev.weekDays == 0) ev.weekDays = 1 shl Recur.dow(LocalDate.ofEpochDay(ev.date))
            buildArea()
        }
        add(tabs.view)
        add(area, 4)
        add(summary.apply { setPadding(0, ctx.pp(4), 0, ctx.pp(1.5f)) })
        button("OK") {
            ev.every = every.text.toString().toIntOrNull()?.coerceIn(1, 99) ?: 1
            if (ev.repeat == Repeat.NONE) { ev.repeatEnd = -1; ev.exceptions.clear() }
            dismiss(); onPick(ev)
        }
        button("Cancel") { dismiss() }
        buildArea()
    }

    private fun unit(): String = when (ev.repeat) {
        Repeat.DAY -> "Day(s)"; Repeat.WEEK -> "Week(s)"; Repeat.YEAR -> "Year(s)"; else -> "Month(s)"
    }

    private fun buildArea() {
        area.removeAllViews()
        if (ev.repeat == Repeat.NONE) {
            area.addView(ctx.label("No Repeat").apply { gravity = Gravity.CENTER; setPadding(0, ctx.pp(6.5f), 0, ctx.pp(6.5f)) },
                lp(MATCH))
            updateSummary(); return
        }
        val r1 = ctx.hbox()
        r1.addView(ctx.label("Every:", bold = true).apply { minWidth = ctx.pp(32); gravity = Gravity.END })
        every = ctx.numberField(maxOf(1, ev.every)).apply {
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    ev.every = s.toString().toIntOrNull()?.coerceIn(1, 99) ?: 1; updateSummary()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        r1.addView(every, lp().apply { marginStart = ctx.pp(3) })
        r1.addView(ctx.label(unit()), lp().apply { marginStart = ctx.pp(2.5f) })
        area.addView(r1)

        val r2 = ctx.hbox()
        r2.addView(ctx.label("End on:", bold = true).apply { minWidth = ctx.pp(32); gravity = Gravity.END })
        val endText = if (ev.repeatEnd < 0) "No End Date" else Fmt.dowDate(ev.repeatEnd)
        r2.addView(ctx.popupTrigger(endText) { tv ->
            ctx.popupList(tv, listOf("No End Date", "Choose Date..."), if (ev.repeatEnd < 0) 0 else 1) { i ->
                if (i == 0) { ev.repeatEnd = -1; buildArea() }
                else DateDialog(ctx, "Ending On", if (ev.repeatEnd >= 0) ev.repeatEnd else ev.date) { d ->
                    ev.repeatEnd = maxOf(d, ev.date); buildArea()
                }.show()
            }
        }, lp().apply { marginStart = ctx.pp(3) })
        area.addView(r2, lp().apply { topMargin = ctx.pp(3) })

        if (ev.repeat == Repeat.WEEK) {
            area.addView(ctx.label("Repeat on:", bold = true), lp().apply { topMargin = ctx.pp(4) })
            val g = PushGroup(ctx, Fmt.dowLetter.toList(), -1, multi = true) { g ->
                var mask = 0
                for (i in 0..6) if (g.on[i]) mask = mask or (1 shl i)
                if (mask == 0) { mask = 1 shl Recur.dow(LocalDate.ofEpochDay(ev.date)); g.on[Recur.dow(LocalDate.ofEpochDay(ev.date))] = true; g.refresh() }
                ev.weekDays = mask; updateSummary()
            }
            for (i in 0..6) g.on[i] = (ev.weekDays and (1 shl i)) != 0
            g.refresh()
            area.addView(g.view, lp(MATCH).apply { topMargin = ctx.pp(1.5f) })
        }
        if (ev.repeat == Repeat.MONTH_DAY || ev.repeat == Repeat.MONTH_DATE) {
            val r3 = ctx.hbox()
            r3.addView(ctx.label("Repeat by:", bold = true).apply { minWidth = ctx.pp(32); gravity = Gravity.END })
            val g = PushGroup(ctx, listOf("Day", "Date"), if (ev.repeat == Repeat.MONTH_DAY) 0 else 1) { g ->
                ev.repeat = if (g.selected == 0) Repeat.MONTH_DAY else Repeat.MONTH_DATE; updateSummary()
            }
            r3.addView(g.view, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ctx.pp(3) })
            area.addView(r3, lp(MATCH).apply { topMargin = ctx.pp(4) })
        }
        updateSummary()
    }

    private fun updateSummary() {
        summary.text = if (ev.repeat == Repeat.NONE) "" else Recur.describe(ev)
    }
}

/** Palm Note view: a full page of text for one record. */
class NoteDialog(
    ctx: Context,
    title: String,
    text: String,
    onDone: (String) -> Unit,
    onDelete: (() -> Unit)?
) : PalmDialog(ctx, title, height = 160) {
    init {
        val f = ctx.field(text, "Write a note", multiLine = true).apply {
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
        }
        add(f)
        button("Done") { dismiss(); onDone(f.text.toString()) }
        if (onDelete != null) button("Delete...") {
            ctx.ask("Delete Note", "Delete this note?", "Yes" to { dismiss(); onDelete() }, "No" to {})
        }
    }
}
