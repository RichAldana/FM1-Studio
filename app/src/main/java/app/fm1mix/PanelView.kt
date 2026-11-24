package app.fm1mix

import android.content.Context
import android.graphics.*
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.nio.ShortBuffer
import app.fm1mix.PanelLayout as L

/*
 * The emulated FM-1, drawn for a phone held upright and played by touch: screen, MASTER and 7 encoders (drag a knob up
 * or right to turn it), 14 buttons whose lights follow the firmware, and a two-tier keyboard of 27 keys (the height of
 * the touch is the velocity; a finger sliding across keys plays them in turn; several fingers at once).
 * The layout is PanelLayout, rebuilt for whatever size the view gets.
 */
class PanelView(ctx: Context, private val part: Int = PART_ALL) : View(ctx) {
    companion object {
        /** the whole panel; the screen, encoders and buttons; the keyboard (each part in its own swipe zone) */
        const val PART_ALL = 0
        const val PART_TOP = 1
        const val PART_KEYS = 2
        const val NOTES_NONE = 0
        const val NOTES_C = 1
        const val NOTES_ALL = 2
        private val NOTE = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        /** the note a key plays at [octave]: key ids 14..40 are F3..G5 at octave 0 */
        fun noteOf(id: Int, octave: Int) = 53 + (id - 14) + 12 * octave
        fun noteName(n: Int) = NOTE[Math.floorMod(n, 12)] + (Math.floorDiv(n, 12) - 1)
    }

