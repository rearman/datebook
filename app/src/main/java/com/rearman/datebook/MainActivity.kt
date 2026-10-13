package com.rearman.datebook

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate

/** The Date Book app. [TodoActivity] is the To Do List app; both use the same records. */
open class MainActivity : Activity() {
    companion object {
        const val EXTRA_DAY = "day"
        const val EXTRA_EVENT = "event"
        const val EXTRA_SELECT = "select"
        const val EXTRA_TODO = "todo"
        private const val REQ_EXPORT = 11
        private const val REQ_IMPORT = 12
        const val DAY = 0
        const val WEEK = 1
        const val MONTH = 2
        const val AGENDA = 3
        const val TODO = 4
    }

    lateinit var store: Store
    lateinit var prefs: Prefs
    var day: Long = LocalDate.now().toEpochDay()
    private var mode = DAY
    /** True in the To Do List app. */
    protected open val isTodoApp = false
    /** Private records the user chose to see while records are masked ("e12", "t5"). */
    val revealed = mutableSetOf<String>()

    private lateinit var root: LinearLayout
    private var selected: Occ? = null
    private var selectedTodo: Todo? = null
    private var focusKey: String? = null
    private var lastEdit: EditText? = null
    private var weekSel: Occ? = null
    private var pendingExport: String? = null
    private var afterExport: (() -> Unit)? = null

    private val today: Long get() = LocalDate.now().toEpochDay()

