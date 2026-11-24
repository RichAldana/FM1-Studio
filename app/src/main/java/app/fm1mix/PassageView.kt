package app.fm1mix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The passage screen (the loop player, "Passage 1"): which part of a file a slot plays, 30 s at most.
 *  - the whole file at the top, the passage's window on it: drag it along;
 *  - the passage in large below, the bar lines from its start (at the tempo of the moment) and the attacks marked:
 *    drag its start and end handles;
 *  - start, length and end, with ◀ ▶ to move start or end by 10 ms;
 *  - the length in bars (free, 1, 2, 4, 8, 16: greyed when it would pass 30 s or the end of the file);
 *  - the magnet when a handle is let go: to the nearest attack (80 ms around), to the zero crossing, or none;
 *  - listen: the passage round and round, as it is now (native/src/loops.rs, the preview).
 * The file is read whole into the engine first (10 min at most): "Lecture…" until then.
 */
class PassageView(ctx: Context, private val title: String, private val fileName: String) : View(ctx) {
    var onBack: (() -> Unit)? = null
    var onOk: ((a: Int, b: Int) -> Unit)? = null

    // the file, once read
    private var rate = 0
    private var frames = 0
    private var onsets = IntArray(0)
    private var overview = FloatArray(0)
    var progress = 0f
        set(v) { field = v; postInvalidate() }
    var error: String? = null
        set(v) { field = v; postInvalidate() }

    // the passage
    private var a = 0
    private var b = 0
    private var bars = 0                 // 0 free
    private var magnet = 0               // 0 attacks, 1 zero crossing, 2 none
    private var playing = false
    private var za = 0
    private var zb = 1
    private var zoom = FloatArray(0)

    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val tempo = FloatArray(3)
    private val amber = 0xFFF29B38.toInt()
    private val ink = 0xFFD9D6CE.toInt()
    private val dim = 0xFF8E8C87.toInt()
    private val green = 0xFF62D36E.toInt()

    // where things are, from the last drawing
    private val ovRect = RectF()
    private val zoomRect = RectF()
    private class Hit(val rect: RectF, val f: () -> Unit)
    private val hits = mutableListOf<Hit>()

    init { contentDescription = title }

    /** The file is in the engine: its rate and length, and the passage to start from. */
    fun ready(rate: Int, frames: Int, a0: Int, b0: Int) {
        this.rate = rate
        this.frames = frames
        onsets = Native.loopSrcOnsets() ?: IntArray(0)
        overview = FloatArray(300).also { Native.loopSrcPeaks(0, frames, it) }
        a = a0.coerceIn(0, maxOf(0, frames - 2))
        b = b0.coerceIn(a + 2, minOf(frames, a + maxLen()))
        rezoom()
        invalidate()
    }

    private fun maxLen() = (30.0 * rate).toInt()
    private fun bpm() = tempo[0].takeIf { it > 20f } ?: 120f
    private fun barFrames() = (4.0 * 60.0 / bpm() * rate).toInt()

    private fun rezoom() {
        val pad = maxOf(1, (b - a) / 20)
        za = maxOf(0, a - pad)
        zb = minOf(frames, b + pad)
        val bins = maxOf(60, ((width - paddingLeft - paddingRight - 28f * d) / (3f * d)).toInt())
        zoom = FloatArray(bins).also { Native.loopSrcPeaks(za, zb, it) }
    }

    private fun changed() {
        if (playing) Native.loopSrcPreview(a, b, true)
        invalidate()
    }

    /** frame a..b kept inside the file, 30 s at most, at least 10 ms */
    private fun clampAB() {
        val min = maxOf(2, rate / 100)
        a = a.coerceIn(0, maxOf(0, frames - min))
        b = b.coerceIn(a + min, minOf(frames, a + maxLen()))
    }

    private fun snap(f: Int): Int = when (magnet) {
        0 -> {
            val near = onsets.minByOrNull { abs(it - f) }
            if (near != null && abs(near - f) <= rate * 0.08) near else f
        }
        1 -> Native.loopSrcZero(f, rate * 3 / 1000)
        else -> f
    }

    private fun setBars(n: Int) {
        bars = n
        if (n > 0) b = a + n * barFrames()
        clampAB(); rezoom(); changed()
    }

