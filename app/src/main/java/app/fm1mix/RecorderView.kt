package app.fm1mix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * The recorder, bare: a chrono, the wave of what is recorded drawing itself as it comes (the peaks of the recorded
 * mix, 30 a second, red where it would clip), and one big button. After a take: "Découper" opens it in the slicer.
 */
class RecorderView(ctx: Context) : View(ctx) {
    /** the record button (the activity's toggleRecord: the same as the ● of the bar) */
    var onRec: (() -> Unit)? = null
    /** open the last take in the slicer */
    var onSlice: (() -> Unit)? = null
    /** under the wave: what is recorded and how (Le mix · 24 bits · 44,1 kHz) */
    var info = ""
        set(v) { field = v; invalidate() }
    var recording = false
        private set
    /** a finished take is there to slice */
    var hasTake = false
        set(v) { field = v; invalidate() }

    private var startMs = 0L
    private var stoppedSec = 0.0
    private var peaks = FloatArray(4096)
    private var n = 0
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val r = RectF()
    private var slice = RectF()
    private var btn = RectF()

    fun setRecording(on: Boolean) {
        if (on == recording) return
        recording = on
        if (on) { n = 0; startMs = System.currentTimeMillis(); hasTake = false }
        else stoppedSec = (System.currentTimeMillis() - startMs) / 1000.0
        invalidate()
    }

    /** one peak of the recorded mix (1 = full scale), from the screen's refresh */
    fun addPeak(v: Float) {
        if (!recording) return
        if (n == peaks.size) peaks = peaks.copyOf(n * 2)
        peaks[n++] = v
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(0xFF2B2D31.toInt())
        val red = 0xFFE5534B.toInt()
        val amber = 0xFFF29B38.toInt()
        // the chrono
        val sec = if (recording) (System.currentTimeMillis() - startMs) / 1000.0 else stoppedSec
        val mm = (sec / 60).toInt()
        val ss = sec - mm * 60
        p.typeface = mono; p.textAlign = Paint.Align.CENTER
        p.textSize = minOf(h * 0.13f, 46f * d)
        p.color = if (recording) 0xFFFFFFFF.toInt() else 0xFFB8B5AE.toInt()
        c.drawText("%02d:%04.1f".format(mm, ss), w / 2f, h * 0.15f + p.textSize * 0.35f, p)
        // the wave
        val top = h * 0.27f
        val bot = h * 0.66f
        val mid = (top + bot) / 2f
        r.set(16f * d, top, w - 16f * d, bot)
        p.color = 0xFF222327.toInt()
        c.drawRoundRect(r, 12f * d, 12f * d, p)
        p.color = 0xFF3A3C41.toInt()
        c.drawLine(r.left + 8f * d, mid, r.right - 8f * d, mid, p)
        if (n > 0) {
            val x0 = r.left + 8f * d
            val ww = r.width() - 16f * d
            val cols = minOf(n, ww.toInt() / 3).coerceAtLeast(1)
            val cw = ww / cols
            val half = (bot - top) / 2f - 6f * d
            for (k in 0 until cols) {
                val a = (k.toLong() * n / cols).toInt()
                val b = maxOf(a + 1, ((k + 1).toLong() * n / cols).toInt())
                var v = 0f
                for (i in a until b) v = maxOf(v, peaks[i])
                p.color = when { v >= 1f -> red; recording -> 0xFFF07A5C.toInt(); else -> amber }
                val hh = maxOf(1f * d, minOf(1f, v) * half)
                c.drawRect(x0 + k * cw, mid - hh, x0 + k * cw + maxOf(1f, cw - 1f), mid + hh, p)
            }
        } else {
            p.textSize = 13f * d; p.typeface = Typeface.DEFAULT; p.color = 0xFF6E6F74.toInt()
            c.drawText(if (recording) "…" else "Touche ● pour enregistrer", w / 2f, mid + 4f * d, p)
        }
        // the button
        val br = minOf(h * 0.12f, 34f * d)
        val cy = h * 0.83f
        btn.set(w / 2f - br, cy - br, w / 2f + br, cy + br)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f * d; p.color = 0xFFD9D6CE.toInt()
        c.drawCircle(w / 2f, cy, br, p)
        p.style = Paint.Style.FILL; p.color = red
        if (recording) {
            val q = br * 0.42f
            r.set(w / 2f - q, cy - q, w / 2f + q, cy + q)
            c.drawRoundRect(r, 4f * d, 4f * d, p)
        } else c.drawCircle(w / 2f, cy, br * 0.72f, p)
        // what is recorded, and the way to the slicer
        p.typeface = Typeface.DEFAULT; p.textSize = 12f * d; p.color = 0xFF8E8C87.toInt(); p.textAlign = Paint.Align.LEFT
        c.drawText(info, 18f * d, h * 0.72f + 4f * d, p)
        if (hasTake && !recording) {
            p.textAlign = Paint.Align.RIGHT; p.color = amber; p.textSize = 15f * d
            val tx = w - 20f * d
            c.drawText("Découper ›", tx, cy + 5f * d, p)
            slice.set(tx - p.measureText("Découper ›") - 12f * d, cy - 24f * d, tx + 12f * d, cy + 24f * d)
        } else slice.setEmpty()
        if (recording) postInvalidateDelayed(100)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val pad = 12f * d
            if (e.x >= btn.left - pad && e.x <= btn.right + pad && e.y >= btn.top - pad && e.y <= btn.bottom + pad) {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onRec?.invoke()
            } else if (slice.contains(e.x, e.y)) onSlice?.invoke()
        }
        return true
    }
}
