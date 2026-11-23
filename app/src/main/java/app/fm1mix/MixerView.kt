package app.fm1mix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * The mixer on a desk: a strip each for the emulated FM-1, the real one and the master. Each has its three EQ knobs
 * (low, mid, high, ±12 dB: drag up or down, a double tap back to 0), a fader (0 to 150 %, a double tap back to
 * 100 %), and a meter with a clip light (red for a second and a half when it would clip; on the master, the
 * limiter's work). Moves go out at once through [onVol] and [onEq]; the activity keeps them per firmware.
 */
class MixerView(ctx: Context) : View(ctx) {
    var onVol: ((strip: Int, pct: Int) -> Unit)? = null
    var onEq: ((strip: Int, band: Int, db: Int) -> Unit)? = null
    val vol = intArrayOf(100, 100, 100)
    /** [strip * 3 + band], dB */
    val eq = IntArray(9)
    /** the limiter's reduction now, dB (≤ 0) */
    var limiterDb = 0f

    private val names = arrayOf("ÉMULÉ", "FM-1", "MASTER")
    private val level = FloatArray(3)
    private val clipAt = LongArray(3)
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val amber = 0xFFF29B38.toInt()

    private class Grab(val strip: Int, val band: Int, var y: Float, val start: Int)   // band -1: the fader
    private val grabs = SparseArray<Grab>()
    private val lastTap = LongArray(12)

    /** the peaks since the last refresh (1 = full scale; above, it would clip) */
    fun levels(emu: Float, ext: Float, out: Float) {
        val now = System.currentTimeMillis()
        val v = floatArrayOf(emu, ext, out)
        for (i in 0 until 3) {
            level[i] = max(v[i], level[i] * 0.86f)
            if (v[i] >= 0.999f) clipAt[i] = now
        }
        invalidate()
    }

    fun set(volumes: IntArray, eqDb: IntArray) {
        System.arraycopy(volumes, 0, vol, 0, 3)
        System.arraycopy(eqDb, 0, eq, 0, 9)
        invalidate()
    }

    // geometry, per strip
    private fun sw() = width / 3f
    private fun knobY() = 46f * d
    private fun knobR() = min(sw() / 9f, 15f * d)
    private fun knobX(s: Int, b: Int) = s * sw() + sw() * (0.2f + 0.3f * b)
    private fun faderTop() = knobY() + knobR() + 34f * d
    private fun faderBot() = height - 26f * d
    private fun faderX(s: Int) = s * sw() + sw() * 0.40f
    private fun meterX(s: Int) = s * sw() + sw() * 0.64f
    private fun volY(pct: Int) = faderBot() - (faderBot() - faderTop()) * pct / 150f

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF2B2D31.toInt())
        val now = System.currentTimeMillis()
        for (s in 0 until 3) {
            val x0 = s * sw()
            if (s > 0) { p.color = 0xFF222327.toInt(); c.drawRect(x0 - 1f * d, 8f * d, x0 + 1f * d, height - 8f * d, p) }
            p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 12f * d
            p.color = if (s == 2) amber else 0xFFD9D6CE.toInt()
            c.drawText(names[s], x0 + sw() / 2f, 18f * d, p)
            // EQ knobs
            for (b in 0 until 3) {
                val cx = knobX(s, b)
                val cy = knobY()
                val kr = knobR()
                val db = eq[s * 3 + b]
                p.style = Paint.Style.FILL; p.color = 0xFF1A1B1E.toInt()
                c.drawCircle(cx, cy, kr, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f * d; p.strokeCap = Paint.Cap.ROUND
                r.set(cx - kr - 4f * d, cy - kr - 4f * d, cx + kr + 4f * d, cy + kr + 4f * d)
                p.color = 0xFF3E4045.toInt(); c.drawArc(r, 135f, 270f, false, p)
                if (db != 0) { p.color = amber; c.drawArc(r, 270f, 135f * db / 12f, false, p) }
                val a = Math.toRadians(-90.0 + 135.0 * db / 12.0)
                p.color = 0xFFEDE9E0.toInt()
                c.drawLine(cx, cy, cx + (kr * 0.8f * Math.cos(a)).toFloat(), cy + (kr * 0.8f * Math.sin(a)).toFloat(), p)
                p.style = Paint.Style.FILL; p.typeface = Typeface.DEFAULT; p.textSize = 10f * d
                p.color = if (db != 0) amber else 0xFF8E8C87.toInt()
                c.drawText(if (db == 0) "GMA"[b].toString() else (if (db > 0) "+$db" else "$db"), cx, cy + kr + 16f * d, p)
            }
            // the fader
            val ft = faderTop()
            val fb = faderBot()
            val fx = faderX(s)
            p.color = 0xFF1A1B1E.toInt()
            r.set(fx - 3f * d, ft, fx + 3f * d, fb); c.drawRoundRect(r, 3f * d, 3f * d, p)
            p.color = 0xFF55575C.toInt()
            val unity = volY(100)
            c.drawRect(fx - 12f * d, unity - 0.75f * d, fx - 6f * d, unity + 0.75f * d, p)
            val ty = volY(vol[s])
            p.color = 0xFF4A4C52.toInt()
            r.set(fx - 16f * d, ty - 9f * d, fx + 16f * d, ty + 9f * d); c.drawRoundRect(r, 4f * d, 4f * d, p)
            p.color = if (vol[s] > 100) amber else 0xFFEDE9E0.toInt()
            c.drawRect(fx - 12f * d, ty - 1f * d, fx + 12f * d, ty + 1f * d, p)
            // the meter: -48 .. 0 dBFS, its clip light above it
            val mx = meterX(s)
            p.color = 0xFF1A1B1E.toInt()
            r.set(mx - 4f * d, ft, mx + 4f * d, fb); c.drawRoundRect(r, 2f * d, 2f * d, p)
            val lv = level[s]
            val db = if (lv <= 1e-5f) -96f else 20f * log10(lv)
            val f = ((db + 48f) / 48f).coerceIn(0f, 1f)
            if (f > 0f) {
                val my = fb - (fb - ft) * f
                p.color = if (db > -6f) 0xFFE8C547.toInt() else 0xFF62D36E.toInt()
                c.drawRect(mx - 3f * d, my, mx + 3f * d, fb, p)
            }
            val clip = now - clipAt[s] < 1500
            p.color = if (clip) 0xFFE5534B.toInt() else 0xFF3A2224.toInt()
            c.drawCircle(mx, ft - 9f * d, 4f * d, p)
            // the value, and on the master the limiter
            p.textSize = 11f * d; p.color = 0xFFB8B5AE.toInt()
            c.drawText("${vol[s]} %", x0 + sw() / 2f, height - 8f * d, p)
            if (s == 2 && limiterDb < -0.5f) {
                p.color = amber; p.textAlign = Paint.Align.LEFT; p.textSize = 10f * d
                c.drawText("LIM %.0f".format(limiterDb), mx + 8f * d, ft - 5f * d, p)
            }
        }
        if (clipAt.any { now - it < 1500 }) postInvalidateDelayed(250)
    }

    /** what a touch at x, y takes: an EQ knob (strip, band) or the strip's fader (band -1) */
    private fun hit(x: Float, y: Float): Pair<Int, Int> {
        val s = (x / sw()).toInt().coerceIn(0, 2)
        if (y < faderTop() - 14f * d) {
            val b = ((x - s * sw()) / sw() * 3f).toInt().coerceIn(0, 2)
            return Pair(s, b)
        }
        return Pair(s, -1)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val (s, b) = hit(e.getX(i), e.getY(i))
                val key = s * 4 + b + 1
                val now = System.currentTimeMillis()
                if (now - lastTap[key] < 300) {                     // a double tap: back to the middle
                    if (b < 0) { vol[s] = 100; onVol?.invoke(s, 100) } else { eq[s * 3 + b] = 0; onEq?.invoke(s, b, 0) }
                    invalidate()
                }
                lastTap[key] = now
                grabs.put(e.getPointerId(i), Grab(s, b, e.getY(i), if (b < 0) vol[s] else eq[s * 3 + b]))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val g = grabs.get(e.getPointerId(i)) ?: continue
                val dy = g.y - e.getY(i)
                if (g.band < 0) {
                    val v = (g.start + dy / (faderBot() - faderTop()) * 150f).toInt().coerceIn(0, 150)
                    if (v != vol[g.strip]) { vol[g.strip] = v; onVol?.invoke(g.strip, v); invalidate() }
                } else {
                    val v = (g.start + dy / (70f * d) * 12f).toInt().coerceIn(-12, 12)
                    val k = g.strip * 3 + g.band
                    if (v != eq[k]) { eq[k] = v; onEq?.invoke(g.strip, g.band, v); invalidate() }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> grabs.remove(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> grabs.clear()
        }
        return true
    }
}
