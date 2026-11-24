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
import org.json.JSONObject

/**
 * The effects the engine has (native/src/fx.rs: the brief's XY engine), and the pad's choices: the effect, what it
 * goes on (the send: everything, the emulated FM-1 or the real one), HOLD. The engine's list (Native.fxList) gives the
 * order and what this build has; the profiles (assets/fx_profiles.json) give the screen its names and the axes'.
 */
class FxCatalog(json: String, list: String) {
    /** a stepped axis' cells, low to high: their labels (steps, or the notes of a scale) */
    class Grid(val labels: List<String>)
    class Fx(val index: Int, val id: String, val label: String, val type: String, val xLabel: String, val yLabel: String,
             val xGrid: Grid?, val yGrid: Grid?)

    val all: List<Fx>
    val types: List<String>
    /** the profiles' French names (label, category) -> their English ones */
    private val en = HashMap<String, String>()

    /** the first stepped mapping of an axis (an object or a list of them), as cells */
    private fun grid(axis: Any?): Grid? {
        val maps = when (axis) { is JSONArray -> (0 until axis.length()).map { axis.getJSONObject(it) }; is JSONObject -> listOf(axis); else -> emptyList() }
        val m = maps.firstOrNull { it.optString("curve") == "steps" || it.optString("curve") == "scale" } ?: return null
        if (m.optString("curve") == "steps") {
            val named = m.optJSONArray(if (Lang.fr) "stepLabels" else "stepLabelsEn") ?: m.optJSONArray("stepLabels")
            val steps = named ?: m.optJSONArray("steps") ?: return null
            return Grid((0 until steps.length()).map { steps.getString(it) })
        }
        val sc = m.optJSONArray("scale")
        val keep = if (sc == null) null else (0 until sc.length()).map { sc.getInt(it) }.toSet()
        val lo = Math.round(m.optDouble("min", 0.0)).toInt()
        val hi = Math.round(m.optDouble("max", 12.0)).toInt()
        val notes = (lo..hi).filter { keep == null || keep.isEmpty() || Math.floorMod(it, 12) in keep }
        val isNote = m.optString("param") == "note"
        return Grid(notes.map { if (isNote) PanelView.noteName(it) else if (it > 0) "+$it" else "$it" })
    }

    init {
        val prof = HashMap<String, JSONObject>()
        runCatching {
            val a = JSONArray(json)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                prof[o.getString("id")] = o
                for ((k, ke) in listOf("label" to "labelEn", "category" to "categoryEn"))
                    if (o.has(ke)) en[o.getString(k)] = o.getString(ke)
            }
        }
        all = list.lines().filter { it.isNotBlank() }.mapIndexed { i, line ->
            val p = line.split('\t')
            val o = prof[p[0]]
            fun label(k: String) = o?.optString(if (Lang.fr) k else k + "En", o.optString(k)) ?: ""
            Fx(i, p[0], p.getOrElse(1) { p[0] }, p.getOrElse(2) { "" }, label("xLabel"), label("yLabel"),
                runCatching { grid(o?.opt("x")) }.getOrNull(), runCatching { grid(o?.opt("y")) }.getOrNull())
        }
        types = all.map { it.type }.distinct()
    }

    fun ofType(t: String) = all.filter { it.type == t }

    /** a profile's name or category as shown: in English when the phone is not in French */
    fun shown(s: String) = if (Lang.fr) s else en[s] ?: s
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

    val TARGETS = arrayOf(tr("TOUT", "ALL"), tr("ÉMULÉ", "EMULATED"), "FM-1")
}

