package app.fm1mix

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray

/**
 * The effects the engine has (native/src/fx.rs: the brief's XY engine), and the pad's choices: the effect, what it
 * goes on (the send: everything, the emulated FM-1 or the real one), HOLD. The engine's list (Native.fxList) gives the
 * order and what this build has; the profiles (assets/fx_profiles.json) give the screen its names and the axes'.
 */
class FxCatalog(json: String, list: String) {
    class Fx(val index: Int, val id: String, val label: String, val type: String, val xName: String, val yName: String)

    val all: List<Fx>
    val types: List<String>

    init {
        val axes = HashMap<String, Pair<String, String>>()
        runCatching {
            val a = JSONArray(json)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                axes[o.getString("id")] = Pair(o.optString("xLabel", o.getJSONObject("x").optString("param")),
                    o.optString("yLabel", o.getJSONObject("y").optString("param")))
            }
        }
        all = list.lines().filter { it.isNotBlank() }.mapIndexed { i, line ->
            val p = line.split('\t')
            val ax = axes[p[0]] ?: Pair("x", "y")
            Fx(i, p[0], p.getOrElse(1) { p[0] }, p.getOrElse(2) { "" }, ax.first, ax.second)
        }
        types = all.map { it.type }.distinct()
    }

    fun ofType(t: String) = all.filter { it.type == t }
}

object FxState {
    var catalog: FxCatalog? = null
    var selected = -1          // the engine's index, -1 none
    var type = ""
    var target = 0             // 0 everything, 1 the emulated FM-1, 2 the real one
    var hold = false
    /** the real FM-1 is played by the FM-1 itself (output on the FM-1): it cannot go through the pad then */
    var fm1Out = false
    val views = mutableListOf<View>()
    var onChange: (() -> Unit)? = null

    fun send() {
        Native.fxSelect(selected)
        Native.fxTarget(target)
        Native.fxHold(hold)
        views.forEach { it.invalidate() }
    }

    val TARGETS = arrayOf("TOUT", "ÉMULÉ", "FM-1")
    /** the axes' names in the profiles' parameters, for the pad's edges */
    val NAMES = mapOf("cutoff" to "fréquence", "res" to "résonance", "time" to "temps", "feedback" to "répétitions",
        "t60" to "durée", "mix" to "mélange", "size" to "taille", "diffusion" to "diffusion", "speed" to "vitesse",
        "depth" to "profondeur", "drive" to "saturation", "tone" to "tonalité", "shift" to "hauteur",
        "fall" to "chute", "length" to "longueur", "decay" to "déclin", "thresh" to "seuil", "ratio" to "ratio")
}

/**
 * The effects page (the middle zone only: the largest). Two thirds: the pad, after the Kaoss Pad: the effect plays
 * while a finger is on it (HOLD keeps it), and where the finger is sets it. The third under it: the effect, picked by
 * its type then its name; its send (on everything, on the emulated FM-1, on the real one); HOLD; the tempo it follows
 * (the MIDI clock when one comes, else its own: TAP sets it).
 */
