package com.rearman.datebook

import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.time.LocalDate

/** Palm "Event Details" form. Edits a copy; the copy is saved on OK. */
class EventDetails(
    private val act: MainActivity,
    private val orig: Event,
    private val occDay: Long,
    private val isNew: Boolean
) : PalmDialog(act, "Event Details") {
    private val work = orig.deepCopy().apply { date = occDay }
    private val alarmBox = act.hbox()
    private val adv = act.numberField(work.alarmAdvance)

    init {
        row("Time:", act.selector(Fmt.range(work)) { tv ->
            TimeDialog(act, work.untimed, work.start, work.end) { u, s, e ->
                work.untimed = u; work.start = s; work.end = e; tv.text = Fmt.range(work)
            }.show()
        })
        row("Date:", act.selector(Fmt.dowDate(work.date)) { tv ->
            DateDialog(act, "Set Date", work.date) { d -> work.date = d; tv.text = Fmt.dowDate(d) }.show()
        })

        val chk = act.checkBox("", work.alarm) { b -> work.alarm = b; alarmBox.visibility = if (b) View.VISIBLE else View.GONE }
        alarmBox.addView(adv)
        val units = listOf("Minutes", "Hours", "Days")
        alarmBox.addView(act.popupTrigger(units[work.alarmUnit]) { tv ->
            act.popupList(tv, units, work.alarmUnit) { i -> work.alarmUnit = i; tv.setTrigger(units[i]) }
        }, lp().apply { marginStart = act.pp(1.5f) })
        alarmBox.visibility = if (work.alarm) View.VISIBLE else View.GONE
        row("Alarm:", chk, alarmBox)

        row("Repeat:", act.selector(Recur.shortName(work)) { tv ->
            RepeatDialog(act, work) { r ->
                work.repeat = r.repeat; work.every = r.every; work.repeatEnd = r.repeatEnd
                work.weekDays = r.weekDays
                if (r.repeat == Repeat.NONE) work.exceptions.clear()
                tv.text = Recur.shortName(work)
            }.show()
        })
        row("Private:", act.checkBox("", work.isPrivate) { b -> work.isPrivate = b })

        button("OK") { collect(); dismiss(); act.saveEventEdit(orig, occDay, work, isNew) {} }
        button("Cancel") { dismiss() }
        if (!isNew) button("Delete...") { dismiss(); act.deleteEvent(orig, occDay) }
        button("Note") {
            collect(); dismiss()
            act.saveEventEdit(orig, occDay, work, isNew) { o -> act.openNote(o) }
        }
    }

    private fun collect() {
        work.alarmAdvance = adv.text.toString().toIntOrNull()?.coerceIn(0, 99) ?: 0
    }
}

/** Palm "To Do Item Details" form. */
class TodoDetails(private val act: MainActivity, private val t: Todo) : PalmDialog(act, "To Do Item Details") {
    private val work = t.copy()

    init {
        val pri = PushGroup(act, listOf("1", "2", "3", "4", "5"), work.priority - 1) { g -> work.priority = g.selected + 1 }
        row("Priority:", pri.view)
        (pri.view.layoutParams as? LinearLayout.LayoutParams)?.let { it.width = act.pp(80) }

        row("Category:", act.popupTrigger(act.store.categoryName(work.category)) { tv ->
            act.pickCategory(tv, work.category, includeAll = false) { id -> work.category = id; tv.setTrigger(act.store.categoryName(id)) }
        })
        row("Due Date:", act.popupTrigger(dueText(work.due)) { tv ->
            act.pickDue(tv, work.due) { d -> work.due = d; tv.setTrigger(dueText(d)) }
        })
        row("Private:", act.checkBox("", work.isPrivate) { b -> work.isPrivate = b })

        button("OK") { dismiss(); act.saveTodo(work) }
        button("Cancel") { dismiss() }
        button("Delete...") { dismiss(); act.deleteTodo(t) }
        button("Note") { dismiss(); act.saveTodo(work); act.openTodoNote(work) }
    }

    private fun dueText(d: Long) = if (d < 0) "No Date" else Fmt.dowDate(d)
}

// ---------------- Option dialogs ----------------

