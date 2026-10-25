// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
package site.cubexcraft.mobile.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The CubeX Mobile 3.0 design (handoff android3, tokens.json) as plain Android Views: colours, the three fonts, the cut-corner
 * buttons, panels, rows, fields. No XML layouts and no Compose: views are made in code, which inflates fastest on weak phones.
 * Sizes are dp/sp as in the design (it is drawn at 360 dp wide).
 */
object Cx {
    const val BG = 0xFF0B0509.toInt()
    const val PANEL = 0xFF14090D.toInt()
    const val LINE = 0xFF3A0D13.toInt()
    const val LINE2 = 0xFF5E141D.toInt()
    const val TEXT = 0xFFF0E2E3.toInt()
    const val TEXT_HI = 0xFFFBF2F2.toInt()
    const val SOFT = 0xFFD9C3C6.toInt()
    const val MUTED = 0xFFB09BA0.toInt()
    const val DIM = 0xFF8A6167.toInt()
    const val ACCENT = 0xFFFF2A3C.toInt()
    const val SUCCESS = 0xFF00FF9F.toInt()
    const val WARNING = 0xFFFCEE0A.toInt()
    const val FIELD = 0xFF0E0609.toInt()
    const val TRACK = 0xFF2A0810.toInt()
    const val IMAGE = 0xFF1A0C11.toInt()
    const val ON_PRIMARY = 0xFFFFD6D9.toInt()
    const val ON_SELECTED = 0xFFFFF2F3.toInt()

    fun alpha(color: Int, a: Float) = (color and 0xFFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)

    enum class Font(val file: String) {
        DISPLAY("Unbounded-ExtraBold.ttf"), DISPLAY_SEMI("Unbounded-SemiBold.ttf"),
        BODY("Manrope-Regular.ttf"), BODY_SEMI("Manrope-SemiBold.ttf"),
        MONO("JetBrainsMono-Regular.ttf"), MONO_BOLD("JetBrainsMono-Bold.ttf"),
    }

