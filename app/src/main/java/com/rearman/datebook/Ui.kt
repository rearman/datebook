package com.rearman.datebook

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Palm OS look: black ink on a white (or green LCD) screen, with grays for shading. */
object Palm {
    const val INK = Color.BLACK
    const val GRAY = 0xFF808080.toInt()
    const val LIGHT = 0xFFC4C4C4.toInt()
    var paper = Color.WHITE
    var textSp = 15f

    fun apply(p: Prefs) {
        paper = if (p.greenScreen) 0xFFC9D3A8.toInt() else Color.WHITE
        textSp = when (p.font) { 0 -> 13f; 2 -> 18f; else -> 15f }
    }
}

typealias LP = LinearLayout.LayoutParams

val MATCH: Int = ViewGroup.LayoutParams.MATCH_PARENT
val WRAP: Int = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

fun Context.dp(v: Int): Int = dp(v.toFloat())

fun lp(w: Int = WRAP, h: Int = WRAP, weight: Float = 0f) = LP(w, h, weight)

fun Context.label(text: String, bold: Boolean = false, size: Float = Palm.textSp): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(Palm.INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.hbox(gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout =
    LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.box(fill: Int, stroke: Int, strokeDp: Float, radiusDp: Float, dashed: Boolean = false): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (dashed) setStroke(dp(strokeDp), stroke, dp(3).toFloat(), dp(2).toFloat())
        else if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }

/** Rounded Palm command button. Inverts while pressed. */
fun Context.palmButton(text: String, onClick: () -> Unit): TextView =
    label(text, bold = true).apply {
        gravity = Gravity.CENTER
        val normal = box(Palm.paper, Palm.INK, 1.5f, 9f)
        val pressed = box(Palm.INK, Palm.INK, 1.5f, 9f)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(Palm.paper, Palm.INK)
            )
        )
        setPadding(dp(12), dp(5), dp(12), dp(5))
        minWidth = dp(52)
        setOnClickListener { onClick() }
    }

/** Palm selector trigger: a value in a dotted box. Tap to change it. */
fun Context.selector(text: String, onClick: (TextView) -> Unit): TextView =
    label(text).apply {
        background = box(Color.TRANSPARENT, Palm.GRAY, 1f, 2f, dashed = true)
        setPadding(dp(6), dp(4), dp(6), dp(4))
        setOnClickListener { onClick(this) }
    }

/** Palm pop-up trigger: "▼ value". */
fun Context.popupTrigger(text: String, onClick: (TextView) -> Unit): TextView =
    label("▼ $text", bold = true).apply {
        setPadding(dp(4), dp(4), dp(8), dp(4))
        setOnClickListener { onClick(this) }
    }

fun TextView.setTrigger(text: String) { this.text = "▼ $text" }

fun Context.checkBox(text: String, checked: Boolean, onChange: (Boolean) -> Unit = {}): CheckBox =
    CheckBox(this).apply {
        this.text = text
        isChecked = checked
        setTextColor(Palm.INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Palm.textSp)
        buttonTintList = ColorStateList.valueOf(Palm.INK)
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

fun Context.field(text: String, hint: String = "", multiLine: Boolean = false): EditText =
    EditText(this).apply {
        setText(text)
        this.hint = hint
        setTextColor(Palm.INK)
        setHintTextColor(Palm.GRAY)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Palm.textSp)
        backgroundTintList = ColorStateList.valueOf(Palm.GRAY)
        inputType = if (multiLine)
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

fun Context.numberField(value: Int): EditText = field(value.toString()).apply {
    inputType = InputType.TYPE_CLASS_NUMBER
    minWidth = dp(48)
    gravity = Gravity.CENTER
}

/**
 * A row of push buttons where one is selected (shown inverted),
 * like the priority 1-5 buttons or the repeat-type tabs.
 */
class PushGroup(
    private val ctx: Context,
    labels: List<String>,
    initial: Int,
    private val multi: Boolean = false,
    private val onChange: (PushGroup) -> Unit = {}
) {
    val view: LinearLayout = ctx.hbox()
    private val cells = mutableListOf<TextView>()
    var selected: Int = initial
    val on = BooleanArray(labels.size)

    init {
        labels.forEachIndexed { i, s ->
            val tv = ctx.label(s, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(4), ctx.dp(5), ctx.dp(4), ctx.dp(5))
                setOnClickListener {
                    if (multi) on[i] = !on[i] else selected = i
                    refresh()
                    onChange(this@PushGroup)
                }
            }
            cells.add(tv)
            view.addView(tv, LP(0, WRAP, 1f).apply { marginEnd = ctx.dp(2) })
        }
        refresh()
    }

    fun select(i: Int) { selected = i; refresh() }

    fun refresh() {
        cells.forEachIndexed { i, tv ->
            val sel = if (multi) on[i] else i == selected
            tv.background = ctx.box(if (sel) Palm.INK else Palm.paper, Palm.INK, 1.5f, 3f)
            tv.setTextColor(if (sel) Palm.paper else Palm.INK)
        }
    }
}

/** A scroll view that never grows taller than a set height. */
class MaxScroll(ctx: Context, private val maxPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxPx, View.MeasureSpec.AT_MOST))
    }
}

