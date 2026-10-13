package com.rearman.datebook

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
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
import kotlin.math.roundToInt

/** Palm OS look: black ink on a white (or green LCD) screen, with grays for shading. */
object Palm {
    const val INK = Color.BLACK
    const val GRAY = 0xFF808080.toInt()
    const val LIGHT = 0xFFC4C4C4.toInt()
    var paper = Color.WHITE
    /** Text size in Palm pixels. 9.4 gives the 11 px line of the Palm standard font. */
    var text = 9.4f

    fun apply(p: Prefs) {
        paper = if (p.greenScreen) 0xFFC9D3A8.toInt() else Color.WHITE
        text = when (p.font) { 0 -> 8f; 2 -> 12f; else -> 9.4f }
    }
}

typealias LP = LinearLayout.LayoutParams

val MATCH: Int = ViewGroup.LayoutParams.MATCH_PARENT
val WRAP: Int = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

fun Context.dp(v: Int): Int = dp(v.toFloat())

/** Device pixels for [v] Palm pixels. The shorter screen side is 160 Palm px, as on the Palm IIIxe. */
fun Context.ppf(v: Float): Float =
    v * minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) / 160f

fun Context.pp(v: Float): Int = if (v <= 0f) 0 else maxOf(1, ppf(v).roundToInt())

fun Context.pp(v: Int): Int = pp(v.toFloat())

fun lp(w: Int = WRAP, h: Int = WRAP, weight: Float = 0f) = LP(w, h, weight)

fun Context.label(text: String, bold: Boolean = false, size: Float = Palm.text): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(Palm.INK)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ppf(size))
        includeFontPadding = false
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.hbox(gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout =
    LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.box(fill: Int, stroke: Int, strokePp: Float, radiusPp: Float, dashed: Boolean = false): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = pp(radiusPp).toFloat()
        if (dashed) setStroke(pp(strokePp), stroke, ppf(1f), ppf(1f))
        else if (strokePp > 0) setStroke(pp(strokePp), stroke)
    }

/** Rounded Palm command button. Inverts while pressed. */
fun Context.palmButton(text: String, onClick: () -> Unit): TextView =
    label(text, bold = true).apply {
        gravity = Gravity.CENTER
        val normal = box(Palm.paper, Palm.INK, 1f, 3.5f)
        val pressed = box(Palm.INK, Palm.INK, 1f, 3.5f)
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
        setPadding(pp(3), pp(1), pp(3), pp(1))
        minWidth = pp(16)
        setOnClickListener { onClick() }
    }

/** Palm selector trigger: a value in a dotted box. Tap to change it. */
fun Context.selector(text: String, onClick: (TextView) -> Unit): TextView =
    label(text).apply {
        background = box(Color.TRANSPARENT, Palm.GRAY, 1f, 1f, dashed = true)
        setPadding(pp(2), pp(1), pp(2), pp(1))
        setOnClickListener { onClick(this) }
    }

/** Palm pop-up trigger: "▼ value". */
fun Context.popupTrigger(text: String, onClick: (TextView) -> Unit): TextView =
    label("▼ $text", bold = true).apply {
        setPadding(pp(1), pp(1), pp(2), pp(1))
        setOnClickListener { onClick(this) }
    }

fun TextView.setTrigger(text: String) { this.text = "▼ $text" }

fun Context.checkBox(text: String, checked: Boolean, onChange: (Boolean) -> Unit = {}): CheckBox =
    CheckBox(this).apply {
        this.text = text
        isChecked = checked
        setTextColor(Palm.INK)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ppf(Palm.text))
        includeFontPadding = false
        buttonDrawable = PalmCheckDrawable(this@checkBox, pp(9))
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(pp(2), 0, 0, 0)
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

