package app.fm1mix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** The top bar's drawn icons, in a box of [s] x [s] centred on cx, cy (shared by the round buttons and the switches). */
object BarIcons {
    const val FORK = 0
    const val PHONE = 1
    const val MACHINE = 2
    const val SPEAKER = 3
    const val CONTROL = 4     // a knob: who plays the emulated FM-1

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
