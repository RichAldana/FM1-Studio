package app.fm1mix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * The MOTION page (the middle zone): every track's motions at a glance (Motion.kt), a row each: the track and its
 * effect, its four patterns (their gesture drawn small, their state by colour: green plays, amber plays next, red
 * records or waits for its bar, a green edge holds a gesture, dashed empty), its REC.
 * A tap on a pattern: it plays from the next bar (on the one playing: it stops there; on an empty one: REC writes
 * there); a long press: its menu (play, write over, add over, length, copy, erase). STOP: every pattern stops on the
 * next bar.
 */
class MotionView(ctx: Context) : View(ctx) {
    var onPatternMenu: ((Int, Int) -> Unit)? = null

    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val path = Path()
    private val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val green = 0xFF62D36E.toInt()
    private val red = 0xFFE5534B.toInt()
    private val amber = 0xFFF29B38.toInt()
    private val ink = 0xFFD9D6CE.toInt()
    private val dim = 0xFF8E8C87.toInt()
    private class Hit(val rect: RectF, val track: Int, val pattern: Int, val f: () -> Unit)
    private val hits = ArrayList<Hit>()
    private var press: Hit? = null
    private var long = false
    private val longPress = Runnable {
        val h = press ?: return@Runnable
        if (h.pattern >= 0) { long = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onPatternMenu?.invoke(h.track, h.pattern) }
    }

    init { contentDescription = "Motion" }

