package app.fm1mix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** The top bar's drawn icons, in a box of [s] x [s] centred on cx, cy (shared by the round buttons and the switches). */
object BarIcons {
    const val FORK = 0
    const val PHONE = 1
    const val MACHINE = 2
    const val SPEAKER = 3
    const val CONTROL = 4     // a knob: who plays the emulated FM-1
    const val PENCIL = 5      // the project's name: rename it
    const val SAVE = 6        // a floppy: save the project
    const val OPEN = 7        // a folder: open a project
    const val EXPORT = 8      // an arrow out of a tray: export, send

    private val path = Path()
    private val r = RectF()

    fun draw(c: Canvas, p: Paint, kind: Int, cx: Float, cy: Float, s: Float, color: Int) {
        p.color = color
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val x0 = cx - s / 2f
        val y0 = cy - s / 2f
        fun X(f: Float) = x0 + f * s
        fun Y(f: Float) = y0 + f * s
        when (kind) {
            FORK -> {                                 // two branches from one trunk, three rings
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.09f
                path.reset()
                path.moveTo(X(0.28f), Y(0.30f)); path.lineTo(X(0.28f), Y(0.40f))
                path.cubicTo(X(0.28f), Y(0.54f), X(0.50f), Y(0.50f), X(0.50f), Y(0.64f))
                path.moveTo(X(0.72f), Y(0.30f)); path.lineTo(X(0.72f), Y(0.40f))
                path.cubicTo(X(0.72f), Y(0.54f), X(0.50f), Y(0.50f), X(0.50f), Y(0.64f))
                c.drawPath(path, p)
                c.drawCircle(X(0.28f), Y(0.20f), s * 0.10f, p)
                c.drawCircle(X(0.72f), Y(0.20f), s * 0.10f, p)
                c.drawCircle(X(0.50f), Y(0.78f), s * 0.10f, p)
            }
            PHONE -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.09f
                r.set(X(0.30f), Y(0.08f), X(0.70f), Y(0.92f)); c.drawRoundRect(r, s * 0.09f, s * 0.09f, p)
                c.drawLine(X(0.43f), Y(0.80f), X(0.57f), Y(0.80f), p)
            }
            MACHINE -> {                              // the FM-1: its screen, its buttons, its keys
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.08f
                r.set(X(0.04f), Y(0.20f), X(0.96f), Y(0.80f)); c.drawRoundRect(r, s * 0.10f, s * 0.10f, p)
                p.style = Paint.Style.FILL
                r.set(X(0.14f), Y(0.30f), X(0.40f), Y(0.54f)); c.drawRect(r, p)
                for (i in 0 until 3) c.drawCircle(X(0.54f + i * 0.13f), Y(0.40f), s * 0.045f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.06f
                c.drawLine(X(0.14f), Y(0.67f), X(0.86f), Y(0.67f), p)
            }
            SPEAKER -> {
                p.style = Paint.Style.FILL
                path.reset()
                path.moveTo(X(0.12f), Y(0.38f)); path.lineTo(X(0.30f), Y(0.38f)); path.lineTo(X(0.52f), Y(0.18f))
                path.lineTo(X(0.52f), Y(0.82f)); path.lineTo(X(0.30f), Y(0.62f)); path.lineTo(X(0.12f), Y(0.62f)); path.close()
                c.drawPath(path, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.08f
                r.set(X(0.42f), Y(0.30f), X(0.78f), Y(0.70f)); c.drawArc(r, -50f, 100f, false, p)
                r.set(X(0.40f), Y(0.14f), X(0.96f), Y(0.86f)); c.drawArc(r, -50f, 100f, false, p)
            }
            CONTROL -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.09f
                c.drawCircle(cx, cy, s * 0.34f, p)
                c.drawLine(cx, cy, cx + s * 0.20f, cy - s * 0.20f, p)
            }
            PENCIL -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.09f
                path.reset()
                path.moveTo(X(0.68f), Y(0.14f)); path.lineTo(X(0.86f), Y(0.32f)); path.lineTo(X(0.36f), Y(0.82f))
                path.lineTo(X(0.14f), Y(0.86f)); path.lineTo(X(0.18f), Y(0.64f)); path.close()
                c.drawPath(path, p)
                c.drawLine(X(0.58f), Y(0.24f), X(0.76f), Y(0.42f), p)
            }
            SAVE -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.08f
                path.reset()
                path.moveTo(X(0.14f), Y(0.12f)); path.lineTo(X(0.70f), Y(0.12f)); path.lineTo(X(0.88f), Y(0.30f))
                path.lineTo(X(0.88f), Y(0.88f)); path.lineTo(X(0.14f), Y(0.88f)); path.close()
                c.drawPath(path, p)
                r.set(X(0.30f), Y(0.12f), X(0.64f), Y(0.34f)); c.drawRect(r, p)
                r.set(X(0.28f), Y(0.56f), X(0.74f), Y(0.88f)); c.drawRect(r, p)
            }
            OPEN -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.08f
                path.reset()
                path.moveTo(X(0.10f), Y(0.82f)); path.lineTo(X(0.10f), Y(0.20f)); path.lineTo(X(0.38f), Y(0.20f))
                path.lineTo(X(0.48f), Y(0.32f)); path.lineTo(X(0.84f), Y(0.32f)); path.lineTo(X(0.84f), Y(0.44f))
                c.drawPath(path, p)
                path.reset()
                path.moveTo(X(0.10f), Y(0.82f)); path.lineTo(X(0.24f), Y(0.46f)); path.lineTo(X(0.94f), Y(0.46f))
                path.lineTo(X(0.80f), Y(0.82f)); path.close()
                c.drawPath(path, p)
            }
            EXPORT -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.09f
                c.drawLine(cx, Y(0.12f), cx, Y(0.62f), p)
                path.reset()
                path.moveTo(X(0.30f), Y(0.32f)); path.lineTo(cx, Y(0.12f)); path.lineTo(X(0.70f), Y(0.32f))
                c.drawPath(path, p)
                path.reset()
                path.moveTo(X(0.18f), Y(0.50f)); path.lineTo(X(0.18f), Y(0.88f)); path.lineTo(X(0.82f), Y(0.88f)); path.lineTo(X(0.82f), Y(0.50f))
                c.drawPath(path, p)
            }
        }
    }
}

