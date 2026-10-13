package com.rearman.datebook

import android.content.ClipData
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/*
 * Memo Pad screens, after the Palm OS 3.5 Memo Pad. Each function is an extension on
 * MainActivity, the same way Details.kt builds its screens.
 */

/** Memos shown in the list: the category filter, the private filter, and the sort order. */
internal fun MainActivity.visibleMemos(): List<Memo> {
    val cat = prefs.memoCategory
    val hidePrivate = prefs.security == 2
    return memoOrder(store.memos, prefs.memoSort == 1).filter { m ->
        (cat < 0 || m.category == cat) && !(hidePrivate && m.isPrivate)
    }
}

/**
 * Memo List screen. One line per memo, in the chosen order. Tap a line to open it.
 * In manual sort, a long press drags a line onto another line to move it.
 */
internal fun MainActivity.buildMemoList(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
    tab(h, "Memo List")
    h.addView(popupTrigger(categoryLabel(prefs.memoCategory)) { tv ->
        endEdit()
        pickCategory(tv, prefs.memoCategory, includeAll = true, app = 1) { id -> prefs.memoCategory = id; render() }
    })

    val manual = prefs.memoSort == 0
    val items = visibleMemos()
    val col = vbox()
    if (items.isEmpty()) col.addView(label("No memos. Tap New to add one.").apply {
        setPadding(pp(5), pp(6.5f), pp(5), pp(6.5f))
    })
    items.forEachIndexed { i, m -> col.addView(memoRow(m, i + 1, manual), lp(MATCH)) }
    content.addView(ScrollView(this).apply { addView(col); isFillViewport = true; isVerticalScrollBarEnabled = false })

    cmd(cmds, "New") { newMemo() }
    cmds.addView(View(this), LP(0, 1, 1f))
}

/**
 * One list row for [m], numbered [n]. A masked memo shows a gray bar; a tap asks to show
 * it. In manual sort, the row also takes a drag and a drop.
 */
private fun MainActivity.memoRow(m: Memo, n: Int, manual: Boolean): View {
    val row: View = if (isMasked(m)) hbox().apply {
        addView(label("").apply {
            background = box(Palm.LIGHT, Palm.LIGHT, 0f, 1f)
            setOnClickListener { revealAsk(m) }
        }, LP(0, pp(7), 1f).apply { topMargin = pp(2); marginEnd = pp(3) })
    } else label("$n. ${m.title}").apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(pp(3), 0, pp(3), 0)
        setOnClickListener { endEdit(); memoOpen = m; render() }
    }
    if (manual) {
        row.setOnLongClickListener { v ->
            v.startDragAndDrop(ClipData.newPlainText("memo", m.id.toString()), View.DragShadowBuilder(v), m, 0)
        }
        row.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DROP -> {
                    val dragged = event.localState as Memo
                    val full = memoOrder(store.memos, false)
                    val changed = moveMemo(full, full.indexOf(dragged), full.indexOf(m))
                    store.savePositions(changed)
                    render()
                    true
                }
                else -> false
            }
        }
    }
    return row
}

/**
 * Memo Edit screen for [MainActivity.memoOpen]. The body is one multi-line field on lined paper.
 * A change saves the memo when it has text, or when it already exists in the store.
 */
internal fun MainActivity.buildMemoEdit(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
    val m = memoOpen!!
    val list = visibleMemos()
    val idx = if (m.id == 0L) -1 else list.indexOfFirst { it.id == m.id }
    val n = if (idx < 0) list.size + 1 else idx + 1
    val total = if (idx < 0) list.size + 1 else list.size
    tab(h, "Memo $n of $total")
    h.addView(popupTrigger(categoryLabel(m.category)) { tv ->
        endEdit()
        pickCategory(tv, m.category, includeAll = false, app = 1) { id ->
            m.category = id
            if (m.text.isNotBlank()) store.save(m)
            render()
        }
    })

    val ed = EditText(this).apply {
        typeface = Palm.typeface(context, false)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ppf(Palm.text))
        includeFontPadding = false
        setTextColor(Palm.INK)
        background = LinedBackground(this@buildMemoEdit, this, fill = true)
        setPadding(pp(2), 0, pp(2), 0)
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        filters = arrayOf<InputFilter>(InputFilter.LengthFilter(4096)) // Palm OS memo limit
        setText(m.text)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                m.text = s?.toString() ?: ""
                if (m.id != 0L || m.text.isNotBlank()) store.save(m)
            }
        })
    }
    lastEdit = ed
    content.addView(ScrollView(this).apply {
        addView(ed, LP(MATCH, MATCH))
        isFillViewport = true
        isVerticalScrollBarEnabled = false
    })
    ed.post { ed.requestFocus(); ed.setSelection(ed.text.length) }
    if (m.id == 0L) showKeyboardSoon()

    cmd(cmds, "Done") {
        endEdit()
        if (m.text.isBlank() && m.id != 0L) store.delete(m)
        memoOpen = null
        render()
    }
    cmd(cmds, "Details") { showMemoDetails(m) }
    cmds.addView(View(this), LP(0, 1, 1f))
}

/** Starts a new memo in the category shown in the list, then opens it for editing. */
internal fun MainActivity.newMemo() {
    endEdit()
    memoOpen = Memo(category = if (prefs.memoCategory > 0) prefs.memoCategory else 0)
    render()
}

/** Memo Details dialog: the category and the private flag. OK saves them. Delete asks first. */
private fun MainActivity.showMemoDetails(m: Memo) {
    var cat = m.category
    var priv = m.isPrivate
    val d = PalmDialog(this, "Memo Details")
    d.row("Category:", popupTrigger(categoryLabel(cat)) { tv ->
        pickCategory(tv, cat, includeAll = false, app = 1) { id -> cat = id; tv.setTrigger(categoryLabel(id)) }
    })
    d.add(checkBox("Private", m.isPrivate) { b -> priv = b })
    d.button("OK") {
        m.category = cat
        m.isPrivate = priv
        if (m.id != 0L || m.text.isNotBlank()) store.save(m)
        d.dismiss()
        render()
    }
    d.button("Cancel") { d.dismiss() }
    d.button("Delete...") { d.dismiss(); deleteMemoAsk(m) }
    d.show()
}

/** Asks before the current memo is deleted. A memo that was never saved is only closed. */
internal fun MainActivity.deleteMemoAsk(m: Memo) {
    ask("Delete Memo", "Delete current memo?",
        "OK" to { if (m.id != 0L) store.delete(m); memoOpen = null; render() },
        "Cancel" to {})
}

/** Memo Pad Preferences: the sort order. OK saves it. */
internal fun MainActivity.showMemoPreferences() {
    val names = listOf("Manual", "Alphabetic")
    var sort = prefs.memoSort
    val d = PalmDialog(this, "Preferences")
    d.row("Sort by:", popupTrigger(names[sort]) { tv ->
        popupList(tv, names, sort) { i -> sort = i; tv.setTrigger(names[i]) }
    })
    d.button("OK") { prefs.memoSort = sort; d.dismiss(); render() }
    d.button("Cancel") { d.dismiss() }
    d.show()
}

/** Memo Pad About box. */
internal fun MainActivity.showMemoAbout() {
    ask("About Memo Pad", "Memo Pad, after the Palm OS 3.5 Memo Pad.", "OK" to {})
}