    // ---------------- lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.get(this)
        prefs = Prefs(this)
        mode = if (isTodoApp) TODO else prefs.lastView
        root = vbox().apply { isFocusableInTouchMode = true }
        setContentView(root)
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Alarms.ensureChannel(this)
    }

    override fun onResume() {
        super.onResume()
        render()
        Alarms.scheduleNext(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        render()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.hasExtra(EXTRA_TODO)) {
            val tid = i.getLongExtra(EXTRA_TODO, 0)
            selectedTodo = store.todos.firstOrNull { it.id == tid }
            if (selectedTodo != null) prefs.todoCategory = -1
            i.removeExtra(EXTRA_TODO)
        }
        if (!i.hasExtra(EXTRA_DAY)) return
        day = i.getLongExtra(EXTRA_DAY, today)
        mode = DAY
        val sel = i.getLongExtra(EXTRA_SELECT, 0)
        if (sel != 0L) store.event(sel)?.let { selected = Occ(it, day) }
        i.removeExtra(EXTRA_SELECT)
        val id = i.getLongExtra(EXTRA_EVENT, 0)
        if (id != 0L) {
            Alarms.cancelRemind(this, id, day)
            store.event(id)?.let { e ->
                getSystemService(android.app.NotificationManager::class.java).cancel(Alarms.notifId(e, day))
                selected = Occ(e, day)
            }
        }
        i.removeExtra(EXTRA_DAY)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (mode) {
            TODO, DAY -> super.onBackPressed()
            else -> { mode = DAY; render() }
        }
    }

    // ---------------- shared helpers used by the views and dialogs ----------------

    fun dayOccs(d: Long): List<Occ> =
        store.dayEvents(d).filter { !(prefs.security == 2 && it.event.isPrivate) }

    fun isMasked(e: Event) = prefs.security == 1 && e.isPrivate && !revealed.contains("e${e.id}")
    private fun isMasked(t: Todo) = prefs.security == 1 && t.isPrivate && !revealed.contains("t${t.id}")

    fun revealAsk(e: Event) =
        ask("Private Record", "Show this private record?", "Show" to { revealed.add("e${e.id}"); render() }, "Cancel" to {})

    private fun revealAsk(t: Todo) =
        ask("Private Record", "Show this private record?", "Show" to { revealed.add("t${t.id}"); render() }, "Cancel" to {})

    fun goDay(d: Long, occ: Occ?) {
        day = d; mode = DAY; prefs.lastView = DAY; selected = occ
        render()
    }

    fun editEvent(o: Occ) = EventDetails(this, o.event, o.day, false).show()

    /** Saves changes made in Event Details. Asks Current/All for repeating events. */
    fun saveEventEdit(orig: Event, occDay: Long, work: Event, isNew: Boolean, after: (Occ) -> Unit) {
        if (isNew) {
            work.id = 0
            store.save(work); selected = Occ(work, work.date); day = work.date
            changed(); after(Occ(work, work.date)); return
        }
        val origView = orig.deepCopy().apply { date = occDay }
        if (work == origView) { after(Occ(orig, occDay)); return }
        if (!orig.repeats) {
            work.id = orig.id
            store.save(work); selected = Occ(work, work.date); day = work.date
            changed(); after(Occ(work, work.date)); return
        }
        ask(
            "Repeating Event", "Apply the changes to the current event only, or to all occurrences?",
            "Current" to {
                orig.exceptions.add(occDay); store.save(orig)
                val n = work.deepCopy().apply {
                    id = 0; repeat = Repeat.NONE; repeatEnd = -1; exceptions.clear()
                }
                store.save(n); selected = Occ(n, n.date); day = n.date
                changed(); after(Occ(n, n.date))
            },
            "All" to {
                val m = work.deepCopy().apply {
                    id = orig.id
                    date = if (repeat == Repeat.NONE) work.date else orig.date + (work.date - occDay)
                }
                store.save(m); selected = Occ(m, work.date); day = work.date
                changed(); after(Occ(m, work.date))
            },
            "Cancel" to {}
        )
    }

    fun deleteEvent(e: Event, d: Long) {
        val done = { selected = null; changed() }
        if (e.repeats) {
            ask(
                "Delete Repeating Event", "Delete the current event, all future events, or all events?",
                "Current" to { e.exceptions.add(d); store.save(e); done() },
                "Future" to {
                    if (d <= e.date) store.delete(e) else { e.repeatEnd = d - 1; store.save(e) }
                    done()
                },
                "All" to { store.delete(e); done() },
                "Cancel" to {}
            )
        } else {
            ask("Delete Event", "Delete selected event?", "Yes" to { store.delete(e); done() }, "No" to {})
        }
    }

    fun openNote(o: Occ) {
        val e = o.event
        NoteDialog(
            this, "Note: " + e.title.ifBlank { "(no description)" }.take(24), e.note,
            { t -> e.note = t; store.save(e); render() },
            if (e.note.isNotEmpty()) ({ e.note = ""; store.save(e); render() }) else null
        ).show()
    }

    fun saveTodo(t: Todo) { store.save(t); selectedTodo = t; render() }

    fun deleteTodo(t: Todo) =
        ask("Delete To Do", "Delete selected To Do item?", "Yes" to { store.delete(t); selectedTodo = null; render() }, "No" to {})

    fun openTodoNote(t: Todo) {
        NoteDialog(
            this, "Note: " + t.title.ifBlank { "(no description)" }.take(24), t.note,
            { s -> t.note = s; store.save(t); render() },
            if (t.note.isNotEmpty()) ({ t.note = ""; store.save(t); render() }) else null
        ).show()
    }

    /** Switches to the To Do List app, as the Palm To Do button does. */
    fun openTodoApp(todoId: Long?) {
        val i = Intent(this, TodoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (todoId != null) i.putExtra(EXTRA_TODO, todoId)
        startActivity(i)
    }

    /** Switches to the Date Book app. */
    fun openDateBook(d: Long?, eventId: Long?) {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (d != null) i.putExtra(EXTRA_DAY, d)
        if (eventId != null) i.putExtra(EXTRA_SELECT, eventId)
        startActivity(i)
    }

    fun changed() {
        Alarms.scheduleNext(this)
        render()
    }

    fun pickDue(anchor: View, cur: Long, cb: (Long) -> Unit) {
        val items = listOf("Today", "Tomorrow", "One week later", "No Date", "Choose Date...")
        popupList(anchor, items, if (cur < 0) 3 else -1) { i ->
            when (i) {
                0 -> cb(today)
                1 -> cb(today + 1)
                2 -> cb(today + 7)
                3 -> cb(-1)
                else -> DateDialog(this, "Due Date", if (cur >= 0) cur else today) { d -> cb(d) }.show()
            }
        }
    }

    fun pickCategory(anchor: View, cur: Int, includeAll: Boolean, cb: (Int) -> Unit) {
        val ids = mutableListOf<Int>()
        val names = mutableListOf<String>()
        if (includeAll) { ids.add(-1); names.add("All") }
        ids.add(0); names.add("Unfiled")
        store.categories.forEach { ids.add(it.id); names.add(it.name) }
        names.add("Edit Categories...")
        popupList(anchor, names, ids.indexOf(cur)) { i ->
            if (i < ids.size) cb(ids[i]) else showEditCategories()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(root.windowToken, 0)
    }

    /** Ends any edit in progress, so that typed text is saved. */
    private fun endEdit() {
        focusKey = null
        root.requestFocus()
        hideKeyboard()
    }

    // ---------------- screen ----------------

    fun render() {
        Palm.apply(prefs)
        Fmt.h24 = DateFormat.is24HourFormat(this)
        root.removeAllViews()
        root.setBackgroundColor(Palm.paper)
        window.decorView.setBackgroundColor(Palm.paper)
        lastEdit = null

        val header = hbox()
        header.setPadding(0, dp(4), dp(4), 0)
        val content = FrameLayout(this)
        val cmds = hbox()
        cmds.setPadding(dp(6), dp(6), dp(6), dp(6))

        when (mode) {
            DAY -> buildDay(header, content, cmds)
            WEEK -> buildWeek(header, content, cmds)
            MONTH -> buildMonth(header, content, cmds)
            AGENDA -> buildAgenda(header, content, cmds)
            else -> buildTodo(header, content, cmds)
        }

        root.addView(header, lp(MATCH))
        root.addView(View(this).apply { setBackgroundColor(Palm.INK) }, LP(MATCH, dp(2)))
        root.addView(content, LP(MATCH, 0, 1f))
        root.addView(cmds, lp(MATCH))
        root.addView(silkscreen(), LP(MATCH, dp(50)))
    }

    private fun tab(h: LinearLayout, title: String) {
        val t = label(title, bold = true).apply {
            setTextColor(Palm.paper)
            val r = dp(7).toFloat()
            background = GradientDrawable().apply {
                setColor(Palm.INK); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(10), dp(4), dp(10), dp(3))
            setOnClickListener { showMenu() }
        }
        h.addView(t, lp().apply { marginStart = dp(2); gravity = Gravity.BOTTOM })
        h.addView(View(this), LP(0, 1, 1f))
    }

    private fun arrow(left: Boolean, onClick: () -> Unit): TextView =
        label(if (left) "◀" else "▶", bold = true).apply {
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { endEdit(); onClick() }
        }

    private fun viewIcons(cmds: LinearLayout) {
        for (k in 0..3) {
            cmds.addView(ViewIcon(this, k, mode == k) {
                endEdit(); mode = k; prefs.lastView = k; render()
            }, lp().apply { marginEnd = dp(1) })
        }
        cmds.addView(View(this), LP(0, 1, 1f))
    }

    private fun cmd(cmds: LinearLayout, text: String, onClick: () -> Unit) {
        cmds.addView(palmButton(text, onClick).apply {
            setPadding(dp(9), dp(5), dp(9), dp(5)); minWidth = dp(40)
        }, lp().apply { marginStart = dp(5) })
    }

    /** The silk-screened buttons below a Palm screen. */
    private fun silkscreen(): LinearLayout {
        val bar = hbox().apply { setBackgroundColor(Palm.SILK); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        fun key(text: String, onClick: () -> Unit) {
            bar.addView(TextView(this).apply {
                this.text = text
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                background = box(Palm.SILK, 0xFF9A9A9A.toInt(), 1.2f, 18f)
                setOnClickListener { onClick() }
            }, LP(0, MATCH, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        key("Menu") { showMenu() }
        key("Find") { endEdit(); showFind() }
        return bar
    }

    // ---------------- Day view ----------------

    private class Row(val occ: Occ?, val min: Int, val untimed: Boolean)

    private fun buildDay(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
        tab(h, Fmt.dayTitle(day))
        val weekStart = day - Recur.dow(Fmt.ld(day))
        h.addView(arrow(true) { day -= 7; render() })
        for (i in 0..6) {
            val d = weekStart + i
            h.addView(label(Fmt.dowLetter[i], bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, dp(5))
                if (d == day) { background = box(Palm.INK, Palm.INK, 0f, 2f); setTextColor(Palm.paper) }
                else if (d == today) background = box(Color.TRANSPARENT, Palm.INK, 1f, 2f)
                setOnClickListener { endEdit(); day = d; selected = null; render() }
            }, LP(dp(24), WRAP))
        }
        h.addView(arrow(false) { day += 7; render() })

        val occs = dayOccs(day)
        val timed = occs.filter { !it.event.untimed }
        val rows = mutableListOf<Row>()
        occs.filter { it.event.untimed }.forEach { rows.add(Row(it, -1, true)) }
        val list = mutableListOf<Row>()
        timed.forEach { list.add(Row(it, it.event.start, false)) }
        if (!prefs.compress) {
            for (hr in prefs.dayStart until prefs.dayEnd) {
                val a = hr * 60; val b = a + 60
                if (timed.none { it.event.start < b && maxOf(it.event.end, it.event.start + 1) > a }) list.add(Row(null, a, false))
            }
        }
        rows.addAll(list.sortedWith(compareBy<Row>({ it.min }, { if (it.occ == null) 1 else 0 })))
        if (rows.isEmpty()) rows.add(Row(null, prefs.dayStart * 60, false))

        val (laneOf, nLanes) = lanes(timed)
        val barW = if (prefs.timeBars) dp(6) + nLanes * dp(5) else 0

        val col = vbox()
        var focusView: EditText? = null
        rows.forEachIndexed { idx, row ->
            val rowEnd = rows.drop(idx + 1).firstOrNull { !it.untimed }?.min
                ?: maxOf(row.min + 60, row.occ?.event?.end ?: 0)
            val r = hbox(Gravity.TOP)
            if (barW > 0) {
                r.addView(TimeBar(this, if (row.untimed) emptyList() else timed, laneOf, row.min, rowEnd),
                    LP(barW, MATCH))
            }
            val occ = row.occ
            val e = occ?.event
            val timeText = when {
                row.untimed -> "•"
                else -> Fmt.timeShort(row.min)
            }
            r.addView(label(timeText, bold = true).apply {
                gravity = Gravity.END
                setPadding(dp(2), dp(9), dp(6), dp(9))
                setTextColor(if (e == null) Palm.GRAY else Palm.INK)
                setOnClickListener { endEdit(); timeTapped(row) }
            }, LP(dp(50), WRAP))

            if (e != null && isMasked(e)) {
                r.addView(label("").apply {
                    background = box(Palm.LIGHT, Palm.LIGHT, 0f, 2f)
                    setOnClickListener { revealAsk(e) }
                }, LP(0, dp(22), 1f).apply { topMargin = dp(8); marginEnd = dp(8) })
            } else {
                val ed = rowEdit(e?.title ?: "")
                val key = if (e != null) "e${e.id}" else "s${row.min}"
                ed.tag = key
                ed.setOnFocusChangeListener { _, has ->
                    if (has) { selected = row.occ; focusKey = key; lastEdit = ed }
                    else commitDayRow(row, ed.text.toString())
                }
                r.addView(ed, LP(0, WRAP, 1f))
                if (key == focusKey) focusView = ed
                if (e != null && occ != null) {
                    val icons = hbox().apply { setPadding(0, dp(9), dp(4), 0) }
                    if (e.note.isNotEmpty()) icons.addView(IconView(this, IconView.NOTE).apply {
                        setOnClickListener { endEdit(); openNote(occ) }
                    }, lp().apply { marginStart = dp(3) })
                    if (e.alarm) icons.addView(IconView(this, IconView.ALARM).apply {
                        setOnClickListener { endEdit(); editEvent(occ) }
                    }, lp().apply { marginStart = dp(3) })
                    if (e.repeats) icons.addView(IconView(this, IconView.REPEAT).apply {
                        setOnClickListener { endEdit(); editEvent(occ) }
                    }, lp().apply { marginStart = dp(3) })
                    r.addView(icons)
                }
            }
            col.addView(r, lp(MATCH))
            col.addView(DottedLine(this), LP(MATCH, dp(1)))
        }
        val sv = ScrollView(this).apply { addView(col); isFillViewport = true }
        content.addView(sv)
        focusView?.let { f ->
            f.post { f.requestFocus(); f.setSelection(f.text.length) }
        }

        viewIcons(cmds)
        cmd(cmds, "New") { endEdit(); newEvent(null) }
        cmd(cmds, "Details") {
            endEdit()
            root.post {
                val s = selected
                if (s != null && store.event(s.event.id) != null) editEvent(Occ(store.event(s.event.id)!!, s.day))
                else ask("Select Event", "Tap an event first, then tap Details.", "OK" to {})
            }
        }
        cmd(cmds, "Go to") { endEdit(); DateDialog(this, "Go to Date", day) { d -> day = d; selected = null; render() }.show() }
    }

    private fun rowEdit(text: String): EditText = EditText(this).apply {
        setText(text)
        background = null
        setTextColor(Palm.INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Palm.textSp)
        setPadding(dp(2), dp(9), dp(4), dp(9))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setHorizontallyScrolling(false)
        maxLines = 20
        imeOptions = EditorInfo.IME_ACTION_DONE
        setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { endEdit(); true } else false
        }
    }

    private fun newEventAt(start: Int, end: Int, untimed: Boolean): Event =
        Event(
            date = day, start = start, end = end.coerceAtMost(24 * 60 - 1), untimed = untimed,
            alarm = prefs.presetOn, alarmAdvance = prefs.presetAdvance, alarmUnit = prefs.presetUnit
        )

    /** Palm "New": choose a time, then type the description on the new line. */
    private fun newEvent(start: Int?) {
        val s = start ?: if (day == today) minOf((java.time.LocalTime.now().hour + 1) * 60, 23 * 60) else prefs.dayStart * 60
        TimeDialog(this, false, s, s + 60) { u, a, b ->
            val e = newEventAt(a, b, u)
            store.save(e)
            selected = Occ(e, day)
            focusKey = "e${e.id}"
            changed()
            showKeyboardSoon()
        }.show()
    }

    private fun showKeyboardSoon() {
        root.postDelayed({
            currentFocus?.let { getSystemService(InputMethodManager::class.java).showSoftInput(it, 0) }
        }, 250)
    }

    private fun timeTapped(row: Row) {
        val o = row.occ
        if (o == null) { newEvent(row.min); return }
        if (isMasked(o.event)) { revealAsk(o.event); return }
        val e = o.event
        TimeDialog(this, e.untimed, e.start, e.end) { u, s, en ->
            val work = e.deepCopy().apply { date = o.day; untimed = u; start = s; end = en }
            saveEventEdit(e, o.day, work, false) {}
        }.show()
    }

    private fun commitDayRow(row: Row, raw: String) {
        val text = raw.trim()
        val o = row.occ
        if (o == null) {
            if (text.isEmpty()) return
            val e = newEventAt(row.min, row.min + 60, false).apply { title = text }
            store.save(e)
            selected = Occ(e, day)
            root.post { changed() }
            return
        }
        val e = store.event(o.event.id) ?: return
        if (text.isEmpty() && e.note.isEmpty() && !e.repeats) {
            store.delete(e); if (selected?.event?.id == e.id) selected = null
            root.post { changed() }
            return
        }
        if (text.isEmpty() || text == e.title) {
            if (text.isEmpty()) root.post { render() }
            return
        }
        if (!e.repeats) {
            e.title = text; store.save(e)
            return
        }
        val work = e.deepCopy().apply { date = o.day; title = text }
        root.post { saveEventEdit(e, o.day, work, false) {} }
    }

    // ---------------- Week view ----------------

    private fun buildWeek(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
        val ws = day - Recur.dow(Fmt.ld(day))
        tab(h, Fmt.dayTitle(ws))
        h.addView(arrow(true) { day -= 7; weekSel = null; render() })
        h.addView(arrow(false) { day += 7; weekSel = null; render() })

        val col = vbox()
        val desc = label("Tap an event to see it. Tap it again to open its day.", size = Palm.textSp - 2).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setTextColor(Palm.GRAY)
        }
        col.addView(desc, lp(MATCH))
        val head = hbox().apply { setPadding(dp(28), 0, 0, dp(2)) }
        for (i in 0..6) {
            val d = ws + i
            val untimed = dayOccs(d).count { it.event.untimed }
            val cell = vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { goDay(d, null) }
                if (d == today) background = box(Palm.INK, Palm.INK, 0f, 3f)
            }
            val fg = if (d == today) Palm.paper else Palm.INK
            cell.addView(label(Fmt.dowLetter[i], bold = true, size = Palm.textSp - 2).apply { setTextColor(fg) })
            cell.addView(label(Fmt.ld(d).dayOfMonth.toString(), bold = true).apply { setTextColor(fg) })
            cell.addView(label("◆".repeat(minOf(untimed, 3)), size = 9f).apply { setTextColor(fg) })
            head.addView(cell, LP(0, WRAP, 1f))
        }
        col.addView(head, lp(MATCH))
        val grid = WeekGrid(this, this, ws) { o ->
            weekSel = o
            if (o != null) {
                desc.setTextColor(Palm.INK)
                desc.text = "${Fmt.dowDate(o.day)}  ${Fmt.range(o.event)}\n${o.event.title.ifBlank { "(no description)" }}"
            }
        }
        val sv = ScrollView(this).apply { addView(grid) }
        col.addView(sv, LP(MATCH, 0, 1f))
        content.addView(col)
        sv.post { sv.scrollTo(0, (prefs.dayStart * dp(34))) }

        viewIcons(cmds)
        cmd(cmds, "Go to") { DateDialog(this, "Go to Date", day) { d -> day = d; render() }.show() }
    }

    // ---------------- Month view ----------------

    private fun buildMonth(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
        val d = Fmt.ld(day)
        tab(h, "${Fmt.monthShort[d.monthValue - 1]} ${d.year}")
        h.addView(arrow(true) { day = Fmt.ld(day).minusMonths(1).toEpochDay(); render() })
        h.addView(arrow(false) { day = Fmt.ld(day).plusMonths(1).toEpochDay(); render() })
        content.addView(MonthGrid(this, this, d.year, d.monthValue),
            FrameLayout.LayoutParams(MATCH, MATCH))
        viewIcons(cmds)
        cmd(cmds, "Go to") { DateDialog(this, "Go to Date", day) { x -> day = x; render() }.show() }
    }

    // ---------------- Agenda view ----------------

    private fun agendaTodos(): List<Todo> {
        val cat = prefs.todoCategory
        // Items due on or before this day. Completed items show only when the
        // To Do List shows them, and only on the day they were due or completed.
        return sortTodos(store.todos.filter {
            (cat < 0 || it.category == cat) &&
                (if (it.done) prefs.showCompleted && it.due == day else it.due <= day) &&
                !(prefs.onlyDue && it.due < 0) &&
                !(prefs.security == 2 && it.isPrivate)
        })
    }

    private fun buildAgenda(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
        tab(h, "Agenda")
        h.addView(arrow(true) { day -= 1; render() })
        h.addView(arrow(false) { day += 1; render() })

        val col = vbox().apply { setPadding(dp(10), dp(6), dp(10), dp(10)) }
        val ld = Fmt.ld(day)
        col.addView(label(
            "${Fmt.dowLong[Recur.dow(ld)]}, ${ld.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${ld.dayOfMonth}, ${ld.year}",
            bold = true, size = Palm.textSp + 1
        ).apply { setOnClickListener { goDay(day, null) } })

        val occs = dayOccs(day)
        if (occs.isEmpty()) col.addView(label("No appointments", size = Palm.textSp - 1).apply {
            setTextColor(Palm.GRAY); setPadding(0, dp(6), 0, dp(6))
        })
        for (o in occs) {
            val r = hbox(Gravity.TOP).apply {
                setPadding(0, dp(5), 0, dp(5))
                setOnClickListener { if (isMasked(o.event)) revealAsk(o.event) else goDay(day, o) }
            }
            r.addView(label(if (o.event.untimed) "•" else Fmt.timeShort(o.event.start), bold = true).apply {
                gravity = Gravity.END; setPadding(0, 0, dp(8), 0)
            }, LP(dp(48), WRAP))
            if (isMasked(o.event)) r.addView(label("").apply { background = box(Palm.LIGHT, Palm.LIGHT, 0f, 2f) },
                LP(0, dp(18), 1f))
            else r.addView(label(o.event.title.ifBlank { "(no description)" }), LP(0, WRAP, 1f))
            col.addView(r, lp(MATCH))
        }

        col.addView(View(this).apply { setBackgroundColor(Palm.INK) },
            LP(MATCH, dp(2)).apply { topMargin = dp(10); bottomMargin = dp(4) })
        val th = hbox()
        th.addView(label("To Do", bold = true), LP(0, WRAP, 1f))
        th.addView(popupTrigger(categoryLabel(prefs.todoCategory)) { tv ->
            pickCategory(tv, prefs.todoCategory, includeAll = true) { id -> prefs.todoCategory = id; render() }
        })
        col.addView(th, lp(MATCH))
        val tds = agendaTodos()
        if (tds.isEmpty()) col.addView(label("No items due", size = Palm.textSp - 1).apply {
            setTextColor(Palm.GRAY); setPadding(0, dp(6), 0, dp(6))
        })
        for (t in tds) {
            val r = hbox(Gravity.TOP)
            r.addView(checkBox("", t.done) { b -> completeTodo(t, b) }, lp().apply { topMargin = dp(2) })
            if (prefs.showPriorities) r.addView(todoPriorityLabel(t))
            if (isMasked(t)) r.addView(label("").apply {
                background = box(Palm.LIGHT, Palm.LIGHT, 0f, 2f); setOnClickListener { revealAsk(t) }
            }, LP(0, dp(22), 1f).apply { topMargin = dp(8); marginEnd = dp(8) })
            else r.addView(label(t.title.ifBlank { "(no description)" }).apply {
                setPadding(dp(2), dp(9), dp(4), dp(9))
                setOnClickListener { openTodoApp(t.id) }
            }, LP(0, WRAP, 1f))
            if (prefs.showDueDates) r.addView(todoDueLabel(t, day))
            if (prefs.showCategories) r.addView(todoCategoryLabel(t))
            col.addView(r, lp(MATCH))
        }
        content.addView(ScrollView(this).apply { addView(col) })
        viewIcons(cmds)
        cmd(cmds, "Go to") { DateDialog(this, "Go to Date", day) { d -> day = d; render() }.show() }
    }

    // ---------------- To Do List ----------------

    private fun categoryLabel(id: Int) = if (id < 0) "All" else store.categoryName(id)

    private fun sortTodos(list: List<Todo>): List<Todo> {
        val dueKey = { t: Todo -> if (t.due < 0) Long.MAX_VALUE else t.due }
        val catKey = { t: Todo -> store.categoryName(t.category).lowercase() }
        val cmp: Comparator<Todo> = when (prefs.sortMode) {
            1 -> compareBy<Todo>({ dueKey(it) }, { it.priority }, { it.id })
            2 -> compareBy<Todo>({ catKey(it) }, { it.priority }, { dueKey(it) }, { it.id })
            3 -> compareBy<Todo>({ catKey(it) }, { dueKey(it) }, { it.priority }, { it.id })
            else -> compareBy<Todo>({ it.priority }, { dueKey(it) }, { it.id })
        }
        return list.sortedWith(cmp)
    }

    private fun visibleTodos(): List<Todo> {
        val cat = prefs.todoCategory
        return sortTodos(store.todos.filter {
            (cat < 0 || it.category == cat) &&
                (prefs.showCompleted || !it.done) &&
                (!prefs.onlyDue || (it.due in 0..today)) &&
                !(prefs.security == 2 && it.isPrivate)
        })
    }

    /** Due date column, as set in Show options. One line; it sizes to its text. */
    private fun todoDueLabel(t: Todo, ref: Long): TextView {
        val late = !t.done && t.due in 0 until ref
        val txt = if (t.due < 0) "–" else {
            val d = Fmt.ld(t.due); "${d.monthValue}/${d.dayOfMonth}" + if (late) "!" else ""
        }
        return label(txt, bold = late).apply {
            gravity = Gravity.END
            maxLines = 1
            setPadding(dp(6), dp(9), dp(4), dp(9))
            setOnClickListener { tv -> endEdit(); pickDue(tv, t.due) { d -> t.due = d; saveTodo(t) } }
        }
    }

    /** Category column, as set in Show options. One line, cut with "..." when long. */
    private fun todoCategoryLabel(t: Todo): TextView =
        label(store.categoryName(t.category), size = Palm.textSp - 2).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(96)
            setPadding(dp(6), dp(10), dp(8), dp(9))
            setOnClickListener { tv ->
                endEdit()
                pickCategory(tv, t.category, includeAll = false) { id -> t.category = id; saveTodo(t) }
            }
        }

    private fun todoPriorityLabel(t: Todo): TextView =
        label("${t.priority}", bold = true).apply {
            setPadding(dp(2), dp(9), dp(6), dp(9))
            setOnClickListener { tv ->
                endEdit()
                popupList(tv, listOf("1", "2", "3", "4", "5"), t.priority - 1) { i -> t.priority = i + 1; saveTodo(t) }
            }
        }

    private fun completeTodo(t: Todo, done: Boolean) {
        t.done = done
        if (done && prefs.recordCompletion) t.due = today
        store.save(t)
        root.post { render() }
    }

    private fun buildTodo(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
        tab(h, "To Do List")
        h.addView(popupTrigger(categoryLabel(prefs.todoCategory)) { tv ->
            endEdit()
            pickCategory(tv, prefs.todoCategory, includeAll = true) { id -> prefs.todoCategory = id; render() }
        })

        val col = vbox()
        val items = visibleTodos()
        var focusView: EditText? = null
        if (items.isEmpty()) col.addView(label("No items. Tap New to add one.").apply {
            setTextColor(Palm.GRAY); setPadding(dp(12), dp(16), dp(12), dp(16))
        })
        for (t in items) {
            val r = hbox(Gravity.TOP)
            r.addView(checkBox("", t.done) { b -> endEdit(); completeTodo(t, b) }, lp().apply { topMargin = dp(2) })
            if (prefs.showPriorities) r.addView(todoPriorityLabel(t))
            if (isMasked(t)) {
                r.addView(label("").apply {
                    background = box(Palm.LIGHT, Palm.LIGHT, 0f, 2f)
                    setOnClickListener { revealAsk(t) }
                }, LP(0, dp(22), 1f).apply { topMargin = dp(8); marginEnd = dp(8) })
            } else {
                val ed = rowEdit(t.title)
                val key = "t${t.id}"
                ed.tag = key
                ed.setOnFocusChangeListener { _, has ->
                    if (has) { selectedTodo = t; focusKey = key; lastEdit = ed }
                    else commitTodo(t, ed.text.toString())
                }
                if (key == focusKey) focusView = ed
                r.addView(ed, LP(0, WRAP, 1f))
            }
            if (prefs.showDueDates) r.addView(todoDueLabel(t, today))
            if (prefs.showCategories) r.addView(todoCategoryLabel(t))
            col.addView(r, lp(MATCH))
            col.addView(DottedLine(this), LP(MATCH, dp(1)))
        }
        content.addView(ScrollView(this).apply { addView(col); isFillViewport = true })
        focusView?.let { f -> f.post { f.requestFocus(); f.setSelection(f.text.length) } }

        cmd(cmds, "New") { endEdit(); newTodo() }
        cmd(cmds, "Details") {
            endEdit()
            root.post {
                val t = selectedTodo?.let { s -> store.todos.firstOrNull { it.id == s.id } }
                if (t != null) TodoDetails(this, t).show()
                else ask("Select Item", "Tap an item first, then tap Details.", "OK" to {})
            }
        }
        cmd(cmds, "Show...") { endEdit(); showShowOptions() }
        cmds.addView(View(this), LP(0, 1, 1f))
    }

    private fun newTodo() {
        val cat = if (prefs.todoCategory > 0) prefs.todoCategory else 0
        val t = Todo(priority = 1, category = cat)
        store.save(t)
        selectedTodo = t
        focusKey = "t${t.id}"
        if (!prefs.showCompleted || prefs.onlyDue) {
            // A new item has no due date, so it would be hidden by "Show Only Due Items".
            prefs.onlyDue = false
        }
        render()
        showKeyboardSoon()
    }

    private fun commitTodo(t: Todo, raw: String) {
        val text = raw.trim()
        if (text.isEmpty() && t.note.isEmpty()) {
            store.delete(t); if (selectedTodo?.id == t.id) selectedTodo = null
            root.post { render() }
            return
        }
        if (text.isEmpty() || text == t.title) return
        t.title = text
        store.save(t)
    }

    // ---------------- Menus ----------------

    private fun showMenu() {
        val todo = mode == TODO
        val record: List<Pair<String, () -> Unit>> = if (todo) listOf(
            "New Item" to { newTodo() },
            "Delete Item..." to { selectedTodo?.let { deleteTodo(it) } ?: toast("Select an item first.") },
            "Attach Note" to { selectedTodo?.let { openTodoNote(it) } ?: toast("Select an item first.") },
            "Delete Note..." to {
                selectedTodo?.let { t ->
                    if (t.note.isEmpty()) toast("This item has no note.")
                    else ask("Delete Note", "Delete the note?", "Yes" to { t.note = ""; store.save(t); render() }, "No" to {})
                } ?: toast("Select an item first.")
            },
            "Purge..." to { showPurgeTodos() },
            "Export Backup..." to { exportFile("datebook-backup", store.exportJson()) },
            "Import Backup..." to { importFile() }
        ) else listOf(
            "New Event" to { if (mode != DAY) { mode = DAY; render() }; newEvent(null) },
            "Delete Event..." to { selected?.let { deleteEvent(it.event, it.day) } ?: toast("Select an event first.") },
            "Attach Note" to { selected?.let { openNote(it) } ?: toast("Select an event first.") },
            "Delete Note..." to {
                selected?.let { o ->
                    if (o.event.note.isEmpty()) toast("This event has no note.")
                    else ask("Delete Note", "Delete the note?", "Yes" to { o.event.note = ""; store.save(o.event); render() }, "No" to {})
                } ?: toast("Select an event first.")
            },
            "Purge..." to { showPurgeEvents() },
            "Export Backup..." to { exportFile("datebook-backup", store.exportJson()) },
            "Import Backup..." to { importFile() }
        )
        val ed = lastEdit
        val edit: List<Pair<String, () -> Unit>> = listOf(
            "Undo" to { textOp(ed, android.R.id.undo) },
            "Cut" to { textOp(ed, android.R.id.cut) },
            "Copy" to { textOp(ed, android.R.id.copy) },
            "Paste" to { textOp(ed, android.R.id.paste) },
            "Select All" to { textOp(ed, android.R.id.selectAll) },
            "Keyboard" to {
                ed?.let { it.requestFocus(); getSystemService(InputMethodManager::class.java).showSoftInput(it, 0) }
                    ?: toast("Tap a line of text first.")
            }
        )
        val options: List<Pair<String, () -> Unit>> = if (todo) listOf(
            "Font..." to { showFont() },
            "Edit Categories..." to { showEditCategories() },
            "Security..." to { showSecurity() },
            "About To Do List" to { showAbout(true) }
        ) else listOf(
            "Font..." to { showFont() },
            "Preferences..." to { showPreferences() },
            "Display Options..." to { showDisplayOptions() },
            "Security..." to { showSecurity() },
            "About Date Book" to { showAbout(false) }
        )
        MenuBar(this, listOf("Record" to record, "Edit" to edit, "Options" to options)).show()
    }

    private fun textOp(ed: EditText?, id: Int) {
        if (ed == null) { toast("Tap a line of text first."); return }
        ed.requestFocus()
        ed.onTextContextMenuItem(id)
    }

    // ---------------- Find ----------------

    private fun showFind() {
        val d = PalmDialog(this, "Find")
        val f = field("", "Find text")
        d.add(f)
        d.button("OK") {
            val q = f.text.toString().trim()
            d.dismiss(); hideKeyboard()
            if (q.isNotEmpty()) showFindResults(q)
        }
        d.button("Cancel") { d.dismiss() }
        d.show()
        f.requestFocus()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun showFindResults(q: String) {
        val d = PalmDialog(this, "Find")
        d.add(label("Matches for \"$q\"", bold = true))
        val hideP = prefs.security != 0
        val evs = store.events.filter {
            !(hideP && it.isPrivate) && (it.title.contains(q, true) || it.note.contains(q, true))
        }.sortedBy { it.date }
        val tds = store.todos.filter {
            !(hideP && it.isPrivate) && (it.title.contains(q, true) || it.note.contains(q, true))
        }
        fun result(text: String, onClick: () -> Unit) =
            d.add(label(text).apply { setPadding(dp(8), dp(7), dp(4), dp(7)); setOnClickListener { d.dismiss(); onClick() } }, 0)
        d.add(label("Date Book", bold = true).apply { setTextColor(Palm.GRAY) }, 10)
        if (evs.isEmpty()) d.add(label("  No matches"), 0)
        for (e in evs) {
            val shown = nextOccurrence(e)
            result("${Fmt.shortDate(shown)}  ${e.title.ifBlank { "(no description)" }}") {
                if (isTodoApp) openDateBook(shown, e.id) else goDay(shown, Occ(e, shown))
            }
        }
        d.add(label("To Do List", bold = true).apply { setTextColor(Palm.GRAY) }, 10)
        if (tds.isEmpty()) d.add(label("  No matches"), 0)
        for (t in tds) {
            result(t.title.ifBlank { "(no description)" }) {
                if (isTodoApp) { prefs.todoCategory = -1; selectedTodo = t; render() } else openTodoApp(t.id)
            }
        }
        d.button("Done") { d.dismiss() }
        d.show()
    }

    /** The first occurrence on or after today, or the last one if all are past. */
    private fun nextOccurrence(e: Event): Long {
        if (!e.repeats) return e.date
        var d = maxOf(today, e.date)
        val last = minOf(Recur.lastDay(e), d + 800)
        while (d <= last) { if (Recur.occursOn(e, d)) return d; d++ }
        return e.date
    }

    // ---------------- Backup files ----------------

    fun exportFile(base: String, text: String, after: () -> Unit = {}) {
        pendingExport = text
        afterExport = after
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "$base-${LocalDate.now()}.json")
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun importFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            if (requestCode == REQ_EXPORT) { pendingExport = null; afterExport = null; toast("Not saved.") }
            return
        }
        when (requestCode) {
            REQ_EXPORT -> {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write((pendingExport ?: "").toByteArray()) }
                    toast("Saved.")
                    afterExport?.invoke()
                } catch (ex: Exception) {
                    ask("Export", "The file was not saved: ${ex.message}", "OK" to {})
                }
                pendingExport = null; afterExport = null
            }
            REQ_IMPORT -> {
                try {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    val (ne, nt) = store.importJson(text)
                    changed()
                    ask("Import", "Added $ne events and $nt To Do items.", "OK" to {})
                } catch (ex: Exception) {
                    ask("Import", "This file is not a Date Book backup.", "OK" to {})
                }
            }
        }
    }
}