    override fun onDraw(c: Canvas) {
        hits.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(0xFF2B2D31.toInt())
        val pad = 10f * d
        // the header
        p.typeface = cond; p.textSize = 17f * d; p.textAlign = Paint.Align.LEFT; p.color = ink; p.letterSpacing = 0.08f
        c.drawText("MOTION", pad + 2f * d, pad + 22f * d, p)
        p.letterSpacing = 0f
        p.textSize = 13f * d; p.textAlign = Paint.Align.CENTER
        val stopW = p.measureText("STOP") + 22f * d
        r.set(w - pad - stopW, pad + 4f * d, w - pad, pad + 32f * d)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = red; c.drawRoundRect(r, 8f * d, 8f * d, p)
        p.style = Paint.Style.FILL; p.color = 0xFFF3A39E.toInt(); c.drawText("STOP", r.centerX(), r.centerY() + 4.5f * d, p)
        hits.add(Hit(RectF(r), -1, -1) { Motion.stopAll() })
        p.typeface = Typeface.DEFAULT; p.textSize = 11f * d; p.color = dim; p.textAlign = Paint.Align.RIGHT
        c.drawText(tr("tout change à la mesure · 16 pas", "everything changes on the bar · 16 steps"), r.left - 10f * d, r.centerY() + 4f * d, p)
        // the column heads
        val nameW = 92f * d
        val recW = 44f * d
        val top = pad + 40f * d
        val cellsX = pad + nameW
        val cellW = (w - pad - recW - 6f * d - cellsX - 3 * 5f * d) / 4f
        p.textSize = 10f * d; p.color = dim; p.textAlign = Paint.Align.LEFT
        c.drawText(tr("PISTE · EFFET", "TRACK · EFFECT"), pad + 4f * d, top + 8f * d, p)
        p.textAlign = Paint.Align.CENTER
        for (k in 0 until 4) c.drawText("ABCD"[k].toString(), cellsX + k * (cellW + 5f * d) + cellW / 2f, top + 8f * d, p)
        c.drawText("REC", w - pad - recW / 2f, top + 8f * d, p)
        // the rows
        val rowsTop = top + 14f * d
        val gap = 5f * d
        val rh = (h - rowsTop - pad - (Motion.TRACKS - 1) * gap) / Motion.TRACKS
        val blink = (System.currentTimeMillis() / 300) % 2 == 0L
        val cat = FxState.catalog
        var busy = false
        for (t in 0 until Motion.TRACKS) {
            val y0 = rowsTop + t * (rh + gap)
            r.set(pad, y0, w - pad, y0 + rh)
            p.color = if (t == FxState.track) 0xFF26272B.toInt() else 0xFF1B1C1F.toInt()
            c.drawRoundRect(r, 9f * d, 9f * d, p)
            // the track and its effect
            p.typeface = cond; p.textSize = 14f * d; p.textAlign = Paint.Align.LEFT; p.color = if (t == FxState.track) amber else ink
            c.drawText(ellipsize(FxState.name(t), nameW - 12f * d), pad + 8f * d, y0 + rh / 2f - 1f * d, p)
            p.typeface = Typeface.DEFAULT; p.textSize = 10.5f * d; p.color = 0xFFB8B5AE.toInt()
            val fxName = cat?.all?.getOrNull(FxState.selected[t])?.let { cat.shown(it.label) } ?: "—"
            c.drawText(ellipsize(fxName, nameW - 12f * d), pad + 8f * d, y0 + rh / 2f + 12f * d, p)
            hits.add(Hit(RectF(pad, y0, cellsX, y0 + rh), t, -1) { FxState.track = t; FxState.onChange?.invoke(); FxState.views.forEach { it.invalidate() } })
            // the patterns
            for (k in 0 until Motion.PATTERNS) {
                val cell = RectF(cellsX + k * (cellW + 5f * d), y0 + 4f * d, cellsX + k * (cellW + 5f * d) + cellW, y0 + rh - 4f * d)
                val st = Motion.state(t, k)
                if (st == Motion.PLAYING || st == Motion.RECORDING || st == Motion.ARMED || st == Motion.NEXT) busy = true
                val (fill, edge, line) = when (st) {
                    Motion.PLAYING -> Triple(green, green, 0xFF10210F.toInt())
                    Motion.RECORDING -> Triple(red, red, 0xFF1A0706.toInt())
                    Motion.ARMED -> Triple(if (blink) 0xFF3A1A18.toInt() else 0xFF0E0F11.toInt(), red, 0xFFF3A39E.toInt())
                    Motion.NEXT -> Triple(0xFF3B2F20.toInt(), amber, 0xFFF7C489.toInt())
                    Motion.READY -> Triple(0xFF1A1F1B.toInt(), 0xFF3E7A46.toInt(), 0xFF9FD8A6.toInt())
                    else -> Triple(0xFF0E0F11.toInt(), if (Motion.tracks[t].target == k) 0xFF8E8C87.toInt() else 0xFF34363B.toInt(), dim)
                }
                p.style = Paint.Style.FILL; p.color = fill; c.drawRoundRect(cell, 7f * d, 7f * d, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f * d; p.color = edge; c.drawRoundRect(cell, 7f * d, 7f * d, p)
                // its gesture, small
                val cv = Motion.curve(t, k, 16)
                if (cv != null) {
                    path.reset()
                    for (i in cv.indices) {
                        val x = cell.left + 6f * d + (cell.width() - 12f * d) * i / (cv.size - 1)
                        val y = cell.bottom - 7f * d - (cell.height() - 14f * d) * cv[i]
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    p.color = line; p.strokeWidth = 1.8f * d; c.drawPath(path, p)
                }
                p.style = Paint.Style.FILL
                if (cv == null && st == Motion.EMPTY && Motion.tracks[t].target == k) {
                    p.color = dim; p.textSize = 9f * d; p.textAlign = Paint.Align.CENTER
                    c.drawText(tr("ICI", "HERE"), cell.centerX(), cell.centerY() + 3f * d, p)
                }
                hits.add(Hit(cell, t, k) { Motion.tapPattern(t, k) })
            }
            // REC
            val rs = Motion.recState(t)
            if (rs != Motion.EMPTY) busy = true
            val cx = w - pad - recW / 2f
            val cy = y0 + rh / 2f
            val rad = minOf(rh / 2f - 5f * d, 16f * d)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = if (rs == Motion.EMPTY) 0xFF5A3A38.toInt() else red
            c.drawCircle(cx, cy, rad, p)
            p.style = Paint.Style.FILL
            p.color = if (rs == Motion.RECORDING || (rs == Motion.ARMED && blink)) red else 0x66E5534B
            c.drawCircle(cx, cy, if (rs == Motion.RECORDING) rad - 4f * d else rad / 2.4f, p)
            hits.add(Hit(RectF(cx - rad - 4f * d, y0, cx + rad + 4f * d, y0 + rh), t, -1) { Motion.tapRec(t) })
        }
        postInvalidateDelayed(if (busy) 60 else 300)
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
                long = false
                press = hits.firstOrNull { it.rect.contains(e.x, e.y) }
                if (press?.pattern ?: -1 >= 0) postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val h = hits.firstOrNull { it.rect.contains(e.x, e.y) }
                if (!long && h != null && press != null && h.rect == press!!.rect) { performClick(); h.f(); invalidate() }
                press = null
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); press = null }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