    private fun time(f: Int): String {
        val s = f.toDouble() / maxOf(1, rate)
        val m = (s / 60).toInt()
        val rest = s - m * 60
        return "%d:%05.2f".format(m, rest).replace('.', ',')
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { if (frames > 0) rezoom() }

    override fun onDraw(c: Canvas) {
        hits.clear()
        Native.fxInfo(tempo)
        c.drawColor(0xFF1E1F22.toInt())
        // (inside the padding the window's bars leave: the touches are moved the same way)
        c.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        val pad = 14f * d
        var y = pad
        // the header: back, the title, OK
        r.set(pad, y, pad + 40f * d, y + 40f * d)
        box(c, r, 0xFF26272B.toInt(), 0xFF3A3C41.toInt(), 10f)
        p.color = ink; p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * d; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(r.centerX() + 4f * d, r.centerY() - 7f * d, r.centerX() - 4f * d, r.centerY(), p)
        c.drawLine(r.centerX() - 4f * d, r.centerY(), r.centerX() + 4f * d, r.centerY() + 7f * d, p)
        p.style = Paint.Style.FILL
        hits.add(Hit(RectF(r)) { onBack?.invoke() })
        p.typeface = cond; p.textSize = 17f * d
        val okW = p.measureText("OK") + 34f * d
        r.set(w - pad - okW, y, w - pad, y + 40f * d)
        val canOk = frames > 0
        box(c, r, if (canOk) amber else 0xFF3A3C41.toInt(), 0, 10f)
        p.color = 0xFF1E1F22.toInt(); p.textAlign = Paint.Align.CENTER
        c.drawText("OK", r.centerX(), r.centerY() + 6f * d, p)
        if (canOk) hits.add(Hit(RectF(r)) { onOk?.invoke(a, b) })
        p.textAlign = Paint.Align.LEFT; p.color = ink; p.textSize = 17f * d
        c.drawText(ellipsize(title, r.left - pad - 64f * d), pad + 52f * d, y + 18f * d, p)
        p.typeface = Typeface.DEFAULT; p.textSize = 11f * d; p.color = dim
        val sub = fileName + if (frames > 0) " · " + time(frames).substringBefore(',') + tr(" en tout", " in all") else ""
        c.drawText(ellipsize(sub, r.left - pad - 64f * d), pad + 52f * d, y + 35f * d, p)
        y += 52f * d

        if (frames == 0) {                                  // reading the file
            p.textAlign = Paint.Align.CENTER; p.textSize = 15f * d; p.color = if (error != null) 0xFFF3A39E.toInt() else ink
            c.drawText(error ?: tr("Lecture du fichier… %d %%", "Reading the file… %d %%").format((progress * 100).roundToInt()), w / 2f, h / 2f, p)
            if (error == null) {
                r.set(w * 0.2f, h / 2f + 16f * d, w * 0.8f, h / 2f + 22f * d); box(c, r, 0xFF2A2B30.toInt(), 0, 3f)
                r.right = r.left + (w * 0.6f) * progress; box(c, r, amber, 0, 3f)
            }
            return
        }

        // the whole file
        label(c, tr("TOUT LE FICHIER · glisse la fenêtre", "THE WHOLE FILE · drag the window"), pad, y + 10f * d); y += 18f * d
        ovRect.set(pad, y, w - pad, y + 64f * d)
        box(c, ovRect, 0xFF141517.toInt(), 0, 8f)
        drawPeaks(c, overview, ovRect, 0, frames, 0xFF5A5B60.toInt(), amber)
        val wx0 = ovRect.left + ovRect.width() * a / frames
        val wx1 = maxOf(wx0 + 3f * d, ovRect.left + ovRect.width() * b / frames)
        r.set(wx0, ovRect.top, wx1, ovRect.bottom)
        p.color = 0x24F29B38; c.drawRect(r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = amber; c.drawRoundRect(r, 4f * d, 4f * d, p); p.style = Paint.Style.FILL
        y += 64f * d
        p.textSize = 10f * d; p.color = dim
        for (k in 0..4) {
            p.textAlign = if (k == 0) Paint.Align.LEFT else if (k == 4) Paint.Align.RIGHT else Paint.Align.CENTER
            c.drawText(time(frames * k / 4).substringBefore(','), ovRect.left + ovRect.width() * k / 4, y + 12f * d, p)
        }
        y += 22f * d

        // the passage, large
        label(c, tr("LE PASSAGE · tire les poignées", "THE PASSAGE · pull the handles"), pad, y + 10f * d); y += 18f * d
        val fixedBelow = (86 + 10 + 18 + 34 + 18 + 10 + 34 + 12 + 52 + 14) * d
        zoomRect.set(pad, y, w - pad, maxOf(y + 120f * d, h - pad - fixedBelow))
        box(c, zoomRect, 0xFF141517.toInt(), 0, 10f)
        val span = maxOf(1, zb - za)
        fun zx(f: Int) = zoomRect.left + zoomRect.width() * (f - za).toFloat() / span
        // bar and beat lines from the start
        val bf = barFrames()
        if (bf > 0) {
            val beat = bf / 4f
            var k = 0
            while (k < 200) {
                val f = a + (k * beat).toInt()
                if (f > zb) break
                val x = zx(f)
                val isBar = k % 4 == 0
                p.color = if (isBar) 0xFF5A5C62.toInt() else 0xFF2E3035.toInt()
                c.drawRect(x, zoomRect.top, x + (if (isBar) 1.2f else 0.8f) * d, zoomRect.bottom, p)
                if (isBar) { p.textAlign = Paint.Align.LEFT; p.textSize = 10f * d; p.color = dim; c.drawText("${k / 4 + 1}", x + 4f * d, zoomRect.top + 13f * d, p) }
                k++
            }
        }
        val wave = RectF(zoomRect.left + 2f * d, zoomRect.top + 20f * d, zoomRect.right - 2f * d, zoomRect.bottom - 10f * d)
        drawPeaks(c, zoom, wave, za, zb, 0xFF6A5A40.toInt(), 0xFFE6B66A.toInt(), a, b)
        // the attacks
        p.color = 0xFF9CC0EA.toInt()
        val tri = Path()
        for (o in onsets) {
            if (o < za || o > zb) continue
            val x = zx(o)
            tri.reset(); tri.moveTo(x - 4f * d, zoomRect.bottom - 2f * d); tri.lineTo(x + 4f * d, zoomRect.bottom - 2f * d); tri.lineTo(x, zoomRect.bottom - 8f * d); tri.close()
            c.drawPath(tri, p)
        }
        // the handles
        for (f in intArrayOf(a, b)) {
            val x = zx(f)
            p.color = amber
            c.drawRect(x - 1.5f * d, zoomRect.top, x + 1.5f * d, zoomRect.bottom, p)
            r.set(x - 10f * d, zoomRect.centerY() - 17f * d, x + 10f * d, zoomRect.centerY() + 17f * d)
            c.drawRoundRect(r, 6f * d, 6f * d, p)
            p.color = 0xFF7A4A10.toInt()
            for (g in -1..1) c.drawRect(x - 4f * d, r.centerY() + g * 6f * d - 0.75f * d, x + 4f * d, r.centerY() + g * 6f * d + 0.75f * d, p)
        }
        y = zoomRect.bottom + 10f * d

        // start, length, end
        val cw = (w - 2 * pad - 16f * d) / 3f
        val ch = 86f * d
        val lenF = b - a
        for (k in 0..2) {
            r.set(pad + k * (cw + 8f * d), y, pad + k * (cw + 8f * d) + cw, y + ch)
            box(c, r, 0xFF26272B.toInt(), 0, 10f)
            p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT; p.textSize = 10f * d; p.color = dim
            c.drawText(listOf(tr("DÉBUT", "START"), tr("DURÉE", "LENGTH"), tr("FIN", "END"))[k], r.centerX(), r.top + 16f * d, p)
            p.typeface = cond; p.textSize = 17f * d; p.color = ink
            val v = when (k) { 0 -> time(a); 1 -> "%.1f s".format(lenF.toFloat() / rate).replace('.', ','); else -> time(b) }
            c.drawText(v, r.centerX(), r.top + 38f * d, p)
            if (k == 1) {
                p.typeface = Typeface.DEFAULT; p.textSize = 11f * d; p.color = 0xFFF7C489.toInt()
                val nb = lenF.toFloat() / maxOf(1, bf)
                val t = if (bars > 0) tr("$bars mesure${if (bars > 1) "s" else ""} à %.0f", "$bars bar${if (bars > 1) "s" else ""} at %.0f").format(bpm())
                    else tr("≈ %.1f mesures à %.0f", "≈ %.1f bars at %.0f").format(nb, bpm())
                c.drawText(t.replace('.', ','), r.centerX(), r.top + 60f * d, p)
            } else {
                val bw = 44f * d
                val left = RectF(r.centerX() - bw - 3f * d, r.bottom - 38f * d, r.centerX() - 3f * d, r.bottom - 8f * d)
                val right = RectF(r.centerX() + 3f * d, r.bottom - 38f * d, r.centerX() + bw + 3f * d, r.bottom - 8f * d)
                for ((rr, sign) in listOf(left to -1, right to 1)) {
                    box(c, rr, 0xFF1E1F22.toInt(), 0xFF3A3C41.toInt(), 7f)
                    p.color = ink; p.textSize = 13f * d; p.typeface = Typeface.DEFAULT
                    c.drawText(if (sign < 0) "◀" else "▶", rr.centerX(), rr.centerY() + 4.5f * d, p)
                    val isStart = k == 0
                    hits.add(Hit(RectF(rr)) { nudge(isStart, sign) })
                }
            }
        }
        y += ch + 10f * d

        // the length in bars
        label(c, tr("LONGUEUR", "LENGTH"), pad, y + 10f * d); y += 18f * d
        val opts = intArrayOf(0, 1, 2, 4, 8, 16)
        val gw = (w - 2 * pad - 5 * 4f * d) / 6f
        for ((k, n) in opts.withIndex()) {
            r.set(pad + k * (gw + 4f * d), y, pad + k * (gw + 4f * d) + gw, y + 30f * d)
            val ok = n == 0 || (n * bf <= maxLen() && a + n * bf <= frames)
            val on = bars == n
            box(c, r, if (on) amber else 0xFF2A2B30.toInt(), 0, 7f)
            p.typeface = cond; p.textSize = 13f * d; p.textAlign = Paint.Align.CENTER
            p.color = when { on -> 0xFF1E1F22.toInt(); ok -> 0xFFB8B5AE.toInt(); else -> 0xFF5A5C62.toInt() }
            c.drawText(if (n == 0) tr("Libre", "Free") else if (n == 1) tr("1 mes.", "1 bar") else "$n", r.centerX(), r.centerY() + 4.5f * d, p)
            if (ok) hits.add(Hit(RectF(r)) { setBars(n) })
        }
        y += 34f * d
        p.typeface = Typeface.DEFAULT; p.textSize = 10.5f * d; p.color = dim; p.textAlign = Paint.Align.LEFT
        c.drawText(tr("En mesures, au tempo du moment (%.0f BPM, %s).", "In bars, at the tempo of the moment (%.0f BPM, %s).")
            .format(bpm(), if (tempo[2] > 0.5f) tr("horloge du FM-1", "the FM-1's clock") else "TAP"), pad, y + 8f * d, p)
        y += 18f * d

        // the magnet
        label(c, tr("AIMANT", "MAGNET"), pad, y + 20f * d)
        var x = pad + 58f * d
        for ((k, t) in listOf(tr("aux attaques", "to attacks"), tr("au zéro", "to zero"), tr("non", "off")).withIndex()) {
            p.typeface = cond; p.textSize = 12.5f * d
            val tw = p.measureText(t) + 20f * d
            r.set(x, y + 6f * d, x + tw, y + 32f * d)
            val on = magnet == k
            box(c, r, if (on) 0xFF3B2F20.toInt() else 0xFF2A2B30.toInt(), if (on) amber else 0, 13f)
            p.color = if (on) 0xFFF7C489.toInt() else 0xFFB8B5AE.toInt(); p.textAlign = Paint.Align.CENTER
            c.drawText(t, r.centerX(), r.centerY() + 4.5f * d, p)
            hits.add(Hit(RectF(r)) { magnet = k; invalidate() })
            x += tw + 6f * d
        }
        y += 44f * d

        // listen
        r.set(pad, h - pad - 52f * d, w - pad, h - pad)
        box(c, r, if (playing) green else 0xFF1A1F1B.toInt(), green, 12f)
        p.typeface = cond; p.textSize = 16f * d; p.textAlign = Paint.Align.CENTER
        p.color = if (playing) 0xFF10210F.toInt() else 0xFF9FD8A6.toInt()
        c.drawText(if (playing) tr("■  Arrêter l'écoute", "■  Stop listening") else tr("▶  Écouter en boucle", "▶  Listen, looped"), r.centerX(), r.centerY() + 5.5f * d, p)
        hits.add(Hit(RectF(r)) { playing = !playing; Native.loopSrcPreview(a, b, playing); invalidate() })
        if (playing) postInvalidateDelayed(250)
    }

    /** ◀ ▶: the start or the end by 10 ms (the start takes the end along when the length is in bars) */
    private fun nudge(start: Boolean, sign: Int) {
        val step = rate / 100 * sign
        if (start) { val len = b - a; a += step; if (bars > 0) b = a + len }
        else { b += step; bars = 0 }
        clampAB(); rezoom(); changed()
    }

    // ------------------------------------------------------------------ touch

    private var mode = 0                 // 1 the window on the file, 2 the start handle, 3 the end handle, 4 a button
    private var grab = 0
    private var downHit: Hit? = null

    override fun onTouchEvent(e: MotionEvent): Boolean {
        e.offsetLocation(-paddingLeft.toFloat(), -paddingTop.toFloat())
        if (frames == 0) {
            if (e.actionMasked == MotionEvent.ACTION_UP) hits.firstOrNull { it.rect.contains(e.x, e.y) }?.f?.invoke()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = 0
                val slop = 26f * d
                val span = maxOf(1, zb - za)
                val ax = zoomRect.left + zoomRect.width() * (a - za).toFloat() / span
                val bx = zoomRect.left + zoomRect.width() * (b - za).toFloat() / span
                when {
                    ovRect.contains(e.x, e.y) -> {
                        mode = 1
                        val f = frameOnFile(e.x)
                        grab = if (f in a..b) f - a else (b - a) / 2
                        moveWindow(e.x)
                    }
                    zoomRect.contains(e.x, e.y) && abs(e.x - ax) <= slop && abs(e.x - ax) <= abs(e.x - bx) -> mode = 2
                    zoomRect.contains(e.x, e.y) && abs(e.x - bx) <= slop -> mode = 3
                    else -> { downHit = hits.firstOrNull { it.rect.contains(e.x, e.y) }; mode = 4 }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                1 -> moveWindow(e.x)
                2 -> { val len = b - a; a = frameOnZoom(e.x); if (bars > 0) b = a + len; clampAB(); changed() }
                3 -> { b = frameOnZoom(e.x); bars = 0; clampAB(); changed() }
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    1 -> { a = snap(a).also { b += it - a }; clampAB(); rezoom(); changed() }
                    2 -> { val len = b - a; a = snap(a); if (bars > 0) b = a + len; clampAB(); rezoom(); changed() }
                    3 -> { b = snap(b); clampAB(); rezoom(); changed() }
                    4 -> { val h = hits.firstOrNull { it.rect.contains(e.x, e.y) }; if (h != null && h.rect == downHit?.rect) h.f() }
                }
                mode = 0; downHit = null
            }
            MotionEvent.ACTION_CANCEL -> { mode = 0; downHit = null }
        }
        return true
    }

    private fun frameOnFile(x: Float) = ((x - ovRect.left) / ovRect.width() * frames).toInt().coerceIn(0, frames)
    private fun frameOnZoom(x: Float) = (za + (x - zoomRect.left) / zoomRect.width() * (zb - za)).toInt().coerceIn(0, frames)

    private fun moveWindow(x: Float) {
        val len = b - a
        a = (frameOnFile(x) - grab).coerceIn(0, maxOf(0, frames - len))
        b = a + len
        rezoom(); changed()
    }

    // ------------------------------------------------------------------ drawing

    private fun box(c: Canvas, rr: RectF, fill: Int, edge: Int, rad: Float) {
        p.style = Paint.Style.FILL; p.color = fill
        c.drawRoundRect(rr, rad * d, rad * d, p)
        if (edge != 0) { p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * d; p.color = edge; c.drawRoundRect(rr, rad * d, rad * d, p); p.style = Paint.Style.FILL }
    }

    private fun label(c: Canvas, t: String, x: Float, y: Float) {
        p.typeface = cond; p.textSize = 11f * d; p.color = dim; p.textAlign = Paint.Align.LEFT; p.letterSpacing = 0.06f
        c.drawText(t, x, y, p)
        p.letterSpacing = 0f
    }

    /** peaks as bars in [rr], frames f0..f1; the ones inside sa..sb (the passage) in [hot] */
    private fun drawPeaks(c: Canvas, peaks: FloatArray, rr: RectF, f0: Int, f1: Int, cold: Int, hot: Int, sa: Int = a, sb: Int = b) {
        val n = peaks.size
        if (n == 0) return
        val bw = rr.width() / n
        for (k in 0 until n) {
            val f = f0 + ((k + 0.5) * (f1 - f0) / n).toLong()
            p.color = if (f in sa..sb) hot else cold
            val hh = maxOf(1.5f * d, peaks[k] * rr.height())
            c.drawRect(rr.left + k * bw + 0.4f, rr.centerY() - hh / 2f, rr.left + (k + 1) * bw - 0.4f, rr.centerY() + hh / 2f, p)
        }
    }

    private fun ellipsize(t: String, maxW: Float): String {
        if (maxW <= 0f || p.measureText(t) <= maxW) return t
        var n = t.length
        while (n > 1 && p.measureText(t.substring(0, n) + "…") > maxW) n--
        return t.substring(0, n) + "…"
    }
}