/** The thin dotted line under each Day view and To Do line. */
class DottedLine(ctx: Context) : View(ctx) {
    private val p = Paint().apply {
        color = Palm.LIGHT; strokeWidth = ctx.dp(1f).toFloat()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(ctx.dp(2).toFloat(), ctx.dp(2).toFloat()), 0f)
    }
    override fun onDraw(c: Canvas) { c.drawLine(0f, height / 2f, width.toFloat(), height / 2f, p) }
}

/** Day view time bars: one lane per overlapping event, with ticks at start and end. */
@SuppressLint("ViewConstructor")
class TimeBar(
    ctx: Context,
    private val timed: List<Occ>,
    private val laneOf: Map<Occ, Int>,
    private val rs: Int,
    private val re: Int
) : View(ctx) {
    private val p = Paint().apply { color = Palm.INK; strokeWidth = ctx.dp(1.6f).toFloat() }

    override fun onDraw(c: Canvas) {
        val lw = context.dp(5).toFloat()
        val x0 = context.dp(4).toFloat()
        val tick = context.dp(3).toFloat()
        for (o in timed) {
            val e = o.event
            val end = maxOf(e.end, e.start + 1)
            if (!(e.start < re && end > rs)) continue
            val x = x0 + (laneOf[o] ?: 0) * lw
            val startsHere = e.start >= rs && e.start < re
            val endsHere = end > rs && end <= re
            val top = if (startsHere) context.dp(10).toFloat() else 0f
            val bot = if (endsHere) height - context.dp(10).toFloat() else height.toFloat()
            c.drawLine(x, top, x, maxOf(top, bot), p)
            if (startsHere) c.drawLine(x, top, x + tick, top, p)
            if (endsHere) c.drawLine(x, maxOf(top, bot), x + tick, maxOf(top, bot), p)
        }
    }
}