/** Palm check box: a square with a 1 Palm px frame, and a check mark when checked. */
class PalmCheckDrawable(ctx: Context, private val size: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; style = Paint.Style.STROKE; strokeWidth = ctx.ppf(1f)
    }
    private var checked = false

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val c = state.contains(android.R.attr.state_checked)
        if (c == checked) return false
        checked = c
        invalidateSelf()
        return true
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val h = p.strokeWidth / 2
        c.drawRect(b.left + h, b.top + h, b.right - h, b.bottom - h, p)
        if (checked) {
            val l = b.left.toFloat(); val t = b.top.toFloat(); val w = b.width().toFloat()
            c.drawLine(l + w * 0.2f, t + w * 0.5f, l + w * 0.42f, t + w * 0.75f, p)
            c.drawLine(l + w * 0.42f, t + w * 0.75f, l + w * 0.85f, t + w * 0.2f, p)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun Context.field(text: String, hint: String = "", multiLine: Boolean = false): EditText =
    EditText(this).apply {
        setText(text)
        this.hint = hint
        setTextColor(Palm.INK)
        setHintTextColor(Palm.GRAY)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ppf(Palm.text))
        includeFontPadding = false
        minHeight = 0
        minimumHeight = 0
        setPadding(pp(1), pp(1), pp(1), pp(1))
        backgroundTintList = ColorStateList.valueOf(Palm.GRAY)
        inputType = if (multiLine)
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

fun Context.numberField(value: Int): EditText = field(value.toString()).apply {
    inputType = InputType.TYPE_CLASS_NUMBER
    minWidth = pp(20)
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
                setPadding(ctx.pp(2), ctx.pp(1), ctx.pp(2), ctx.pp(1))
                setOnClickListener {
                    if (multi) on[i] = !on[i] else selected = i
                    refresh()
                    onChange(this@PushGroup)
                }
            }
            cells.add(tv)
            view.addView(tv, LP(0, WRAP, 1f).apply { marginEnd = ctx.pp(1) })
        }
        refresh()
    }

    fun select(i: Int) { selected = i; refresh() }

    fun refresh() {
        cells.forEachIndexed { i, tv ->
            val sel = if (multi) on[i] else i == selected
            tv.background = ctx.box(if (sel) Palm.INK else Palm.paper, Palm.INK, 1f, 1.5f)
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
    val body: LinearLayout = ctx.vbox().apply { setPadding(ctx.pp(3), ctx.pp(2), ctx.pp(3), ctx.pp(1)) }
    val buttons: LinearLayout = ctx.hbox().apply { setPadding(ctx.pp(3), ctx.pp(2), ctx.pp(3), ctx.pp(3)) }
    private val c = ctx

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val frame = ctx.vbox().apply {
            background = ctx.box(Palm.paper, Palm.INK, 1.5f, 2f)
            setPadding(ctx.pp(1), ctx.pp(1), ctx.pp(1), ctx.pp(1))
        }
        val bar = ctx.label(title, bold = true).apply {
            gravity = Gravity.CENTER
            setTextColor(Palm.paper)
            background = ctx.box(Palm.INK, Palm.INK, 0f, 1.5f)
            setPadding(ctx.pp(2), ctx.pp(1), ctx.pp(2), ctx.pp(1))
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
            (ctx.resources.displayMetrics.widthPixels - ctx.pp(4)),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    /** Adds [v] to the body. [top] is the gap above [v], in Palm px. */
    fun add(v: View, top: Int = 2): View {
        body.addView(v, LP(MATCH, WRAP).apply { topMargin = c.pp(top) })
        return v
    }

    /** "Label:  [control]" row, Palm style with right-aligned bold label. */
    fun row(name: String, vararg views: View): LinearLayout {
        val r = c.hbox()
        r.addView(c.label(name, bold = true).apply { gravity = Gravity.END; minWidth = c.pp(40) },
            lp().apply { marginEnd = c.pp(3) })
        views.forEach { r.addView(it, lp().apply { marginEnd = c.pp(2) }) }
        add(r)
        return r
    }

    fun button(text: String, onClick: () -> Unit): TextView {
        val b = c.palmButton(text, onClick)
        buttons.addView(b, lp().apply { marginEnd = c.pp(4) })
        return b
    }
}

/** A Palm pop-up list next to its trigger. */
fun Context.popupList(anchor: View, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val col = vbox().apply {
        background = box(Palm.paper, Palm.INK, 1f, 1.5f)
        setPadding(pp(1), pp(1), pp(1), pp(1))
    }
    val sv = ScrollView(this).apply { addView(col) }
    val pw = PopupWindow(sv, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    items.forEachIndexed { i, s ->
        val tv = label(s, bold = i == selected).apply {
            setPadding(pp(3), pp(1), pp(8), pp(1))
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
    d.add(label(message).apply { setPadding(0, pp(1), 0, pp(3)) })
    for ((text, action) in choices) d.button(text) { d.dismiss(); action() }
    d.show()
}

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

/** Small drawn icons for the Day view: note, alarm, repeat. */
class IconView(ctx: Context, private val kind: Int) : View(ctx) {
    companion object { const val NOTE = 0; const val ALARM = 1; const val REPEAT = 2 }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; style = android.graphics.Paint.Style.STROKE; strokeWidth = ctx.ppf(1f)
    }

    override fun onMeasure(w: Int, h: Int) { val s = context.pp(8); setMeasuredDimension(s, s) }

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