fun MainActivity.showPreferences() {
    val d = PalmDialog(this, "Preferences")
    val hourNames = (0..24).map { if (it == 24) "Midnight" else Fmt.time(it * 60) }
    var start = prefs.dayStart
    var end = prefs.dayEnd
    d.row("Start Time:", popupTrigger(hourNames[start]) { tv ->
        popupList(tv, hourNames.dropLast(1), start) { i -> start = i; if (end <= start) end = start + 1; tv.setTrigger(hourNames[i]) }
    })
    d.row("End Time:", popupTrigger(hourNames[end]) { tv ->
        popupList(tv, hourNames.drop(1), end - 1) { i -> end = maxOf(i + 1, start + 1); tv.setTrigger(hourNames[end]) }
    })

    var presetOn = prefs.presetOn
    var unit = prefs.presetUnit
    val adv = numberField(prefs.presetAdvance)
    val units = listOf("Minutes", "Hours", "Days")
    val presetBox = hbox()
    presetBox.addView(adv)
    presetBox.addView(popupTrigger(units[unit]) { tv ->
        popupList(tv, units, unit) { i -> unit = i; tv.setTrigger(units[i]) }
    })
    presetBox.visibility = if (presetOn) View.VISIBLE else View.GONE
    d.row("Alarm Preset:", checkBox("", presetOn) { b -> presetOn = b; presetBox.visibility = if (b) View.VISIBLE else View.GONE }, presetBox)

    val remindVals = listOf(1, 2, 3, 5, 10)
    val remindNames = listOf("Once", "Twice", "3 times", "5 times", "10 times")
    var remind = prefs.remindTimes
    d.row("Remind:", popupTrigger(remindNames[remindVals.indexOf(remind).coerceAtLeast(0)]) { tv ->
        popupList(tv, remindNames, remindVals.indexOf(remind)) { i -> remind = remindVals[i]; tv.setTrigger(remindNames[i]) }
    })
    val everyVals = listOf(1, 5, 10, 30)
    val everyNames = listOf("1 minute", "5 minutes", "10 minutes", "30 minutes")
    var every = prefs.remindEvery
    d.row("Play every:", popupTrigger(everyNames[everyVals.indexOf(every).coerceAtLeast(0)]) { tv ->
        popupList(tv, everyNames, everyVals.indexOf(every)) { i -> every = everyVals[i]; tv.setTrigger(everyNames[i]) }
    })
    d.row("Alarm Sound:", palmButton("Change...") {
        Alarms.ensureChannel(this)
        startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Alarms.CHANNEL)
        )
    })

    d.button("OK") {
        prefs.dayStart = start; prefs.dayEnd = end
        prefs.presetOn = presetOn; prefs.presetUnit = unit
        prefs.presetAdvance = adv.text.toString().toIntOrNull()?.coerceIn(0, 99) ?: 5
        prefs.remindTimes = remind; prefs.remindEvery = every
        d.dismiss(); render()
    }
    d.button("Cancel") { d.dismiss() }
    d.show()
}

fun MainActivity.showDisplayOptions() {
    val d = PalmDialog(this, "Display Options")
    d.add(label("Day View:", bold = true))
    val bars = checkBox("Show Time Bars", prefs.timeBars)
    val comp = checkBox("Compress Day View", prefs.compress)
    d.add(bars, 0); d.add(comp, 0)
    d.add(label("Month View:", bold = true), 5)
    val timed = checkBox("Show Timed Items", prefs.monthTimed)
    val untimed = checkBox("Show Untimed Items", prefs.monthUntimed)
    val daily = checkBox("Show Daily Repeating Events", prefs.monthDaily)
    d.add(timed, 0); d.add(untimed, 0); d.add(daily, 0)
    d.add(label("Screen:", bold = true), 5)
    val screen = PushGroup(this, listOf("White", "Green LCD"), if (prefs.greenScreen) 1 else 0)
    d.add(screen.view)
    d.button("OK") {
        prefs.timeBars = bars.isChecked; prefs.compress = comp.isChecked
        prefs.monthTimed = timed.isChecked; prefs.monthUntimed = untimed.isChecked; prefs.monthDaily = daily.isChecked
        prefs.greenScreen = screen.selected == 1
        d.dismiss(); render()
    }
    d.button("Cancel") { d.dismiss() }
    d.show()
}

fun MainActivity.showFont() {
    val d = PalmDialog(this, "Select Font")
    val g = PushGroup(this, listOf("Small", "Normal", "Large"), prefs.font)
    d.add(g.view)
    d.add(label("Sample: Lunch with Ann 12:00").apply { gravity = Gravity.CENTER }, 5)
    d.button("OK") { prefs.font = g.selected; d.dismiss(); render() }
    d.button("Cancel") { d.dismiss() }
    d.show()
}

fun MainActivity.showSecurity() {
    val d = PalmDialog(this, "Security")
    d.add(label("Current Privacy:", bold = true))
    val g = PushGroup(this, listOf("Show", "Mask", "Hide"), prefs.security)
    d.add(g.view)
    d.add(label("Mask: private records show as a gray bar. Tap one to see it.\nHide: private records do not show.", size = Palm.text), 4)
    d.button("OK") { prefs.security = g.selected; revealed.clear(); d.dismiss(); render() }
    d.button("Cancel") { d.dismiss() }
    d.show()
}

fun MainActivity.showAbout(todo: Boolean) {
    ask(
        if (todo) "About To Do List" else "About Date Book",
        "Date Book and To Do List for Android\nVersion 1.0\n\nAfter the Palm OS 3.5 applications.\nYour records stay on this phone.",
        "OK" to {}
    )
}