/** A Palm modal form: black title bar, bordered body, buttons along the bottom. */
open class PalmDialog(ctx: Context, title: String, scroll: Boolean = true) : Dialog(ctx) {
    val body: LinearLayout = ctx.vbox().apply { setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(4)) }
    val buttons: LinearLayout = ctx.hbox().apply { setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(10)) }
    private val c = ctx

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val frame = ctx.vbox().apply {
            background = ctx.box(Palm.paper, Palm.INK, 2.5f, 6f)
            setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
        }
        val bar = ctx.label(title, bold = true).apply {
            gravity = Gravity.CENTER
            setTextColor(Palm.paper)
            background = ctx.box(Palm.INK, Palm.INK, 0f, 4f)
            setPadding(ctx.dp(6), ctx.dp(4), ctx.dp(6), ctx.dp(4))
        }
        frame.addView(bar, lp(MATCH))
        if (scroll) {
            val sv = MaxScroll(ctx, ctx.resources.displayMetrics.heightPixels * 62 / 100)
            sv.addView(body)
            frame.addView(sv, lp(MATCH))
        } else {
            frame.addView(body, lp(MATCH))
        }
        frame.addView(buttons, lp(MATCH))
        setContentView(frame)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels - ctx.dp(24)),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    fun add(v: View, top: Int = 6): View {
        body.addView(v, LP(MATCH, WRAP).apply { topMargin = c.dp(top) })
        return v
    }

    /** "Label:  [control]" row, Palm style with right-aligned bold label. */
    fun row(name: String, vararg views: View): LinearLayout {
        val r = c.hbox()
        r.addView(c.label(name, bold = true).apply { gravity = Gravity.END; minWidth = c.dp(76) },
            lp().apply { marginEnd = c.dp(8) })
        views.forEach { r.addView(it, lp().apply { marginEnd = c.dp(6) }) }
        add(r)
        return r
    }

    fun button(text: String, onClick: () -> Unit): TextView {
        val b = c.palmButton(text, onClick)
        buttons.addView(b, lp().apply { marginEnd = c.dp(8) })
        return b
    }
}

/** A Palm pop-up list next to its trigger. */
fun Context.popupList(anchor: View, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val col = vbox().apply {
        background = box(Palm.paper, Palm.INK, 1.5f, 3f)
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }
    val sv = ScrollView(this).apply { addView(col) }
    val pw = PopupWindow(sv, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    items.forEachIndexed { i, s ->
        val tv = label(s, bold = i == selected).apply {
            setPadding(dp(12), dp(7), dp(24), dp(7))
            if (i == selected) { setBackgroundColor(Palm.INK); setTextColor(Palm.paper) }
            setOnClickListener { pw.dismiss(); onPick(i) }
        }
        col.addView(tv, lp(MATCH))
    }
    pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    pw.elevation = dp(4).toFloat()
    val maxH = resources.displayMetrics.heightPixels * 2 / 3
    sv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    if (sv.measuredHeight > maxH) pw.height = maxH
    pw.showAsDropDown(anchor, 0, -anchor.height)
}

/** A message with buttons. Each button closes the dialog and then runs its action. */
fun Context.ask(title: String, message: String, vararg choices: Pair<String, () -> Unit>) {
    val d = PalmDialog(this, title)
    d.add(label(message).apply { setPadding(0, dp(4), 0, dp(8)) })
    for ((text, action) in choices) d.button(text) { d.dismiss(); action() }
    d.show()
}

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

/** Small drawn icons for the Day view: note, alarm, repeat. */
class IconView(ctx: Context, private val kind: Int) : View(ctx) {
    companion object { const val NOTE = 0; const val ALARM = 1; const val REPEAT = 2 }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; style = android.graphics.Paint.Style.STROKE; strokeWidth = ctx.dp(1.4f).toFloat()
    }

    override fun onMeasure(w: Int, h: Int) { val s = context.dp(18); setMeasuredDimension(s, s) }

    override fun onDraw(c: android.graphics.Canvas) {
        val s = width.toFloat()
        val m = s * 0.15f
        when (kind) {
            NOTE -> {
                c.drawRect(m, m, s - m, s - m, paint)
                c.drawLine(m * 2, s * 0.4f, s - m * 2, s * 0.4f, paint)
                c.drawLine(m * 2, s * 0.6f, s - m * 2, s * 0.6f, paint)
            }
            ALARM -> {
                val cx = s / 2; val cy = s / 2 + m / 3
                c.drawCircle(cx, cy, s / 2 - m, paint)
                c.drawLine(cx, cy, cx, cy - s * 0.22f, paint)
                c.drawLine(cx, cy, cx + s * 0.18f, cy, paint)
            }
            REPEAT -> {
                val r = android.graphics.RectF(m, m, s - m, s - m)
                c.drawArc(r, 200f, 280f, false, paint)
                val ax = s - m; val ay = s / 2
                c.drawLine(ax, ay, ax - s * 0.15f, ay - s * 0.15f, paint)
                c.drawLine(ax, ay, ax + s * 0.1f, ay - s * 0.17f, paint)
            }
        }
    }
}

fun Context.frame(): FrameLayout = FrameLayout(this)

@Suppress("unused")
fun drawableOf(d: Drawable) = d