/**
 * The effects page (the middle zone only: the largest). Two thirds: the pad, after the Kaoss Pad: the effect plays
 * while a finger is on it (HOLD keeps it, and so does a second finger, which toggles HOLD), and where the finger is
 * sets it, after the live convention: X = what (left dark, slow, long, low; right bright, fast, short, high), Y = how
 * much. Its axes are written on its edges; a stepped axis (tempo divisions, notes, shapes) shows its cells, named,
 * the one under the finger lit. The third under it: the effect, picked by its type then its name; its send (on
 * everything, on the emulated FM-1, on the real one); HOLD; the tempo it follows (the MIDI clock when one comes, else
 * its own: TAP sets it).
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
        val cur = cat?.all?.getOrNull(s.selected)
        drawCells(c, cur?.xGrid, true)
        drawCells(c, cur?.yGrid, false)
        p.typeface = Typeface.DEFAULT; p.textSize = 11f * d; p.color = 0xFF8E8C87.toInt()
        if (cur != null) {
            p.textAlign = Paint.Align.CENTER
            c.drawText(cur.xLabel, pad.centerX(), pad.bottom - 8f * d, p)
            c.save(); c.rotate(-90f, pad.left + 14f * d, pad.centerY())
            c.drawText(cur.yLabel, pad.left + 14f * d, pad.centerY(), p)
            c.restore()
            p.textSize = 13f * d; p.typeface = Typeface.DEFAULT_BOLD; p.color = 0xFFB8B5AE.toInt(); p.textAlign = Paint.Align.LEFT
            c.drawText(cat?.shown(cur.label) ?: cur.label, pad.left + 14f * d, pad.top + 22f * d, p)
        } else {
            p.textAlign = Paint.Align.CENTER; p.textSize = 13f * d
            c.drawText(tr("Choisis un effet en dessous", "Pick an effect below"), pad.centerX(), pad.centerY(), p)
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
            for (t in cat.types) x = chip(c, x, rowTop(0) + (rh - ch) / 2f, ch, cat.shown(t).uppercase(), t == s.type, true) {
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
            for (e in cat.ofType(s.type)) x = chip(c, x, rowTop(1) + (rh - ch) / 2f, ch, cat.shown(e.label), e.index == s.selected, true) {
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
        c.drawText(if (info[2] > 0.5f) tr("horloge MIDI", "MIDI clock") else tr("tempo interne", "internal tempo"), w - 18f * d - tapW, y3 + ch / 2f + 11f * d, p)
        postInvalidateDelayed(if (down) 16 else 250)
    }

    /** an axis' lines: a stepped axis' cells (named, the finger's lit), else quarters, faint */
    private fun drawCells(c: Canvas, g: FxCatalog.Grid?, isX: Boolean) {
        val len = if (isX) pad.width() else pad.height()
        val n = g?.labels?.size ?: 4
        val at = if (isX) fx else fy
        p.strokeWidth = 1f * d
        if (g != null && (down || FxState.hold)) {
            // the cell under the finger (the engine keeps it a little past its edges)
            val k = (at * n).toInt().coerceIn(0, n - 1)
            p.color = 0x22F29B38
            if (isX) c.drawRect(pad.left + len * k / n, pad.top, pad.left + len * (k + 1) / n, pad.bottom, p)
            else c.drawRect(pad.left, pad.bottom - len * (k + 1) / n, pad.right, pad.bottom - len * k / n, p)
        }
        p.color = if (g != null) 0xFF4A4C52.toInt() else 0xFF3A3C41.toInt()
        for (k in 1 until n) {
            val v = len * k / n
            if (isX) c.drawLine(pad.left + v, pad.top + 6f * d, pad.left + v, pad.bottom - 6f * d, p)
            else c.drawLine(pad.left + 6f * d, pad.bottom - v, pad.right - 6f * d, pad.bottom - v, p)
        }
        if (g == null) return
        // the names, as many as fit
        p.textSize = 10f * d; p.typeface = Typeface.DEFAULT; p.textAlign = Paint.Align.CENTER; p.color = 0xFF9A978F.toInt()
        val cell = len / n
        val widest = g.labels.maxOf { p.measureText(it) } + 6f * d
        val every = if (isX) maxOf(1, Math.ceil((widest / cell).toDouble()).toInt()) else maxOf(1, Math.ceil((14f * d / cell).toDouble()).toInt())
        for (k in g.labels.indices step every) {
            val mid = cell * (k + 0.5f)
            if (isX) c.drawText(g.labels[k], pad.left + mid, pad.bottom - 22f * d, p)
            else { p.textAlign = Paint.Align.RIGHT; c.drawText(g.labels[k], pad.right - 8f * d, pad.bottom - mid + 4f * d, p) }
        }
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
            MotionEvent.ACTION_POINTER_DOWN -> if (finger >= 0 && FxState.catalog != null) {
                // a second finger on the pad while one plays: HOLD on or off
                val i = e.actionIndex
                if (pad.contains(e.getX(i), e.getY(i))) {
                    FxState.hold = !FxState.hold
                    FxState.onChange?.invoke(); FxState.send()
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
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
            MotionEvent.ACTION_POINTER_UP -> if (finger >= 0 && e.getPointerId(e.actionIndex) == finger) {
                // the first finger lifts while the second stays: the second takes the pad over
                val j = (0 until e.pointerCount).firstOrNull { e.getPointerId(it) != finger }
                if (j != null) { finger = e.getPointerId(j); setFrom(e.getX(j), e.getY(j)); Native.fxTouch(fx, fy, true) }
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