fun MainActivity.showShowOptions() {
    val d = PalmDialog(this, "To Do Preferences")
    val sorts = listOf("Priority, Due Date", "Due Date, Priority", "Category, Priority", "Category, Due Date")
    var sort = prefs.sortMode
    d.row("Sort by:", popupTrigger(sorts[sort]) { tv -> popupList(tv, sorts, sort) { i -> sort = i; tv.setTrigger(sorts[i]) } })
    val c1 = checkBox("Show Completed Items", prefs.showCompleted)
    val c2 = checkBox("Show Only Due Items", prefs.onlyDue)
    val c3 = checkBox("Record Completion Date", prefs.recordCompletion)
    val c4 = checkBox("Show Due Dates", prefs.showDueDates)
    val c5 = checkBox("Show Priorities", prefs.showPriorities)
    val c6 = checkBox("Show Categories", prefs.showCategories)
    listOf(c1, c2, c3, c4, c5, c6).forEach { d.add(it, 0) }
    d.button("OK") {
        prefs.sortMode = sort
        prefs.showCompleted = c1.isChecked; prefs.onlyDue = c2.isChecked; prefs.recordCompletion = c3.isChecked
        prefs.showDueDates = c4.isChecked; prefs.showPriorities = c5.isChecked; prefs.showCategories = c6.isChecked
        d.dismiss(); render()
    }
    d.button("Cancel") { d.dismiss() }
    d.show()
}

/**
 * Edit Categories dialog for one app.
 *
 * [app] 0 edits the To Do categories, 1 the Memo Pad categories.
 */
fun MainActivity.showEditCategories(app: Int = 0) {
    val d = PalmDialog(this, "Edit Categories")
    val list = vbox()
    fun fill() {
        list.removeAllViews()
        for (cat in store.categoriesFor(app)) {
            list.addView(label(cat.name).apply {
                setPadding(pp(3), pp(3), pp(3), pp(3))
                setOnClickListener { renameCategory(cat, app) { fill() } }
            }, lp(LinearLayout.LayoutParams.MATCH_PARENT))
        }
        if (store.categoriesFor(app).isEmpty()) list.addView(label("No categories"))
    }
    fill()
    d.add(label("Tap a category to rename or delete it.", size = Palm.text))
    d.add(list)
    d.button("OK") { d.dismiss(); render() }
    d.button("New") {
        if (store.categoriesFor(app).size >= Store.MAX_CATEGORIES) toast("You can have only 15 categories.")
        else renameCategory(null, app) { fill() }
    }
    d.show()
}

private fun MainActivity.renameCategory(cat: Category?, app: Int, after: () -> Unit) {
    val d = PalmDialog(this, if (cat == null) "New Category" else "Rename Category")
    val f = field(cat?.name ?: "", "Category name")
    d.add(f)
    d.button("OK") {
        val n = f.text.toString().trim()
        if (n.isNotEmpty()) {
            if (cat == null) store.addCategory(n, app) else store.renameCategory(cat, n)
        }
        d.dismiss(); after()
    }
    d.button("Cancel") { d.dismiss() }
    if (cat != null) d.button("Delete...") {
        ask("Delete Category", "Delete \"${cat.name}\"? Its items move to Unfiled.",
            "Yes" to {
                store.deleteCategory(cat)
                if (prefs.todoCategory == cat.id) prefs.todoCategory = -1
                d.dismiss(); after()
            }, "No" to {})
    }
    d.show()
}

fun MainActivity.showPurgeEvents() {
    val d = PalmDialog(this, "Purge")
    d.add(label("Delete events older than:"))
    val names = listOf("1 week", "2 weeks", "3 weeks", "1 month")
    val days = listOf(7, 14, 21, 30)
    var pick = 0
    d.add(popupTrigger(names[0]) { tv -> popupList(tv, names, pick) { i -> pick = i; tv.setTrigger(names[i]) } })
    val archive = checkBox("Save archive copy", true)
    d.add(archive)
    d.button("OK") {
        val cutoff = LocalDate.now().toEpochDay() - days[pick]
        val old = store.events.filter { Recur.lastDay(it) < cutoff }
        d.dismiss()
        if (old.isEmpty()) { toast("No events to purge."); return@button }
        val doIt = { old.forEach { store.delete(it) }; changed(); toast("Purged ${old.size} events.") }
        if (archive.isChecked) exportFile("datebook-archive", store.exportJson(old, emptyList()), doIt)
        else doIt()
    }
    d.button("Cancel") { d.dismiss() }
    d.show()
}

fun MainActivity.showPurgeTodos() {
    val done = store.todos.filter { it.done }
    if (done.isEmpty()) { toast("No completed items."); return }
    val d = PalmDialog(this, "Purge")
    d.add(label("Delete all ${done.size} completed To Do items?"))
    val archive = checkBox("Save archive copy", true)
    d.add(archive)
    d.button("OK") {
        d.dismiss()
        val doIt = { done.forEach { store.delete(it) }; render(); toast("Purged ${done.size} items.") }
        if (archive.isChecked) exportFile("todo-archive", store.exportJson(emptyList(), done), doIt)
        else doIt()
    }
    d.button("Cancel") { d.dismiss() }
    d.show()
}
