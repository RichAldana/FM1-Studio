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
 * The effects the engine has (native/src/fx.rs: the brief's XY engine). The engine's list (Native.fxList) gives the
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

/**
 * The pad's choices, per track (native/src/fx.rs: an engine per track): 0 TOUT (the whole mix), 1 ÉMU, 2 FM-1, 3-6
 * the four loops. Each track its effect, its type shown, its HOLD. Three effects at most at once ([MAX_ACTIVE]): a
 * fourth is refused, and when the phone cannot keep up the last one added is cut (MainActivity watches).
 */
object FxState {
    const val TRACKS = 7
    const val MAX_ACTIVE = 3
    var catalog: FxCatalog? = null
    /** the track the pad shows and plays */
    var track = 1
    val selected = IntArray(TRACKS) { -1 }
    val type = Array(TRACKS) { "" }
    val hold = BooleanArray(TRACKS)
    /** when each track's effect was chosen: the last one added is cut first */
    val addedAt = LongArray(TRACKS)
    /** the real FM-1 is played by the FM-1 itself (output on the FM-1): its track's effect cannot take it then */
    var fm1Out = false
    /** the loops' names on their buttons (LoopBank): tracks 3-6 */
    var loopLabel: (Int) -> String = { "B${it + 1}" }
    val views = mutableListOf<View>()
    var onChange: (() -> Unit)? = null
    /** a fourth effect refused: the message to show */
    var onRefused: ((String) -> Unit)? = null
    /** the finger on a track's pad (Motion.kt hears it) */
    var onPad: ((track: Int, x: Float, y: Float, down: Boolean) -> Unit)? = null
    /** a pattern's menu (a long press on it) */
    var onPatternMenu: ((track: Int, pattern: Int) -> Unit)? = null

    fun name(t: Int) = when (t) {
        0 -> tr("TOUT", "ALL"); 1 -> tr("ÉMU", "EMU"); 2 -> "FM-1"
        else -> loopLabel(t - 3).ifEmpty { "B${t - 2}" }
    }

    fun active() = (0 until TRACKS).count { selected[it] >= 0 }

    /** Track [t] plays effect [idx] (-1 none). False when refused: three already play. */
    fun pick(t: Int, idx: Int): Boolean {
        if (idx >= 0 && selected[t] < 0 && active() >= MAX_ACTIVE) {
            val who = (0 until TRACKS).filter { selected[it] >= 0 }.joinToString(", ") { name(it) }
            onRefused?.invoke(tr("Trois effets jouent déjà ($who) : coupe-en un d'abord.", "Three effects already play ($who): turn one off first."))
            return false
        }
        if (idx >= 0 && selected[t] != idx) addedAt[t] = System.currentTimeMillis()
        selected[t] = idx
        send(t)
        onChange?.invoke()
        return true
    }

    /** the track whose effect came last (-1 none) */
    fun lastAdded(): Int = (0 until TRACKS).filter { selected[it] >= 0 }.maxByOrNull { addedAt[it] } ?: -1

    fun send(t: Int) {
        Native.fxSelect(t, selected[t])
        Native.fxHold(t, hold[t])
        views.forEach { it.invalidate() }
    }

    fun sendAll() { for (t in 0 until TRACKS) send(t) }
}

/**
 * The effects page (the middle zone): the pad of one track, after the Kaoss Pad: the effect plays while a finger is on
 * it (HOLD keeps it, and so does a second finger, which toggles HOLD), and where the finger is sets it, after the live
 * convention: X = what (left dark, slow, long, low; right bright, fast, short, high), Y = how much. Its axes are
 * written on its edges; a stepped axis (tempo divisions, notes, shapes) shows its cells, named, the one under the
 * finger lit.
 *  - at the top, the tracks: TOUT (the whole mix), ÉMU, FM-1, the four loops; a dot: an effect on it (amber), a
 *    pattern playing (green);
 *  - under the pad, its motions (Motion.kt): REC, the length of the next recording, the patterns A B C D (a tap: it
 *    plays from the next bar; an empty one: REC writes there; a long press: its menu), HOLD;
 *  - then the effect, by its type then its name; the tempo (the MIDI clock's, else TAP's).
 * A pattern playing shows its finger on the pad, green.
 */
class FxPadView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val pad = RectF()
    private val amber = 0xFFF29B38.toInt()
    private val green = 0xFF62D36E.toInt()
    private val red = 0xFFE5534B.toInt()
    private val chips = ArrayList<Pair<RectF, () -> Unit>>()
    /** the pattern buttons: their place, track and pattern (a long press opens the menu) */
    private val patRects = ArrayList<Pair<RectF, Int>>()
    private var finger = -1
    private var fx = 0.5f
    private var fy = 0.5f
    private var down = false
    private val info = FloatArray(3)
    // the rows of choices scroll sideways when they are longer than the screen: 0 types, 1 effects, 2 tracks
    private val scroll = FloatArray(3)
    private val rowRect = Array(3) { RectF() }
    private var dragRow = -1
    private var dragX = 0f
    private var dragMoved = false
    private var pressPat = -1
    private var pressLong = false
    private val longPress = Runnable {
        if (pressPat >= 0) {
            pressLong = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            FxState.onPatternMenu?.invoke(FxState.track, pressPat)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); FxState.views.add(this) }
    override fun onDetachedFromWindow() { FxState.views.remove(this); super.onDetachedFromWindow() }

    /** a pill with [label]; [lead]: room left of the text (a track's dot) */
    private fun chip(c: Canvas, x: Float, y: Float, h: Float, label: String, on: Boolean, enabled: Boolean, size: Float = 12.5f,
                     lead: Float = 0f, act: () -> Unit): Float {
        p.textSize = size * d; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
        val w = p.measureText(label) + (if (size < 12f) 18f else 24f) * d + lead
        r.set(x, y, x + w, y + h)
        p.style = if (on) Paint.Style.FILL else Paint.Style.STROKE
        p.strokeWidth = 1.5f * d
        p.color = when { on -> amber; enabled -> 0xFF5A5C62.toInt(); else -> 0xFF3A3B40.toInt() }
        c.drawRoundRect(r, h / 2f, h / 2f, p)
        p.style = Paint.Style.FILL
        p.color = when { on -> 0xFF1E1F22.toInt(); enabled -> 0xFFD9D6CE.toInt(); else -> 0xFF5A5C62.toInt() }
        c.drawText(label, x + lead + (w - lead) / 2f, y + h / 2f + size * 0.36f * d, p)
        if (enabled) chips.add(Pair(RectF(r), act))
        return x + w + 6f * d
    }

    // the layout, top to bottom: the tracks, the pad, the motions, the types, the effects
    private fun trackTop() = 4f * d
    private fun trackH() = 32f * d
    private fun rowH() = 40f * d
    private fun stripH() = 52f * d
    private fun effectsTop() = height - rowH() - 2f * d
    private fun typesTop() = effectsTop() - rowH()
    private fun stripTop() = typesTop() - stripH() - 4f * d

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        c.drawColor(0xFF2B2D31.toInt())
        chips.clear()
        patRects.clear()
        val s = FxState
        val t = s.track
        val cat = s.catalog
        // ---- the tracks
        val th = trackH()
        rowRect[2].set(0f, trackTop(), w, trackTop() + th)
        c.save(); c.clipRect(rowRect[2])
        var x = 12f * d - scroll[2]
        for (k in 0 until FxState.TRACKS) {
            val x0 = x
            // its dot: a pattern playing (green), an effect on it (amber); room is made for it before the name
            val dot = when { Motion.isPlaying(k) -> green; s.selected[k] >= 0 -> if (k == t) 0xFF1E1F22.toInt() else amber; else -> 0 }
            x = chip(c, x, trackTop(), th, s.name(k), k == t, true, 11.5f, if (dot != 0) 8f * d else 0f) { selectTrack(k) }
            if (dot != 0) { p.color = dot; c.drawCircle(x0 + 10f * d, trackTop() + th / 2f, 2.8f * d, p) }
        }
        c.restore()
        // ---- the pad
        pad.set(12f * d, trackTop() + th + 8f * d, w - 12f * d, stripTop() - 6f * d)
        p.shader = LinearGradient(pad.left, pad.bottom, pad.right, pad.top, 0xFF1C1D21.toInt(), 0xFF30262A.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(pad, 16f * d, 16f * d, p)
        p.shader = null
        val cur = cat?.all?.getOrNull(s.selected[t])
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
            c.drawText((cat?.shown(cur.label) ?: cur.label) + " · " + s.name(t), pad.left + 14f * d, pad.top + 22f * d, p)
        } else {
            p.textAlign = Paint.Align.CENTER; p.textSize = 13f * d
            c.drawText(tr("${s.name(t)} : choisis un effet en dessous", "${s.name(t)}: pick an effect below"), pad.centerX(), pad.centerY(), p)
        }
        // the pattern's finger (green), then the hand's (amber)
        val mt = Motion.tracks[t]
        if (Motion.isPlaying(t) && !down && mt.pdown) {
            val gx = pad.left + mt.px * pad.width()
            val gy = pad.bottom - mt.py * pad.height()
            p.shader = RadialGradient(gx, gy, 50f * d, 0x6662D36E, 0x0062D36E, Shader.TileMode.CLAMP)
            c.drawCircle(gx, gy, 50f * d, p)
            p.shader = null
            p.color = green; c.drawCircle(gx, gy, 8f * d, p)
        }
        val px = pad.left + fx * pad.width()
        val py = pad.bottom - fy * pad.height()
        val lit = down || (s.hold[t] && cur != null && fx >= 0f)
        if (down || s.hold[t]) {
            p.shader = RadialGradient(px, py, 64f * d, if (down) 0x99F29B38.toInt() else 0x55F29B38, 0x00F29B38, Shader.TileMode.CLAMP)
            c.drawCircle(px, py, 64f * d, p)
            p.shader = null
        }
        p.style = if (lit) Paint.Style.FILL else Paint.Style.STROKE; p.strokeWidth = 1.5f * d
        p.color = if (lit) amber else 0xFF55575C.toInt()
        c.drawCircle(px, py, 10f * d, p)
        p.style = Paint.Style.FILL
        // ---- the motions: REC, the length, A B C D, HOLD
        drawStrip(c, w, t)
        // ---- the types and the effects (TAP pinned at the right of the types)
        val rh = rowH()
        val ch = minOf(rh - 8f * d, 32f * d)
        Native.fxInfo(info)
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 12f * d
        val tapW = p.measureText("TAP") + 24f * d
        val tempoW = 54f * d
        val y1 = typesTop() + (rh - ch) / 2f
        chip(c, w - 12f * d - tapW, y1, ch, "TAP", false, info[2] < 0.5f) { Native.fxTap(); invalidate() }
        p.typeface = Typeface.DEFAULT; p.textSize = 11f * d; p.textAlign = Paint.Align.RIGHT
        p.color = if (info[2] > 0.5f) amber else 0xFFB8B5AE.toInt()
        c.drawText("%.0f BPM".format(info[0]), w - 18f * d - tapW, y1 + ch / 2f - 1f * d, p)
        p.color = 0xFF8E8C87.toInt(); p.textSize = 9.5f * d
        c.drawText(if (info[2] > 0.5f) tr("horloge MIDI", "MIDI clock") else tr("interne", "internal"), w - 18f * d - tapW, y1 + ch / 2f + 11f * d, p)
        if (cat != null) {
            rowRect[0].set(0f, typesTop(), w - tapW - tempoW - 20f * d, typesTop() + rh)
            c.save(); c.clipRect(rowRect[0])
            x = 12f * d - scroll[0]
            for (ty in cat.types) x = chip(c, x, y1, ch, cat.shown(ty).uppercase(), ty == s.type[t], true) {
                s.type[t] = ty
                scroll[1] = 0f
                s.onChange?.invoke(); invalidate()
            }
            c.restore()
            rowRect[1].set(0f, effectsTop(), w, effectsTop() + rh)
            c.save(); c.clipRect(rowRect[1])
            x = 12f * d - scroll[1]
            val y2 = effectsTop() + (rh - ch) / 2f
            for (e in cat.ofType(s.type[t])) x = chip(c, x, y2, ch, cat.shown(e.label), e.index == s.selected[t], true) {
                s.pick(t, if (s.selected[t] == e.index) -1 else e.index)    // (a second tap: no effect)
            }
            c.restore()
        }
        val busy = Motion.recState(t) != Motion.EMPTY || Motion.isPlaying(t)
        postInvalidateDelayed(if (down || busy) 33 else 250)
    }

    /** REC, the length of the next recording, the patterns, HOLD: [t]'s */
    private fun drawStrip(c: Canvas, w: Float, t: Int) {
        val top = stripTop()
        val h = stripH()
        r.set(12f * d, top, w - 12f * d, top + h)
        p.color = 0xFF1E1F22.toInt(); c.drawRoundRect(r, 12f * d, 12f * d, p)
        val inner = h - 12f * d
        val cy = top + h / 2f
        // REC: a ring, red when it records, blinking when it waits for its bar
        val rs = Motion.recState(t)
        val blink = (System.currentTimeMillis() / 300) % 2 == 0L
        val rx = 18f * d + inner / 2f
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = red
        c.drawCircle(rx, cy, inner / 2f, p)
        p.style = Paint.Style.FILL
        p.color = if (rs == Motion.RECORDING || (rs == Motion.ARMED && blink)) red else 0x66E5534B
        c.drawCircle(rx, cy, if (rs == Motion.RECORDING) inner / 2f - 4f * d else inner / 5f, p)
        if (rs == Motion.RECORDING) {
            p.color = 0xFF1A0706.toInt(); p.textSize = 10f * d; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
            c.drawText("REC", rx, cy + 3.5f * d, p)
        }
        chips.add(Pair(RectF(rx - inner / 2f, top, rx + inner / 2f, top + h), { Motion.tapRec(t) }))
        // the length of the next recording
        var x = rx + inner / 2f + 8f * d
        val bars = Motion.tracks[t].bars
        r.set(x, top + 6f * d, x + 40f * d, top + h - 6f * d)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * d; p.color = 0xFF3A3C41.toInt(); c.drawRoundRect(r, 9f * d, 9f * d, p)
        p.style = Paint.Style.FILL; p.color = 0xFFD9D6CE.toInt(); p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 13f * d; p.textAlign = Paint.Align.CENTER
        c.drawText("$bars", r.centerX(), r.centerY() - 1f * d, p)
        p.typeface = Typeface.DEFAULT; p.textSize = 9f * d; p.color = 0xFF8E8C87.toInt()
        c.drawText(tr("mes.", "bars"), r.centerX(), r.centerY() + 11f * d, p)
        chips.add(Pair(RectF(r), { Motion.cycleBars(t) }))
        x = r.right + 8f * d
        // HOLD at the right
        p.textSize = 12f * d; p.typeface = Typeface.DEFAULT_BOLD
        val holdW = p.measureText("HOLD") + 22f * d
        val hold = FxState.hold[t]
        r.set(w - 18f * d - holdW, top + 8f * d, w - 18f * d, top + h - 8f * d)
        p.style = if (hold) Paint.Style.FILL else Paint.Style.STROKE; p.strokeWidth = 1.5f * d
        p.color = if (hold) amber else 0xFF5A5C62.toInt()
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, p)
        p.style = Paint.Style.FILL; p.color = if (hold) 0xFF1E1F22.toInt() else 0xFFD9D6CE.toInt(); p.textAlign = Paint.Align.CENTER
        c.drawText("HOLD", r.centerX(), r.centerY() + 4.3f * d, p)
        chips.add(Pair(RectF(r), { FxState.hold[t] = !FxState.hold[t]; FxState.send(t); FxState.onChange?.invoke() }))
        // the patterns between
        val right = r.left - 8f * d
        val pw = (right - x - 3 * 5f * d) / 4f
        val target = Motion.tracks[t].target
        for (k in 0 until Motion.PATTERNS) {
            r.set(x + k * (pw + 5f * d), top + 6f * d, x + k * (pw + 5f * d) + pw, top + h - 6f * d)
            val st = Motion.state(t, k)
            val (fill, edge, ink) = when (st) {
                Motion.PLAYING -> Triple(green, green, 0xFF10210F.toInt())
                Motion.RECORDING -> Triple(red, red, 0xFF1A0706.toInt())
                Motion.ARMED -> Triple(if (blink) 0xFF3A1A18.toInt() else 0xFF0E0F11.toInt(), red, 0xFFF3A39E.toInt())
                Motion.NEXT -> Triple(0xFF3B2F20.toInt(), amber, 0xFFF7C489.toInt())
                Motion.READY -> Triple(0xFF1A1F1B.toInt(), 0xFF3E7A46.toInt(), 0xFF9FD8A6.toInt())
                else -> Triple(0xFF0E0F11.toInt(), if (k == target) 0xFF8E8C87.toInt() else 0xFF34363B.toInt(), 0xFF8E8C87.toInt())
            }
            p.style = Paint.Style.FILL; p.color = fill; c.drawRoundRect(r, 9f * d, 9f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = (if (k == target && st == Motion.EMPTY) 1.8f else 1.4f) * d; p.color = edge
            c.drawRoundRect(r, 9f * d, 9f * d, p)
            p.style = Paint.Style.FILL; p.color = ink; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 15f * d; p.textAlign = Paint.Align.CENTER
            c.drawText("ABCD"[k].toString(), r.centerX(), r.centerY() + 1f * d, p)
            p.typeface = Typeface.DEFAULT; p.textSize = 8.5f * d
            val word = when (st) {
                Motion.PLAYING -> tr("JOUE", "PLAYS"); Motion.RECORDING -> "REC"; Motion.ARMED -> tr("ATTEND", "WAITS")
                Motion.NEXT -> tr("ENSUITE", "NEXT"); Motion.READY -> "${Motion.tracks[t].pats[k].bars} " + tr("mes.", "bars")
                else -> if (k == target) tr("ICI", "HERE") else ""
            }
            c.drawText(word, r.centerX(), r.centerY() + 13f * d, p)
            patRects.add(Pair(RectF(r), k))
        }
    }

    private fun selectTrack(k: Int) {
        if (FxState.track == k) return
        // (the finger leaves the track it was on)
        if (down) { Native.fxTouch(FxState.track, fx, fy, false); FxState.onPad?.invoke(FxState.track, fx, fy, false); down = false; finger = -1 }
        FxState.track = k
        val cat = FxState.catalog
        if (FxState.type[k].isEmpty() && cat != null)
            FxState.type[k] = cat.all.getOrNull(FxState.selected[k])?.type ?: cat.types.firstOrNull() ?: ""
        scroll[0] = 0f; scroll[1] = 0f
        FxState.onChange?.invoke()
        invalidate()
    }

    /** an axis' lines: a stepped axis' cells (named, the finger's lit), else quarters, faint */
    private fun drawCells(c: Canvas, g: FxCatalog.Grid?, isX: Boolean) {
        val len = if (isX) pad.width() else pad.height()
        val n = g?.labels?.size ?: 4
        val at = if (isX) fx else fy
        p.strokeWidth = 1f * d
        if (g != null && (down || FxState.hold[FxState.track])) {
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

    private fun touchPad(down: Boolean) {
        val t = FxState.track
        Native.fxTouch(t, fx, fy, down)
        FxState.onPad?.invoke(t, fx, fy, down)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // the pad (its very edges are the zone's swipe), a pattern (a long press: its menu), else the rows
                val edge = 36f * d
                pressPat = -1; pressLong = false
                val pat = patRects.firstOrNull { it.first.contains(e.x, e.y) }
                if (pad.contains(e.x, e.y) && e.x > edge && e.x < width - edge) {
                    finger = e.getPointerId(0)
                    setFrom(e.x, e.y)
                    down = true
                    touchPad(true)
                    invalidate()
                } else if (pat != null) {
                    pressPat = pat.second
                    postDelayed(longPress, 450)
                } else {
                    dragRow = rowRect.indexOfFirst { it.contains(e.x, e.y) }
                    dragX = e.x
                    dragMoved = false
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (finger >= 0 && FxState.catalog != null) {
                // a second finger on the pad while one plays: HOLD on or off
                val i = e.actionIndex
                if (pad.contains(e.getX(i), e.getY(i))) {
                    val t = FxState.track
                    FxState.hold[t] = !FxState.hold[t]
                    FxState.onChange?.invoke(); FxState.send(t)
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
            MotionEvent.ACTION_MOVE -> if (finger >= 0) {
                val i = e.findPointerIndex(finger)
                if (i >= 0) { setFrom(e.getX(i), e.getY(i)); touchPad(true) }
            } else if (dragRow >= 0) {
                val dx = e.x - dragX
                if (kotlin.math.abs(dx) > 8f * d) dragMoved = true
                if (dragMoved) { scroll[dragRow] = (scroll[dragRow] - dx).coerceIn(0f, 2000f * d); dragX = e.x; invalidate() }
            }
            MotionEvent.ACTION_POINTER_UP -> if (finger >= 0 && e.getPointerId(e.actionIndex) == finger) {
                // the first finger lifts while the second stays: the second takes the pad over
                val j = (0 until e.pointerCount).firstOrNull { e.getPointerId(it) != finger }
                if (j != null) { finger = e.getPointerId(j); setFrom(e.getX(j), e.getY(j)); touchPad(true) }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (finger >= 0) {
                    finger = -1
                    down = false
                    touchPad(false)
                    invalidate()
                } else if (pressPat >= 0) {
                    val pat = patRects.firstOrNull { it.first.contains(e.x, e.y) }
                    if (!pressLong && pat != null && pat.second == pressPat) { Motion.tapPattern(FxState.track, pressPat); invalidate() }
                } else if (!dragMoved) {
                    for ((rect, act) in chips) if (rect.contains(e.x, e.y)) { act(); invalidate(); break }
                }
                dragRow = -1; pressPat = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (finger >= 0) { finger = -1; down = false; touchPad(false) }
                dragRow = -1; pressPat = -1
                invalidate()
            }
        }
        return true
    }
}