class FxPadView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val pad = RectF()
    private val amber = 0xFFF29B38.toInt()
    private val chips = ArrayList<Pair<RectF, () -> Unit>>()
    private var finger = -1
    private var fx = 0.5f
    private var fy = 0.5f
    private var down = false
    private val info = FloatArray(3)
    // the two rows of choices scroll sideways when they are longer than the screen
    private val scroll = FloatArray(2)
    private var dragRow = -1
    private var dragX = 0f
    private var dragMoved = false

    override fun onAttachedToWindow() { super.onAttachedToWindow(); FxState.views.add(this) }
    override fun onDetachedFromWindow() { FxState.views.remove(this); super.onDetachedFromWindow() }

    private fun chip(c: Canvas, x: Float, y: Float, h: Float, label: String, on: Boolean, enabled: Boolean, act: () -> Unit): Float {
        p.textSize = 12.5f * d; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
        val w = p.measureText(label) + 24f * d
        r.set(x, y, x + w, y + h)
        p.style = if (on) Paint.Style.FILL else Paint.Style.STROKE
        p.strokeWidth = 1.5f * d
        p.color = when { on -> amber; enabled -> 0xFF5A5C62.toInt(); else -> 0xFF3A3B40.toInt() }
        c.drawRoundRect(r, h / 2f, h / 2f, p)
        p.style = Paint.Style.FILL
        p.color = when { on -> 0xFF1E1F22.toInt(); enabled -> 0xFFD9D6CE.toInt(); else -> 0xFF5A5C62.toInt() }
        c.drawText(label, x + w / 2f, y + h / 2f + 4.5f * d, p)
        if (enabled) chips.add(Pair(RectF(r), act))
        return x + w + 6f * d
    }

    private fun rowTop(row: Int): Float {
        val ctrl = height / 3f
        val rh = (ctrl - 16f * d) / 3f
        return height - ctrl + 8f * d + row * rh
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(0xFF2B2D31.toInt())
        chips.clear()
        val s = FxState
        val cat = s.catalog
        // ---- the pad: two thirds
        pad.set(12f * d, 10f * d, w - 12f * d, h * 2f / 3f - 6f * d)
        p.shader = LinearGradient(pad.left, pad.bottom, pad.right, pad.top, 0xFF1C1D21.toInt(), 0xFF30262A.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(pad, 16f * d, 16f * d, p)
        p.shader = null
        p.color = 0xFF3A3C41.toInt(); p.strokeWidth = 1f * d
        for (k in 1..3) {
            val gx = pad.left + pad.width() * k / 4f
            val gy = pad.top + pad.height() * k / 4f
            c.drawLine(gx, pad.top + 6f * d, gx, pad.bottom - 6f * d, p)
            c.drawLine(pad.left + 6f * d, gy, pad.right - 6f * d, gy, p)
        }
        val cur = cat?.all?.getOrNull(s.selected)
        p.typeface = Typeface.DEFAULT; p.textSize = 11f * d; p.color = 0xFF8E8C87.toInt()
        if (cur != null) {
            val xn = FxState.NAMES[cur.xName] ?: cur.xName
            val yn = FxState.NAMES[cur.yName] ?: cur.yName
            p.textAlign = Paint.Align.CENTER
            c.drawText("$xn →", pad.centerX(), pad.bottom - 8f * d, p)
            c.save(); c.rotate(-90f, pad.left + 14f * d, pad.centerY())
            c.drawText("$yn →", pad.left + 14f * d, pad.centerY(), p)
            c.restore()
            p.textSize = 13f * d; p.typeface = Typeface.DEFAULT_BOLD; p.color = 0xFFB8B5AE.toInt(); p.textAlign = Paint.Align.LEFT
            c.drawText(cur.label, pad.left + 14f * d, pad.top + 22f * d, p)
        } else {
            p.textAlign = Paint.Align.CENTER; p.textSize = 13f * d
            c.drawText("Choisis un effet en dessous", pad.centerX(), pad.centerY(), p)
        }
        val px = pad.left + fx * pad.width()
        val py = pad.bottom - fy * pad.height()
        val lit = down || (s.hold && cur != null && fx >= 0f)
        if (down || s.hold) {
            p.shader = RadialGradient(px, py, 64f * d, if (down) 0x99F29B38.toInt() else 0x55F29B38, 0x00F29B38, Shader.TileMode.CLAMP)
            c.drawCircle(px, py, 64f * d, p)
            p.shader = null
        }
        p.style = if (lit) Paint.Style.FILL else Paint.Style.STROKE; p.strokeWidth = 1.5f * d
        p.color = if (lit) amber else 0xFF55575C.toInt()
        c.drawCircle(px, py, 10f * d, p)
        p.style = Paint.Style.FILL
        // ---- the choices: the third under it
        val rh = (h / 3f - 16f * d) / 3f
        val ch = minOf(rh - 8f * d, 32f * d)
        if (cat != null) {
            // row 1: the types
            c.save(); c.clipRect(0f, rowTop(0), w, rowTop(0) + rh)
            var x = 12f * d - scroll[0]
            for (t in cat.types) x = chip(c, x, rowTop(0) + (rh - ch) / 2f, ch, t.uppercase(), t == s.type, true) {
                s.type = t
                val first = cat.ofType(t).firstOrNull()
                if (cur?.type != t && first != null) s.selected = first.index
                scroll[1] = 0f
                s.onChange?.invoke(); s.send()
            }
            c.restore()
            // row 2: the effects of that type
            c.save(); c.clipRect(0f, rowTop(1), w, rowTop(1) + rh)
            x = 12f * d - scroll[1]
            for (e in cat.ofType(s.type)) x = chip(c, x, rowTop(1) + (rh - ch) / 2f, ch, e.label, e.index == s.selected, true) {
                s.selected = if (s.selected == e.index) -1 else e.index   // (a second tap: no effect)
                s.onChange?.invoke(); s.send()
            }
            c.restore()
        }
        // row 3: the send, HOLD, the tempo
        val y3 = rowTop(2) + (rh - ch) / 2f
        var x3 = 12f * d
        for (i in 0 until 3) {
            val enabled = !(i == 2 && s.fm1Out)
            x3 = chip(c, x3, y3, ch, FxState.TARGETS[i], s.target == i, enabled) { s.target = i; s.onChange?.invoke(); s.send() }
        }
        x3 = chip(c, x3 + 6f * d, y3, ch, "HOLD", s.hold, true) { s.hold = !s.hold; s.onChange?.invoke(); s.send() }
        Native.fxInfo(info)
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 12f * d; p.textAlign = Paint.Align.RIGHT
        val tapW = p.measureText("TAP") + 24f * d
        chip(c, w - 12f * d - tapW, y3, ch, "TAP", false, info[2] < 0.5f) { Native.fxTap(); invalidate() }
        p.typeface = Typeface.DEFAULT; p.textSize = 11f * d; p.textAlign = Paint.Align.RIGHT
        p.color = if (info[2] > 0.5f) amber else 0xFFB8B5AE.toInt()
        c.drawText("%.0f BPM".format(info[0]), w - 18f * d - tapW, y3 + ch / 2f - 1f * d, p)
        p.color = 0xFF8E8C87.toInt(); p.textSize = 9.5f * d
        c.drawText(if (info[2] > 0.5f) "horloge MIDI" else "tempo interne", w - 18f * d - tapW, y3 + ch / 2f + 11f * d, p)
        postInvalidateDelayed(if (down) 16 else 250)
    }

    private fun setFrom(ex: Float, ey: Float) {
        fx = ((ex - pad.left) / pad.width()).coerceIn(0f, 1f)
        fy = ((pad.bottom - ey) / pad.height()).coerceIn(0f, 1f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // the pad (its very edges are the zone's swipe), else the rows (a tap picks, a drag scrolls)
                val edge = 36f * d
                if (pad.contains(e.x, e.y) && e.x > edge && e.x < width - edge) {
                    finger = e.getPointerId(0)
                    setFrom(e.x, e.y)
                    down = true
                    Native.fxTouch(fx, fy, true)
                    invalidate()
                } else if (e.y > rowTop(0)) {
                    dragRow = if (e.y < rowTop(1)) 0 else if (e.y < rowTop(2)) 1 else -1
                    dragX = e.x
                    dragMoved = false
                }
            }
            MotionEvent.ACTION_MOVE -> if (finger >= 0) {
                val i = e.findPointerIndex(finger)
                if (i >= 0) { setFrom(e.getX(i), e.getY(i)); Native.fxTouch(fx, fy, true) }
            } else if (dragRow >= 0) {
                val dx = e.x - dragX
                if (kotlin.math.abs(dx) > 8f * d) dragMoved = true
                if (dragMoved) { scroll[dragRow] = (scroll[dragRow] - dx).coerceIn(0f, 2000f * d); dragX = e.x; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                if (finger >= 0) {
                    finger = -1
                    down = false
                    Native.fxTouch(fx, fy, false)
                    invalidate()
                } else if (!dragMoved) {
                    for ((rect, act) in chips) if (rect.contains(e.x, e.y)) { act(); invalidate(); break }
                }
                dragRow = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                if (finger >= 0) { finger = -1; down = false; Native.fxTouch(fx, fy, false) }
                dragRow = -1
                invalidate()
            }
        }
        return true
    }
}