/** A round button of the top bar with a drawn icon ([BarIcons]). */
class BarButton(ctx: Context, private val kind: Int, desc: String) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density
    /** drawn in amber: the button asks to be used (no firmware chosen yet) */
    var calling = false
        set(v) { field = v; invalidate() }

    init { contentDescription = desc }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rad = minOf(w, h) / 2f - 1.5f * d
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d
        p.color = if (calling) MainActivity.AMBER else 0xFF4A4B50.toInt()
        c.drawCircle(w / 2f, h / 2f, rad, p)
        BarIcons.draw(c, p, kind, w / 2f, h / 2f, rad * 1.15f, if (calling) MainActivity.AMBER else 0xFFFFFFFF.toInt())
    }
}

/**
 * A two-way switch of the top bar: left the phone, right the FM-1. Its knob carries the icon of the side chosen,
 * the empty side a small icon of what it switches ([what]: [BarIcons.CONTROL] or [BarIcons.SPEAKER]).
 * [enabled] false: drawn dim (the other side is not there).
 */
class ModeSwitch(ctx: Context, private val what: Int, desc: String) : View(ctx) {
    var fm1 = false
        set(v) { field = v; invalidate() }
    var usable = true
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val d = ctx.resources.displayMetrics.density

    init { contentDescription = desc }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val th = minOf(h, w * 0.62f)
        val top = (h - th) / 2f
        r.set(1f * d, top + 1f * d, w - 1f * d, top + th - 1f * d)
        val accent = if (fm1) MainActivity.AMBER else 0xFF8FB8E8.toInt()
        p.style = Paint.Style.FILL; p.color = 0xFF2A2B30.toInt()
        c.drawRoundRect(r, th / 2f, th / 2f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d
        p.color = if (usable) accent else 0xFF4A4B50.toInt()
        c.drawRoundRect(r, th / 2f, th / 2f, p)
        val kr = th / 2f - 3.5f * d
        val kx = if (fm1) w - 1f * d - th / 2f else 1f * d + th / 2f
        val cy = top + th / 2f
        p.style = Paint.Style.FILL; p.color = if (usable) accent else 0xFF55565C.toInt()
        c.drawCircle(kx, cy, kr, p)
        BarIcons.draw(c, p, if (fm1) BarIcons.MACHINE else BarIcons.PHONE, kx, cy, kr * (if (fm1) 1.3f else 1.45f), 0xFF1E1F22.toInt())
        val ox = if (fm1) 1f * d + th / 2f + 1f * d else w - 1f * d - th / 2f - 1f * d
        BarIcons.draw(c, p, what, ox, cy, kr * 1.15f, 0xFF7C7D84.toInt())
    }
}

