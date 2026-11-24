package app.fm1mix

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * The loop player's page (the middle zone): four lanes, one a slot (Loops.kt, native/src/loops.rs). Each lane:
 *  - its button, by colour: black empty (a tap: choose a file), dark with a green edge loaded and stopped, red armed
 *    (waiting for its start), green playing; a tap plays, arms, disarms or stops it;
 *  - its name and length, its wave with where it plays;
 *  - how it plays: → forward, ← backward, ↔ ping-pong, 1× once (else over and over), a tap each;
 *  - its clock step (it starts on the next beat, bar, 2 or 4 bars): a tap, the next one;
 *  - a tap on its name or wave: its settings.
 * Above: the tempo (the FM-1's MIDI clock, else TAP's: a tap is a TAP) and STOP (everything, at once).
 */
class LoopsView(ctx: Context, private val bank: LoopBank) : View(ctx) {
    /** a tap on an empty slot's button or name: choose a file */
    var onPick: ((Int) -> Unit)? = null
    /** a slot's settings */
    var onSettings: ((Int) -> Unit)? = null
    /** a slot's passage screen (which part of its file it plays) */
    var onPassage: ((Int) -> Unit)? = null

    private val info = FloatArray(16)
    private val tempo = FloatArray(3)
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val dash = DashPathEffect(floatArrayOf(6f * d, 4f * d), 0f)
    /** what a touch takes: its place, whether it acts as the finger lands (the slots' buttons: on time) or lifts */
    private class Hit(val rect: RectF, val onDown: Boolean, val f: () -> Unit)
    private val hits = mutableListOf<Hit>()
    private var downAt: Hit? = null

    private val green = 0xFF62D36E.toInt()
    private val red = 0xFFE5534B.toInt()
    private val amber = 0xFFF29B38.toInt()
    private val ink = 0xFFD9D6CE.toInt()
    private val dim = 0xFF8E8C87.toInt()

    init { contentDescription = tr("Boucles", "Loops") }

    /** from the screen's refresh: the slots as they play */
    fun update() {
        if (!isShown) return
        Native.loopInfo(info)
        Native.fxInfo(tempo)
        invalidate()
    }

    private fun hit(rect: RectF, onDown: Boolean = false, f: () -> Unit) { hits.add(Hit(RectF(rect), onDown, f)) }