    private val faces = arrayOfNulls<Typeface>(Font.entries.size)
    private lateinit var app: Context

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** Loaded on first use (a few ms each), not at start. */
    fun face(f: Font): Typeface = faces[f.ordinal] ?: synchronized(faces) {
        faces[f.ordinal] ?: runCatching { Typeface.createFromAsset(app.assets, "fonts/" + f.file) }.getOrDefault(Typeface.DEFAULT)
            .also { faces[f.ordinal] = it }
    }
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun View.dp(v: Int) = context.dp(v)
fun View.dp(v: Float) = context.dp(v)

val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply { setMargins(l, t, r, b) }

/**
 * A box: fill, 1 dp border, optional cut corners (top right and bottom left, 8 dp — the design's «срез»). One drawable,
 * no shadows or blur.
 */
class BoxDrawable(
    private val fill: Int, private val stroke: Int = 0, private val strokeW: Float = 0f, private val cut: Float = 0f,
    private val radius: Float = 0f,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        val inset = strokeW / 2
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        path.reset()
        if (cut > 0f) {
            path.moveTo(rect.left, rect.top)
            path.lineTo(rect.right - cut, rect.top)
            path.lineTo(rect.right, rect.top + cut)
            path.lineTo(rect.right, rect.bottom)
            path.lineTo(rect.left + cut, rect.bottom)
            path.lineTo(rect.left, rect.bottom - cut)
            path.close()
        } else {
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
    }

    override fun draw(c: Canvas) {
        if (fill != 0) {
            paint.style = Paint.Style.FILL
            paint.color = fill
            c.drawPath(path, paint)
        }
        if (stroke != 0 && strokeW > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeW
            paint.color = stroke
            c.drawPath(path, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Normal and pressed look of a tappable view: the fill changes in the same frame (handoff README). */
fun pressable(normal: Drawable, pressed: Drawable, disabled: Drawable? = null) = StateListDrawable().apply {
    if (disabled != null) addState(intArrayOf(-android.R.attr.state_enabled), disabled)
    addState(intArrayOf(android.R.attr.state_pressed), pressed)
    addState(intArrayOf(), normal)
}

fun Context.box(fill: Int, stroke: Int = 0, cut: Boolean = false, radius: Float = 0f) =
    BoxDrawable(fill, stroke, if (stroke != 0) dp(1).toFloat() else 0f, if (cut) dp(8).toFloat() else 0f, radius)

fun Context.text(
    s: CharSequence = "", font: Cx.Font = Cx.Font.BODY, sp: Float = 14f, color: Int = Cx.TEXT,
    spacing: Float = 0f, upper: Boolean = false, lines: Int = 0,
): TextView = TextView(this).apply {
    typeface = Cx.face(font)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    includeFontPadding = false
    if (spacing != 0f) letterSpacing = spacing
    isAllCaps = upper
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
    text = s
}

fun Context.icon(res: Int, sizeDp: Int, tint: Int): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun Context.vbox(pad: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (pad != 0) setPadding(pad, pad, pad, pad)
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.space(hDp: Int) = View(this).apply { layoutParams = lp(MATCH, dp(hDp)) }

enum class BtnStyle { PRIMARY, OUTLINE, GHOST, DANGER, PLAY, DISABLED }

/**
 * A design button: PRIMARY red outline on a red tint, OUTLINE grey frame, GHOST text only, DANGER for leaving, PLAY the big
 * filled «Играть» with its 3 dp ring. Height 52 dp (PLAY 64 + ring).
 */
fun Context.button(label: CharSequence, style: BtnStyle = BtnStyle.PRIMARY, iconRes: Int = 0, onClick: (() -> Unit)? = null): View {
    val play = style == BtnStyle.PLAY
    val fg = when (style) {
        BtnStyle.PRIMARY, BtnStyle.DANGER -> Cx.ON_PRIMARY
        BtnStyle.OUTLINE -> Cx.TEXT
        BtnStyle.GHOST, BtnStyle.DISABLED -> Cx.MUTED
        BtnStyle.PLAY -> Cx.BG
    }
    val inner = hbox().apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(16), 0)
        background = when (style) {
            BtnStyle.PRIMARY -> pressable(box(Cx.alpha(Cx.ACCENT, 0.10f), Cx.ACCENT, cut = true), box(Cx.alpha(Cx.ACCENT, 0.28f), Cx.ACCENT, cut = true))
            BtnStyle.OUTLINE -> pressable(box(0, Cx.DIM, cut = true), box(Cx.TRACK, Cx.DIM, cut = true))
            BtnStyle.GHOST -> pressable(box(0), box(Cx.TRACK, cut = true))
            BtnStyle.DANGER -> pressable(box(Cx.LINE2, Cx.ACCENT, cut = true), box(Cx.ACCENT, Cx.ACCENT, cut = true))
            BtnStyle.PLAY -> pressable(box(Cx.ACCENT, Cx.ACCENT, cut = true), box(0xFFD91E31.toInt(), Cx.ACCENT, cut = true))
            BtnStyle.DISABLED -> box(Cx.TRACK, Cx.LINE2, cut = true)
        }
        if (iconRes != 0) addView(icon(iconRes, 22, fg), lp(dp(22), dp(22)).margins(r = dp(14)))
        addView(text(label, if (play) Cx.Font.DISPLAY else Cx.Font.DISPLAY_SEMI, if (play) 17f else 13f, fg, 0.08f, upper = true, lines = 2).apply {
            gravity = Gravity.CENTER
        }, lp(WRAP, WRAP))
        isClickable = onClick != null && style != BtnStyle.DISABLED
        if (onClick != null && style != BtnStyle.DISABLED) setOnClickListener { onClick() }
    }
    if (!play) {
        inner.minimumHeight = dp(52)
        inner.layoutParams = lp(MATCH, dp(52))
        return inner
    }
    // «Играть»: the ring is a 1 dp frame 4 dp outside the button (a picture, not an effect)
    return FrameLayout(this).apply {
        background = box(0, Cx.alpha(Cx.ACCENT, 0.33f))
        setPadding(dp(4), dp(4), dp(4), dp(4))
        addView(inner, FrameLayout.LayoutParams(MATCH, dp(64)))
        layoutParams = lp(MATCH, dp(72))
    }
}

/** «// РАЗДЕЛ ———»: mono yellow label and a thin line to the right. */
fun Context.section(title: CharSequence): View = hbox().apply {
    addView(text("//  $title", Cx.Font.MONO, 12f, Cx.WARNING, 0.16f, upper = true), lp(WRAP, WRAP))
    addView(View(context).apply { setBackgroundColor(Cx.LINE) }, lp(0, dp(1), 1f).margins(l = dp(10)))
}

fun Context.panel(stroke: Int = Cx.LINE, fill: Int = Cx.PANEL) = vbox().apply { background = box(fill, stroke) }

/** Rows on one card, 1 dp apart (the design's list: a dark frame with lighter rows). */
fun Context.group(): LinearLayout = vbox().apply {
    background = box(Cx.TRACK, Cx.LINE)
    setPadding(dp(1), dp(1), dp(1), dp(1))
}

fun LinearLayout.addRow(row: View) {
    if (childCount > 0) addView(View(context), lp(MATCH, dp(1)))
    addView(row, lp(MATCH, WRAP))
}

/**
 * A list row (52 dp): icon, title (and a second line), on the right a value, a status square or a chevron.
 */
fun Context.row(
    iconRes: Int = 0, title: CharSequence, sub: CharSequence? = null, value: CharSequence? = null,
    status: Int = 0, chevron: Int = 0, titleColor: Int = Cx.TEXT, iconTint: Int = Cx.MUTED, onClick: (() -> Unit)? = null,
): LinearLayout = hbox().apply {
    background = if (onClick != null) pressable(box(Cx.PANEL), box(Cx.TRACK)) else box(Cx.PANEL)
    minimumHeight = dp(52)
    setPadding(dp(15), dp(8), dp(15), dp(8))
    if (iconRes != 0) addView(icon(iconRes, 22, iconTint), lp(dp(22), dp(22)).margins(r = dp(12)))
    val texts = vbox()
    texts.addView(text(title, Cx.Font.BODY_SEMI, 14f, titleColor, lines = 2))
    if (sub != null) texts.addView(text(sub, Cx.Font.BODY, 12f, Cx.MUTED, lines = 2), lp().margins(t = dp(2)))
    addView(texts, lp(0, WRAP, 1f))
    if (value != null) addView(text(value, Cx.Font.MONO, 13f, Cx.TEXT).apply { gravity = Gravity.END }, lp(WRAP, WRAP).margins(l = dp(8)))
    if (status != 0) addView(View(context).apply { setBackgroundColor(status) }, lp(dp(10), dp(10)).margins(l = dp(12)))
    if (chevron != 0) addView(icon(chevron, 20, Cx.ACCENT), lp(dp(20), dp(20)).margins(l = dp(10)))
    if (onClick != null) {
        isClickable = true
        setOnClickListener { onClick() }
    }
}

/** The verdict card (90 dp): coloured frame, icon in a tinted square, title in the colour, text below. */
fun Context.statusCard(color: Int, iconRes: Int, title: CharSequence, sub: CharSequence, iconSize: Int = 56): LinearLayout = hbox().apply {
    background = box(Cx.PANEL, color)
    setPadding(dp(16), dp(16), dp(16), dp(16))
    addView(FrameLayout(context).apply {
        background = box(Cx.alpha(color, 0.08f), color)
        addView(icon(iconRes, if (iconSize >= 56) 30 else 26, color), FrameLayout.LayoutParams(dp(if (iconSize >= 56) 30 else 26), dp(if (iconSize >= 56) 30 else 26), Gravity.CENTER))
    }, lp(dp(iconSize), dp(iconSize)).margins(r = dp(16)))
    val col = vbox()
    col.addView(text(title, Cx.Font.DISPLAY_SEMI, 16f, color, lines = 3))
    col.addView(text(sub, Cx.Font.BODY, 14f, Cx.SOFT, lines = 4), lp().margins(t = dp(6)))
    addView(col, lp(0, WRAP, 1f))
}

/** A warning strip (no network, wrong password…): tinted, icon, title in the colour, explanation. */
fun Context.banner(color: Int, iconRes: Int, title: CharSequence, sub: CharSequence?): LinearLayout = hbox().apply {
    gravity = Gravity.TOP
    background = box(Cx.alpha(color, 0.08f), Cx.alpha(color, 0.40f))
    setPadding(dp(13), dp(13), dp(13), dp(13))
    addView(icon(iconRes, 24, color), lp(dp(24), dp(24)).margins(r = dp(12)))
    val col = vbox()
    col.addView(text(title, Cx.Font.DISPLAY_SEMI, 13f, color, lines = 2))
    if (sub != null) col.addView(text(sub, Cx.Font.BODY, 13f, Cx.SOFT, lines = 4), lp().margins(t = dp(5)))
    addView(col, lp(0, WRAP, 1f))
}

class Field(val view: View, val edit: EditText, private val frame: View, private val error: TextView) {
    fun setError(msg: CharSequence?) {
        frame.background = frame.context.box(Cx.FIELD, if (msg != null) Cx.ACCENT else Cx.LINE2)
        error.text = msg ?: ""
        error.visibility = if (msg != null) View.VISIBLE else View.GONE
    }
    val value: String get() = edit.text.toString()
}

/** A labelled input (52 dp): mono label above, dark box, mono text; a password gets the «eye». */
fun Context.field(label: CharSequence, password: Boolean = false, value: String = ""): Field {
    val col = vbox()
    col.addView(text(label, Cx.Font.MONO, 12f, Cx.MUTED, 0.12f, upper = true), lp().margins(b = dp(6)))
    val frame = hbox().apply { background = box(Cx.FIELD, Cx.LINE2); setPadding(dp(15), 0, dp(12), 0) }
    val edit = EditText(this).apply {
        typeface = Cx.face(Cx.Font.MONO)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Cx.TEXT)
        setHintTextColor(Cx.DIM)
        background = null
        setPadding(0, 0, 0, 0)
        isSingleLine = true
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setText(value)
    }
    frame.addView(edit, lp(0, dp(52), 1f))
    if (password) {
        var shown = false
        frame.addView(icon(site.cubexcraft.mobile.R.drawable.ic_eye, 22, Cx.MUTED).apply {
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                shown = !shown
                edit.inputType = InputType.TYPE_CLASS_TEXT or (if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                edit.typeface = Cx.face(Cx.Font.MONO)
                edit.setSelection(edit.text.length)
            }
        }, lp(dp(22), dp(22)))
    }
    col.addView(frame, lp(MATCH, dp(52)))
    val err = text("", Cx.Font.BODY, 12f, Cx.ACCENT).apply { visibility = View.GONE }
    col.addView(err, lp().margins(t = dp(6)))
    return Field(col, edit, frame, err)
}

/** Equal segments (48 dp): the chosen one red. */
fun Context.segments(labels: List<CharSequence>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
    val row = hbox()
    val views = ArrayList<TextView>()
    fun paint(sel: Int) = views.forEachIndexed { i, v ->
        v.background = if (i == sel) box(Cx.alpha(Cx.ACCENT, 0.14f), Cx.ACCENT) else pressable(box(0, Cx.LINE2), box(Cx.TRACK, Cx.LINE2))
        v.setTextColor(if (i == sel) Cx.ON_SELECTED else Cx.MUTED)
    }
    labels.forEachIndexed { i, s ->
        val v = text(s, Cx.Font.BODY_SEMI, 13f, Cx.MUTED, lines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
            isClickable = true
            setOnClickListener { paint(i); onPick(i) }
        }
        views += v
        row.addView(v, lp(0, dp(48), 1f).margins(l = if (i == 0) 0 else dp(6)))
    }
    paint(selected)
    return row
}

/** On/off (52×30): red with the knob on the right when on. */
fun Context.toggle(on: Boolean, onChange: (Boolean) -> Unit): View = FrameLayout(this).apply {
    var state = on
    val knob = View(context)
    fun paint() {
        background = if (state) box(Cx.ACCENT, Cx.ACCENT) else box(Cx.TRACK, Cx.LINE2)
        knob.setBackgroundColor(if (state) Cx.BG else Cx.DIM)
        knob.layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER_VERTICAL or (if (state) Gravity.END else Gravity.START)).apply {
            setMargins(dp(4), 0, dp(4), 0)
        }
    }
    addView(knob)
    paint()
    isClickable = true
    setOnClickListener { state = !state; paint(); onChange(state) }
    layoutParams = lp(dp(52), dp(30))
}

/** Children in rows, wrapping to the next row (mod chips, friends online). */
class Flow(ctx: Context, private val gapX: Int, private val gapY: Int) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gapY; rowH = 0 }
            x += c.measuredWidth + gapX
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gapY; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gapX
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

/** A progress line (8 dp): the track and the filled part. */
class Bar(ctx: Context, color: Int = Cx.ACCENT, heightDp: Int = 8) : FrameLayout(ctx) {
    private val fill = View(ctx).apply { setBackgroundColor(color) }
    private var fraction = 0f

    init {
        setBackgroundColor(Cx.TRACK)
        addView(fill, LayoutParams(0, MATCH))
        layoutParams = lp(MATCH, ctx.dp(heightDp))
    }

    fun set(f: Float) {
        fraction = f.coerceIn(0f, 1f)
        requestLayout()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        fill.layout(0, 0, ((r - l) * fraction).toInt(), b - t)
    }
}