/** The MIDI lamp: lit while MIDI comes in; amber ring while the FM-1's panel plays the emulated one. */
class MidiLamp(ctx: Context) : View(ctx) {
    var active = false
        set(v) { if (v != field) { field = v; invalidate() } }
    var panel = false
        set(v) { if (v != field) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val d = ctx.resources.displayMetrics.density

    init { contentDescription = "MIDI" }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(1f * d, 1f * d, w - 1f * d, h - 1f * d)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d
        p.color = if (panel) MainActivity.AMBER else 0xFF4A4B50.toInt()
        c.drawRoundRect(r, h / 2f, h / 2f, p)
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.textSize = 8.5f * d
        p.color = if (panel) MainActivity.AMBER else 0xFF9A9BA2.toInt()
        c.drawText("MIDI", w / 2f, h / 2f - 2f * d, p)
        p.color = if (active) MainActivity.AMBER else 0xFF4A4B50.toInt()
        c.drawCircle(w / 2f, h / 2f + 7f * d, 3.5f * d, p)
    }
}

/** The project's name in the bar's second page: a pencil, the name, an amber dot while it changed since saved. */
class NamePill(ctx: Context) : View(ctx) {
    var name = ""
        set(v) { if (v != field) { field = v; invalidate() } }
    var dirty = false
        set(v) { if (v != field) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val d = ctx.resources.displayMetrics.density
    private val cond = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)

    init { contentDescription = tr("Renommer le projet", "Rename the project") }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(1f * d, 1f * d, w - 1f * d, h - 1f * d)
        p.style = Paint.Style.FILL; p.color = 0xFF26272B.toInt(); c.drawRoundRect(r, 10f * d, 10f * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = 0xFF3A3C41.toInt(); c.drawRoundRect(r, 10f * d, 10f * d, p)
        val ic = minOf(16f * d, h * 0.45f)
        BarIcons.draw(c, p, BarIcons.PENCIL, 10f * d + ic / 2f, h / 2f, ic, 0xFF8E8C87.toInt())
        val x0 = 10f * d + ic + 8f * d
        val right = w - (if (dirty) 22f else 10f) * d
        p.style = Paint.Style.FILL; p.typeface = cond; p.textSize = minOf(15f * d, h * 0.42f); p.textAlign = Paint.Align.LEFT
        p.color = 0xFFD9D6CE.toInt()
        var t = name
        if (p.measureText(t) > right - x0) {
            var n = t.length
            while (n > 1 && p.measureText(t.substring(0, n) + "…") > right - x0) n--
            t = t.substring(0, n) + "…"
        }
        c.drawText(t, x0, h / 2f + p.textSize * 0.35f, p)
        if (dirty) { p.color = MainActivity.AMBER; c.drawCircle(w - 13f * d, h / 2f, 4f * d, p) }
    }
}