    private val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565)
    private val leds = FloatArray(41)
    private val down = BooleanArray(41)          // by matrix id: held by a finger
    private val encTurn = FloatArray(7)          // by index in layout.encs: where the knob points, in degrees
    /** MASTER, 0..1023 (set by the activity: each firmware's own) */
    var potValue = 1023
        set(v) { field = v.coerceIn(0, 1023); invalidate() }
    /** MASTER turned on the screen (kept per firmware by the activity) */
    var onPot: ((Int) -> Unit)? = null
    /** OCT- (-1) or OCT+ (+1) pressed on the screen (the activity follows the octave) */
    var onOct: ((Int) -> Unit)? = null
    /** the keyboard's octave as the activity follows it (0: the lower C is C4) */
    var octave = 0
        set(v) { if (v != field) { field = v; invalidate() } }
    /** the notes written on the keys: [NOTES_NONE], [NOTES_C] (the Cs, with their octave), [NOTES_ALL] */
    var noteNames = NOTES_C
        set(v) { if (v != field) { field = v; invalidate() } }
    /** Shown dimmed, untouchable, when the machine is off. */
    var powered = false
        set(v) { field = v; invalidate() }

    private var layout: L.Layout = L.build(1080f, 2000f)

    // colours: a dark grey unit with amber lights, as on the screenshot the user picked
    private val bg = 0xFF3A3C41.toInt()
    private val body = 0xFF45474C.toInt()
    private val ink = 0xFFD9D6CE.toInt()
    private val dim = 0xFF8E8C87.toInt()
    private val amber = 0xFFF29B38.toInt()
    private val green = 0xFF62D36E.toInt()
    private val btnFace = 0xFF2E3034.toInt()
    private val btnEdge = 0xFF26272A.toInt()
    private val knobFace = 0xFF1E1F22.toInt()
    private val knobRim = 0xFF2C2D31.toInt()
    private val whiteKey = 0xFFF3E9DA.toInt()
    private val whiteKeyLow = 0xFFE6D6C0.toInt()
    private val blackKey = 0xFF1C1C1E.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = 0.12f
    }
    private val lcdPaint = Paint().apply { isFilterBitmap = false }
    private val r = RectF()

    private class Touch(val kind: Int, var index: Int, var lastX: Float, var lastY: Float) { var acc = 0f }
    private val touches = SparseArray<Touch>()

    fun update(px: ShortArray, ledValues: FloatArray) {
        bmp.copyPixelsFromBuffer(ShortBuffer.wrap(px))
        System.arraycopy(ledValues, 0, leds, 0, minOf(leds.size, ledValues.size))
        invalidate()
    }

    fun clearScreen() {
        bmp.eraseColor(Color.BLACK)
        leds.fill(0f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layout = when (part) {
            PART_TOP -> L.buildTop(w.toFloat(), h.toFloat())
            PART_KEYS -> L.buildKeys(w.toFloat(), h.toFloat())
            else -> L.build(w.toFloat(), h.toFloat())
        }
    }

    // ------------------------------------------------------------------ drawing

    private fun mix(a: Int, b: Int, t0: Float): Int {
        val t = t0.coerceIn(0f, 1f)
        fun ch(s: Int): Int {
            val x = (a shr s) and 255
            val y = (b shr s) and 255
            return (x + (y - x) * t).toInt()
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun round(c: Canvas, x: Float, y: Float, w: Float, h: Float, rad: Float, color: Int) {
        fill.shader = null
        fill.color = color
        r.set(x, y, x + w, y + h)
        c.drawRoundRect(r, rad, rad, fill)
    }

    private fun label(c: Canvas, s: String, cx: Float, y: Float, size: Float, color: Int) {
        text.textSize = size
        text.color = color
        c.drawText(s, cx, y, text)
    }

    override fun onDraw(c: Canvas) {
        val l = layout
        c.drawColor(bg)
        c.save()
        c.translate(l.ox, l.oy)
        c.scale(l.scale, l.scale)

        if (part != PART_KEYS) drawUpper(c, l)
        if (part != PART_TOP) drawKeys(c, l)
        if (!powered) {
            fill.shader = null
            fill.color = 0x66000000
            c.drawRect(-2000f, -4000f, L.REF_W + 2000f, 6000f, fill)
        }
        c.restore()
    }

    private fun drawUpper(c: Canvas, l: L.Layout) {
        // the screen in its bezel
        round(c, l.bezelX, l.bezelY, l.bezelSize, l.bezelSize, 34f, 0xFF111214.toInt())
        if (powered) {
            r.set(l.lcdX, l.lcdY, l.lcdX + l.lcdSize, l.lcdY + l.lcdSize)
            c.drawBitmap(bmp, null, r, lcdPaint)
        } else {
            round(c, l.lcdX, l.lcdY, l.lcdSize, l.lcdSize, 6f, 0xFF050506.toInt())
            label(c, "OFF", l.lcdX + l.lcdSize / 2f, l.lcdY + l.lcdSize / 2f + 14f, 40f, 0xFF3A3B3F.toInt())
        }

        // knobs
        drawKnob(c, l.pot, -135f + 270f * potValue / 1023f, true)
        for (i in l.encs.indices) drawKnob(c, l.encs[i], encTurn[i], false)

        // buttons: the light tints the frame and the label
        for (b in l.buttons) {
            val led = if (powered) leds[b.id].coerceIn(0f, 1f) else 0f
            val tint = if (b.id == 12) green else amber
            val sink = if (down[b.id]) 4f else 0f
            round(c, b.x, b.y + 5f, b.w, b.h, 18f, btnEdge)
            round(c, b.x, b.y + sink, b.w, b.h, 18f, if (down[b.id]) 0xFF27292C.toInt() else btnFace)
            if (led > 0.05f) {
                line.color = mix(btnFace, tint, led)
                line.strokeWidth = 5f
                r.set(b.x + 2.5f, b.y + sink + 2.5f, b.x + b.w - 2.5f, b.y + sink + b.h - 2.5f)
                c.drawRoundRect(r, 16f, 16f, line)
                fill.color = tint
                c.drawCircle(b.x + b.w / 2f, b.y + sink + 16f, 6f, fill)
            }
            label(c, b.name, b.x + b.w / 2f, b.y + sink + b.h / 2f + 12f, if (b.h < 110f) 30f else 32f,
                if (led > 0.5f) tint else ink)
        }

    }

    private fun drawKeys(c: Canvas, l: L.Layout) {
        round(c, l.kbX, l.kbY, l.kbW, l.kbH, 36f, 0xFF1A1B1D.toInt())
        for (pass in 0..1) for (k in l.keys) {
            if ((pass == 0) == k.black) continue
            val held = down[k.id]
            val led = if (powered) leds[k.id].coerceIn(0f, 1f) else 0f
            val gap = if (k.black) 0f else 4f
            if (!k.black) {
                fill.shader = LinearGradient(0f, k.y, 0f, k.y + k.h,
                    if (held) mix(whiteKey, amber, 0.35f) else whiteKey,
                    if (held) mix(whiteKeyLow, amber, 0.45f) else whiteKeyLow, Shader.TileMode.CLAMP)
                r.set(k.x + gap, k.y, k.x + k.w - gap, k.y + k.h)
                c.drawRoundRect(r, 14f, 14f, fill)
                fill.shader = null
            } else {
                round(c, k.x - 3f, k.y, k.w + 6f, k.h + 6f, 14f, 0xFF0E0E0F.toInt())
                round(c, k.x, k.y, k.w, k.h, 12f, if (held) mix(blackKey, amber, 0.4f) else blackKey)
            }
            // the key's light: a bar at its foot
            if (led > 0.05f || held) {
                fill.color = mix(if (k.black) blackKey else whiteKeyLow, amber, if (held) 1f else led)
                val bw = minOf(k.w * 0.5f, 60f)
                round(c, k.x + k.w / 2f - bw / 2f, k.y + k.h - 24f, bw, 9f, 4.5f, fill.color)
            }
            keyName(c, k)
        }
    }

    /** the key's note, as [noteNames] asks: the Cs with their octave, or every note (black keys smaller) */
    private fun keyName(c: Canvas, k: L.Key) {
        if (noteNames == NOTES_NONE) return
        val n = noteOf(k.id, octave)
        val isC = Math.floorMod(n, 12) == 0
        if (noteNames == NOTES_C && !isC) return
        val nm = if (isC) noteName(n) else NOTE[Math.floorMod(n, 12)]
        if (k.black) label(c, nm, k.x + k.w / 2f, k.y + k.h - 34f, 20f, 0xFF77746E.toInt())
        else label(c, nm, k.x + k.w / 2f, k.y + k.h - 46f, if (isC) 28f else 24f,
                   if (isC) 0xFF9A8F80.toInt() else 0xFFB5AA9A.toInt())
    }

    private fun drawKnob(c: Canvas, e: L.Enc, degrees: Float, isPot: Boolean) {
        // tick ring
        line.strokeWidth = 4f
        line.color = if (e.accent) amber else 0xFF6B6D72.toInt()
        for (t in 0..10) {
            val a = Math.toRadians((-135.0 + t * 27.0) - 90.0)
            val r0 = e.r + 16f
            val r1 = e.r + 28f
            c.drawLine(e.cx + (r0 * Math.cos(a)).toFloat(), e.cy + (r0 * Math.sin(a)).toFloat(),
                e.cx + (r1 * Math.cos(a)).toFloat(), e.cy + (r1 * Math.sin(a)).toFloat(), line)
        }
        if (isPot) {                                  // MASTER: an amber arc up to its position
            line.strokeWidth = 7f
            line.color = 0xFF2A2B2E.toInt()
            r.set(e.cx - e.r - 22f, e.cy - e.r - 22f, e.cx + e.r + 22f, e.cy + e.r + 22f)
            c.drawArc(r, 135f, 270f, false, line)
            line.color = amber
            c.drawArc(r, 135f, 270f * potValue / 1023f, false, line)
        }
        fill.shader = null
        fill.color = 0xFF151618.toInt()
        c.drawCircle(e.cx + 2f, e.cy + 6f, e.r + 6f, fill)
        fill.color = knobRim
        c.drawCircle(e.cx, e.cy, e.r + 6f, fill)
        fill.color = knobFace
        c.drawCircle(e.cx, e.cy, e.r - 4f, fill)
        c.save()
        c.rotate(degrees, e.cx, e.cy)
        line.strokeWidth = 8f
        line.color = if (e.accent) amber else 0xFFEDE9E0.toInt()
        c.drawLine(e.cx, e.cy - e.r * 0.25f, e.cx, e.cy - e.r * 0.85f, line)
        c.restore()
        label(c, e.name, e.cx, e.cy + e.r + 70f, 32f, ink)
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!powered) return true
        val l = layout
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                press(e.getPointerId(i), l.toRefX(e.getX(i)), l.toRefY(e.getY(i)))
            }
            MotionEvent.ACTION_MOVE ->
                for (i in 0 until e.pointerCount) move(e.getPointerId(i), l.toRefX(e.getX(i)), l.toRefY(e.getY(i)))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> for (n in touches.size() - 1 downTo 0) release(touches.keyAt(n))
        }
        return true
    }

    /** Let go of everything held (power off, app in the background). */
    fun releaseAll() {
        for (n in touches.size() - 1 downTo 0) release(touches.keyAt(n))
    }

    private fun press(id: Int, x: Float, y: Float) {
        val l = layout
        val h = l.hit(x, y) ?: return
        if (part == PART_TOP && h.kind == L.HIT_KEY || part == PART_KEYS && h.kind != L.HIT_KEY) return
        touches.put(id, Touch(h.kind, h.index, x, y))
        when (h.kind) {
            L.HIT_BTN -> {
                val b = l.buttons[h.index]
                down[b.id] = true
                Native.key(b.id, true)
                if (b.id == 0) onOct?.invoke(-1) else if (b.id == 1) onOct?.invoke(1)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            L.HIT_KEY -> {
                val k = l.keys[h.index]
                down[k.id] = true
                Native.keyVel(k.id, L.keyVelocity(k, y))
            }
        }
        invalidate()
    }

    private fun move(id: Int, x: Float, y: Float) {
        val l = layout
        val t = touches.get(id) ?: return
        val drag = (x - t.lastX) - (y - t.lastY)      // right or up turns clockwise
        t.lastX = x
        t.lastY = y
        when (t.kind) {
            L.HIT_ENC -> {
                t.acc += drag / 22f                    // 22 reference units a click
                val clicks = t.acc.toInt()
                if (clicks != 0) {
                    t.acc -= clicks
                    encTurn[t.index] = (encTurn[t.index] + clicks * 15f) % 360f
                    Native.enc(l.encs[t.index].id, clicks)
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    invalidate()
                }
            }
            L.HIT_POT -> {
                val v = (potValue + drag * 2.5f).toInt().coerceIn(0, 1023)
                if (v != potValue) {
                    potValue = v
                    Native.pot(v)
                    onPot?.invoke(v)
                    invalidate()
                }
            }
            L.HIT_KEY -> {                              // the finger slid onto another key: let go, play that one
                val h = l.hit(x, y)
                if (h != null && h.kind == L.HIT_KEY && h.index != t.index) {
                    val old = l.keys[t.index]
                    val nk = l.keys[h.index]
                    down[old.id] = false
                    Native.key(old.id, false)
                    down[nk.id] = true
                    Native.keyVel(nk.id, L.keyVelocity(nk, y))
                    t.index = h.index
                    invalidate()
                }
            }
        }
    }

    private fun release(id: Int) {
        val l = layout
        val t = touches.get(id) ?: return
        touches.remove(id)
        when (t.kind) {
            L.HIT_BTN -> { val b = l.buttons[t.index]; down[b.id] = false; Native.key(b.id, false) }
            L.HIT_KEY -> { val k = l.keys[t.index]; down[k.id] = false; Native.key(k.id, false) }
        }
        invalidate()
    }
}