/** Palm menu bar: menu titles across the top, the open menu's commands below. */
class MenuBar(
    private val act: Activity,
    private val menus: List<Pair<String, List<Pair<String, () -> Unit>>>>
) : Dialog(act) {
    private val titles = act.hbox()
    private val items = act.vbox()
    private var open = 0

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val frame = act.vbox().apply {
            background = act.box(Palm.paper, Palm.INK, 2f, 4f)
            setPadding(act.dp(2), act.dp(2), act.dp(2), act.dp(4))
        }
        frame.addView(titles, lp(MATCH))
        frame.addView(View(act).apply { setBackgroundColor(Palm.INK) }, LP(MATCH, act.dp(1)))
        frame.addView(items, lp(MATCH))
        setContentView(frame)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setGravity(Gravity.TOP)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        draw()
    }

    private fun draw() {
        titles.removeAllViews()
        menus.forEachIndexed { i, (name, _) ->
            titles.addView(act.label(name, bold = true).apply {
                setPadding(act.dp(12), act.dp(7), act.dp(12), act.dp(7))
                if (i == open) { setBackgroundColor(Palm.INK); setTextColor(Palm.paper) }
                setOnClickListener { open = i; draw() }
            })
        }
        items.removeAllViews()
        for ((name, action) in menus[open].second) {
            items.addView(act.label(name).apply {
                setPadding(act.dp(16), act.dp(9), act.dp(16), act.dp(9))
                setOnClickListener { dismiss(); action() }
            }, lp(MATCH))
        }
    }
}

@Suppress("unused")
private fun unusedImports(c: CheckBox) = c