/** A square button of the bar's second page: an icon over a short word; [accent]: drawn amber (Exporter). */
class LabelButton(ctx: Context, private val kind: Int, private val label: String, private val accent: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val d = ctx.resources.displayMetrics.density

    init { contentDescription = label }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(1f * d, 1f * d, w - 1f * d, h - 1f * d)
        p.style = Paint.Style.FILL; p.color = if (accent) 0xFF3B2F20.toInt() else 0xFF26272B.toInt()
        c.drawRoundRect(r, 10f * d, 10f * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = if (accent) MainActivity.AMBER else 0xFF3A3C41.toInt()
        c.drawRoundRect(r, 10f * d, 10f * d, p)
        val ink = if (accent) 0xFFF7C489.toInt() else 0xFFD9D6CE.toInt()
        val ic = minOf(18f * d, h * 0.42f)
        BarIcons.draw(c, p, kind, w / 2f, h * 0.40f, ic, ink)
        p.style = Paint.Style.FILL; p.typeface = android.graphics.Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
        p.textSize = minOf(9.5f * d, w / maxOf(4, label.length) * 1.6f); p.color = ink
        c.drawText(label, w / 2f, h - 6f * d, p)
    }
}

/**
 * The top bar's pages, side by side: a swipe across the bar (or a tap on the dots under it) shows the other.
 * Page 1 the machine, page 2 the project. The buttons keep their taps: a swipe only starts once the finger went
 * sideways.
 */
class BarPager(ctx: Context, private val pages: List<View>, start: Int, private val onPage: (Int) -> Unit) : android.view.ViewGroup(ctx) {
    var index = start.coerceIn(0, pages.size - 1)
        private set
    private val d = ctx.resources.displayMetrics.density
    private val strip = (9f * d).toInt()
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var x0 = 0f
    private var y0 = 0f
    private var t0 = 0L
    private var dragging = false
    private var offset = 0f
    private var anim: android.animation.ValueAnimator? = null

    init {
        pages.forEach { addView(it) }
        setWillNotDraw(false)
    }

    fun show(i: Int) {
        val to = i.coerceIn(0, pages.size - 1)
        val from = offset + (index - to) * width
        if (to != index) { index = to; onPage(to) }
        anim?.cancel()
        anim = android.animation.ValueAnimator.ofFloat(from, 0f).apply {
            duration = 220
            addUpdateListener { offset = it.animatedValue as Float; place() }
            start()
        }
        invalidate()
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val h = MeasureSpec.getSize(hs)
        val cs = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(h - strip, MeasureSpec.EXACTLY)
        pages.forEach { it.measure(cs, ch) }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        pages.forEach { it.layout(0, 0, r - l, b - t - strip) }
        place()
    }

    private fun place() {
        for ((i, v) in pages.withIndex()) {
            v.translationX = (i - index) * width + offset
            v.visibility = if (kotlin.math.abs(v.translationX) < width) VISIBLE else INVISIBLE
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        // the dots: the page shown long, the other short
        val n = pages.size
        val cy = height - strip / 2f
        var x = width / 2f - (n * 10f * d) / 2f
        p.style = Paint.Style.FILL
        for (i in 0 until n) {
            val long = i == index
            p.color = if (long) MainActivity.AMBER else 0xFF5A5B60.toInt()
            val wl = if (long) 14f * d else 4f * d
            c.drawRoundRect(x, cy - 2f * d, x + wl, cy + 2f * d, 2f * d, 2f * d, p)
            x += wl + 5f * d
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; t0 = e.eventTime; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - x0; val dy = e.y - y0
                if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > 1.5f * kotlin.math.abs(dy)) {
                    dragging = true; anim?.cancel(); x0 = e.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; t0 = e.eventTime; dragging = false; return true }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && kotlin.math.abs(e.x - x0) > slop) { dragging = true; x0 = e.x }
                if (dragging) {
                    var o = e.x - x0
                    // (past the first or last page: it resists)
                    if ((index == 0 && o > 0) || (index == pages.size - 1 && o < 0)) o *= 0.3f
                    offset = o; place()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val fast = e.eventTime - t0 < 250 && kotlin.math.abs(offset) > 20f * d
                    val next = when {
                        (offset < -width * 0.2f || (fast && offset < 0)) -> index + 1
                        (offset > width * 0.2f || (fast && offset > 0)) -> index - 1
                        else -> index
                    }
                    show(next)
                } else if (e.actionMasked == MotionEvent.ACTION_UP && e.y > height - strip * 2.5f) {
                    show((index + 1) % pages.size)          // a tap on the dots
                }
                dragging = false
            }
        }
        return true
    }
}