    override fun onDraw(c: Canvas) {
        hits.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(0xFF2B2D31.toInt())
        val pad = 10f * d
        // the header: BOUCLES, the tempo, STOP
        val hh = 34f * d
        p.typeface = cond; p.textSize = 17f * d; p.textAlign = Paint.Align.LEFT; p.color = ink; p.letterSpacing = 0.08f
        c.drawText(tr("BOUCLES", "LOOPS"), pad + 2f * d, pad + hh / 2f + 6f * d, p)
        p.letterSpacing = 0f
        p.textSize = 13f * d; p.textAlign = Paint.Align.CENTER
        val stopW = p.measureText("STOP") + 22f * d
        r.set(w - pad - stopW, pad + 2f * d, w - pad, pad + hh - 2f * d)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = red
        c.drawRoundRect(r, 8f * d, 8f * d, p)
        p.style = Paint.Style.FILL; p.color = 0xFFF3A39E.toInt()
        c.drawText("STOP", r.centerX(), r.centerY() + 4.5f * d, p)
        hit(r) { Native.loopStopAll(); update() }
        val midi = tempo[2] > 0.5f
        val tTxt = "%.0f BPM · %s".format(tempo[0], if (midi) "FM-1" else "TAP")
        val tW = p.measureText(tTxt) + 30f * d
        r.set(r.left - 8f * d - tW, r.top, r.left - 8f * d, r.bottom)
        p.color = 0xFF1B1C1F.toInt(); c.drawRoundRect(r, 8f * d, 8f * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = 0xFF3A3C41.toInt(); c.drawRoundRect(r, 8f * d, 8f * d, p)
        p.style = Paint.Style.FILL
        // the beat's light: lit on each beat
        val onBeat = (tempo[1] - Math.floor(tempo[1].toDouble())).toFloat() < 0.2f
        p.color = if (onBeat) (if (midi) green else amber) else 0xFF3E4045.toInt()
        c.drawCircle(r.left + 12f * d, r.centerY(), 3.5f * d, p)
        p.color = ink; c.drawText(tTxt, r.centerX() + 6f * d, r.centerY() + 4.5f * d, p)
        if (!midi) hit(r) { Native.fxTap(); update() }

        // the lanes
        val top = pad + hh + 6f * d
        val gap = 7f * d
        val lh = (h - top - pad - 3 * gap) / 4f
        for (i in 0 until 4) drawLane(c, i, pad, top + i * (lh + gap), w - pad, lh)
    }

    private fun drawLane(c: Canvas, i: Int, x0: Float, y0: Float, x1: Float, lh: Float) {
        val s = bank.slots[i]
        val state = info[4 * i].toInt()
        val at = info[4 * i + 1]
        r.set(x0, y0, x1, y0 + lh)
        p.style = Paint.Style.FILL; p.color = 0xFF1B1C1F.toInt()
        c.drawRoundRect(r, 12f * d, 12f * d, p)
        val inset = 7f * d
        // the button
        val bw = minOf(64f * d, lh - 2 * inset + 8f * d)
        val b = RectF(x0 + inset, y0 + inset, x0 + inset + bw, y0 + lh - inset)
        val (fill, edge, txt, label) = when {
            s.busy -> Quad(0xFF26272B.toInt(), 0xFF5A5C62.toInt(), dim, "…")
            state == 3 -> Quad(green, green, 0xFF10210F.toInt(), tr("JOUE", "PLAYS"))
            state == 4 -> Quad(green, green, 0xFF10210F.toInt(), tr("FIN…", "ENDS…"))
            state == 2 -> Quad(red, red, 0xFF1A0706.toInt(), tr("ATTEND", "WAITS"))
            state == 1 -> Quad(0xFF1A1F1B.toInt(), 0xFF3E7A46.toInt(), 0xFF9FD8A6.toInt(), tr("PRÊTE", "READY"))
            else -> Quad(0xFF0E0F11.toInt(), 0xFF3A3C41.toInt(), dim, tr("VIDE", "EMPTY"))
        }
        p.style = Paint.Style.FILL; p.color = fill
        c.drawRoundRect(b, 10f * d, 10f * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = edge
        if (state == 0 && !s.busy) p.pathEffect = dash
        c.drawRoundRect(b, 10f * d, 10f * d, p)
        p.pathEffect = null; p.style = Paint.Style.FILL
        p.typeface = cond; p.textAlign = Paint.Align.CENTER; p.color = txt
        p.textSize = 21f * d; c.drawText("${i + 1}", b.centerX(), b.centerY() + 2f * d, p)
        p.textSize = 10.5f * d; c.drawText(label, b.centerX(), b.centerY() + 16f * d, p)
        if (state == 0 && !s.busy) hit(b) { onPick?.invoke(i) }
        else if (!s.busy) hit(b, onDown = true) { Native.loopTap(i); update() }

        // the rest: name, wave, modes, trigger
        val lx = b.right + 10f * d
        val rx = x1 - inset
        val nameY = y0 + inset + 13f * d
        p.textAlign = Paint.Align.LEFT; p.textSize = 15f * d; p.typeface = cond
        val empty = state == 0 && !s.busy
        val name = when {
            s.missing -> s.name + tr(" (fichier introuvable)", " (file not found)")
            empty -> tr("+ Charger un WAV (30 s au plus)", "+ Load a WAV (30 s at most)")
            else -> s.name
        }
        p.textSize = 11f * d; p.typeface = Typeface.DEFAULT
        val len = if (!empty && s.seconds > 0f) "%.1f s".format(s.seconds) else ""
        // the passage button: [ ] at the right of the name row, for a slot that has a file
        var lenRight = rx
        if (!empty && s.uri != null && !s.busy) {
            val pb = RectF(rx - 34f * d, nameY - 16f * d, rx, nameY + 6f * d)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * d; p.color = amber
            c.drawRoundRect(pb, 6f * d, 6f * d, p)
            // two brackets around a short bar: the part of the file
            val cx = pb.centerX(); val cy = pb.centerY()
            p.strokeWidth = 1.6f * d
            c.drawLine(cx - 9f * d, cy - 5f * d, cx - 9f * d, cy + 5f * d, p)
            c.drawLine(cx - 9f * d, cy - 5f * d, cx - 6f * d, cy - 5f * d, p); c.drawLine(cx - 9f * d, cy + 5f * d, cx - 6f * d, cy + 5f * d, p)
            c.drawLine(cx + 9f * d, cy - 5f * d, cx + 9f * d, cy + 5f * d, p)
            c.drawLine(cx + 9f * d, cy - 5f * d, cx + 6f * d, cy - 5f * d, p); c.drawLine(cx + 9f * d, cy + 5f * d, cx + 6f * d, cy + 5f * d, p)
            p.style = Paint.Style.FILL
            c.drawRect(cx - 4f * d, cy - 1f * d, cx + 4f * d, cy + 1f * d, p)
            hit(RectF(pb.left - 4f * d, pb.top - 4f * d, pb.right + 2f * d, pb.bottom + 4f * d)) { onPassage?.invoke(i) }
            lenRight = pb.left - 6f * d
        }
        val lenW = p.measureText(len) + (rx - lenRight)
        p.color = dim; p.textAlign = Paint.Align.RIGHT; c.drawText(len, lenRight, nameY, p)
        p.textAlign = Paint.Align.LEFT; p.textSize = 15f * d; p.typeface = cond
        p.color = if (empty || s.missing) dim else ink
        c.drawText(ellipsize(name, rx - lx - lenW - 8f * d), lx, nameY, p)
        // the modes and the trigger, at the bottom
        val ch = 22f * d
        val cy = y0 + lh - inset - ch
        var x = lx
        val modes = listOf("→" to (s.dir == 0), "←" to (s.dir == 1), "↔" to (s.dir == 2), "1×" to s.once)
        p.textSize = 12.5f * d; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
        for ((k, m) in modes.withIndex()) {
            val mw = maxOf(28f * d, p.measureText(m.first) + 14f * d)
            r.set(x, cy, x + mw, cy + ch)
            p.color = if (m.second && !empty) amber else 0xFF2A2B30.toInt()
            c.drawRoundRect(r, 6f * d, 6f * d, p)
            p.color = if (m.second && !empty) 0xFF1E1F22.toInt() else 0xFFB8B5AE.toInt()
            c.drawText(m.first, r.centerX(), r.centerY() + 4.5f * d, p)
            if (!empty) hit(RectF(r.left, r.top - 6f * d, r.right, r.bottom + 4f * d)) { mode(i, k) }
            x += mw + 4f * d
        }
        val tTxt = if (empty) "—" else LoopBank.trigShort(s)
        p.typeface = cond; p.textSize = 11.5f * d
        val clock = s.trig == 0
        val tw = minOf(p.measureText(tTxt) + 14f * d, rx - x - 4f * d)
        if (tw > 30f * d) {
            r.set(rx - tw, cy, rx, cy + ch)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d
            p.color = if (clock) 0xFF4E7FB8.toInt() else 0xFF8A6FB0.toInt()
            c.drawRoundRect(r, 6f * d, 6f * d, p)
            p.style = Paint.Style.FILL; p.color = if (clock) 0xFF9CC0EA.toInt() else 0xFFC9B3EA.toInt()
            c.drawText(ellipsize(tTxt, tw - 10f * d), r.centerX(), r.centerY() + 4f * d, p)
            if (!empty) hit(r) { bank.nextQuant(i); invalidate() }    // (a tap: the next step)
        }
        // the wave, between
        val wt = nameY + 6f * d
        val wb = cy - 5f * d
        if (wb - wt > 6f * d) {
            val wr = RectF(lx, wt, rx, wb)
            val n = s.wave.size
            val col = when (state) { 3, 4 -> green; 2 -> red; 1 -> 0xFF62D36E.toInt(); else -> 0xFF3A3C41.toInt() }
            if (n > 0 && !empty) {
                val bwid = wr.width() / n
                for (k in 0 until n) {
                    val v = maxOf(0.04f, s.wave[k])
                    val hh = v * wr.height()
                    val played = (k + 0.5f) / n <= at && state >= 3
                    p.color = col; p.alpha = if (played || state == 1) 255 else 120
                    if (state == 1) p.alpha = 150
                    c.drawRect(wr.left + k * bwid + 0.5f, wr.centerY() - hh / 2f, wr.left + (k + 1) * bwid - 0.5f, wr.centerY() + hh / 2f, p)
                }
                p.alpha = 255
                if (state >= 3) {
                    p.color = 0xFFFFFFFF.toInt()
                    val px = wr.left + at * wr.width()
                    c.drawRect(px - 1f * d, wr.top, px + 1f * d, wr.bottom, p)
                }
            } else {
                p.color = 0xFF2A2B30.toInt(); c.drawRect(wr.left, wr.centerY() - 1f * d, wr.right, wr.centerY() + 1f * d, p)
            }
            if (empty) hit(RectF(lx, y0, rx, cy)) { onPick?.invoke(i) } else hit(RectF(lx, y0, rx, cy)) { onSettings?.invoke(i) }
        }
    }

    private data class Quad(val a: Int, val b: Int, val c: Int, val d: String)

    /** → ← ↔ pick the direction; 1× turns "once" on and off */
    private fun mode(i: Int, k: Int) {
        val s = bank.slots[i]
        if (k == 3) s.once = !s.once else s.dir = k
        bank.apply(i)
        invalidate()
    }

    private fun ellipsize(t: String, maxW: Float): String {
        if (p.measureText(t) <= maxW) return t
        var n = t.length
        while (n > 1 && p.measureText(t.substring(0, n) + "…") > maxW) n--
        return t.substring(0, n) + "…"
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // (the first that holds the point: the chips are listed before the lane's body)
                val h = hits.firstOrNull { it.rect.contains(e.x, e.y) }
                if (h != null && h.onDown) { performClick(); h.f(); downAt = null } else downAt = h
                return true
            }
            MotionEvent.ACTION_UP -> {
                val h = hits.firstOrNull { it.rect.contains(e.x, e.y) }
                if (h != null && h.rect == downAt?.rect) { performClick(); h.f() }   // (redrawn meanwhile: the same place)
                downAt = null
            }
            MotionEvent.ACTION_CANCEL -> downAt = null
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
