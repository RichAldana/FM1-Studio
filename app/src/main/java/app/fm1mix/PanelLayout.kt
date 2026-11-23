package app.fm1mix

/*
 * The FM-1 panel laid out for a phone held upright, after the layout of the screenshot the user chose:
 * MASTER / SELECT / PRST / ALGO and OCT -/+ on the left of the screen, the four knobs under it, two rows of six
 * buttons, and the 27 keys as a two-tier keyboard (F4..G5 on top, F3..E4 below) that takes the height left.
 * Everything is in reference units (the panel is 1080 wide); build() fits it to any view size. No Android in
 * here, so it is checked on its own (tools/layout_check).
 */
object PanelLayout {
    const val REF_W = 1080f
    /** Where the keyboard starts, in reference units. */
    const val TOP_H = 1200f
    const val MIN_ROW = 190f
    const val MAX_ROW = 430f
    const val GAP = 24f
    const val BOTTOM = 20f
    private const val SIDE = 22f

    /** An encoder, or the MASTER pot ([id] -1). [id] is the matrix encoder number. */
    class Enc(val name: String, val id: Int, val cx: Float, val cy: Float, val r: Float, val accent: Boolean)

    /** A button: [id] is its matrix id, 0..13. */
    class Btn(val name: String, val id: Int, val x: Float, val y: Float, val w: Float, val h: Float)

    /** A note key: [id] is its matrix id, 14..40 (F3 .. G5); [name] is set on the C keys. */
    class Key(val id: Int, val x: Float, val y: Float, val w: Float, val h: Float, val black: Boolean, val name: String)

    const val HIT_ENC = 0
    const val HIT_POT = 1
    const val HIT_BTN = 2
    const val HIT_KEY = 3

    /** What a touch lands on: [kind] one of HIT_*, [index] into encs / buttons / keys. */
    class Hit(val kind: Int, val index: Int)

    class Layout(
        /** view pixels -> reference units: ref = (px - ox) / scale */
        val scale: Float, val ox: Float, val oy: Float,
        val encs: List<Enc>, val pot: Enc, val buttons: List<Btn>, val keys: List<Key>,
        val bezelX: Float, val bezelY: Float, val bezelSize: Float,
        val lcdX: Float, val lcdY: Float, val lcdSize: Float,
        val kbX: Float, val kbY: Float, val kbW: Float, val kbH: Float,
        /** how far down the panel goes, in reference units */
        val usedHeight: Float
    ) {
        fun toRefX(px: Float): Float = (px - ox) / scale
        fun toRefY(py: Float): Float = (py - oy) / scale

        private fun inside(x: Float, y: Float, rx: Float, ry: Float, w: Float, h: Float): Boolean =
            x >= rx && x < rx + w && y >= ry && y < ry + h

        /** [x], [y] in reference units. */
        fun hit(x: Float, y: Float): Hit? {
            for (i in encs.indices) {
                val e = encs[i]
                val dx = x - e.cx
                val dy = y - e.cy
                val r = e.r + 16f
                if (dx * dx + dy * dy <= r * r) return Hit(HIT_ENC, i)
            }
            val px = x - pot.cx
            val py = y - pot.cy
            val pr = pot.r + 16f
            if (px * px + py * py <= pr * pr) return Hit(HIT_POT, 0)
            for (i in buttons.indices) {
                val b = buttons[i]
                if (inside(x, y, b.x, b.y, b.w, b.h)) return Hit(HIT_BTN, i)
            }
            for (i in keys.indices) {                 // black keys first: they sit over the white ones
                val k = keys[i]
                if (k.black && inside(x, y, k.x, k.y, k.w, k.h)) return Hit(HIT_KEY, i)
            }
            for (i in keys.indices) {
                val k = keys[i]
                if (!k.black && inside(x, y, k.x, k.y, k.w, k.h)) return Hit(HIT_KEY, i)
            }
            return null
        }
    }

    /** The velocity of a touch on [key] at reference height [y]: 1 at the top, 127 at the bottom. */
    fun keyVelocity(key: Key, y: Float): Int {
        var t = (y - key.y) / key.h
        if (t < 0f) t = 0f
        if (t > 1f) t = 1f
        return Math.round(1f + 126f * t)
    }

    private val BUTTON_NAMES = arrayOf(
        arrayOf("FX", "SCL", "ENV", "LFO", "EDIT", "GLO"),
        arrayOf("HOME", "SAVE", "ARP", "SEQ", "PLAY", "REC")
    )

    /** One tier: white keys (semitones from F3) and black keys, each centred on the gap after white key [blackAfter]. */
    private fun keyRow(out: MutableList<Key>, x0: Float, width: Float, y: Float, rowH: Float,
                       whites: IntArray, blacks: IntArray, blackAfter: IntArray) {
        val w = width / whites.size
        for (i in whites.indices) {
            val n = whites[i]
            val name = if (n == 7) "C4" else if (n == 19) "C5" else ""
            out.add(Key(14 + n, x0 + i * w, y, w, rowH, false, name))
        }
        val bw = w * 0.64f
        for (j in blacks.indices) {
            val cx = x0 + (blackAfter[j] + 1) * w
            out.add(Key(14 + blacks[j], cx - bw / 2f, y, bw, rowH * 0.6f, true, ""))
        }
    }

    /** Fit the panel to a view of [w] x [h] pixels. */
    fun build(w: Float, h: Float): Layout {
        val minTotal = TOP_H + 2f * MIN_ROW + GAP + 2f * 16f + BOTTOM
        var s = w / REF_W
        var hl = h / s
        if (hl < minTotal) {                          // a short screen: shrink everything to fit the height
            s = h / minTotal
            hl = minTotal
        }
        val rowH = ((hl - TOP_H - GAP - BOTTOM - 2f * 16f) / 2f).coerceIn(MIN_ROW, MAX_ROW)
        val kbH = 2f * rowH + GAP + 2f * 16f
        val used = TOP_H + kbH + BOTTOM
        val ox = (w - REF_W * s) / 2f
        val oy = ((h - used * s) / 2f).coerceAtLeast(0f)

        val encs = ArrayList<Enc>()
        encs.add(Enc("SELECT", 0, 301f, 112f, 58f, false))
        encs.add(Enc("PRST", 6, 107f, 352f, 58f, false))
        encs.add(Enc("ALGO", 1, 301f, 352f, 58f, false))
        val names = arrayOf("KNOB1", "KNOB2", "KNOB3", "KNOB4")
        for (i in 0 until 4) encs.add(Enc(names[i], 2 + i, 177f + i * 242f, 745f, 58f, true))
        val pot = Enc("MASTER", -1, 107f, 112f, 58f, false)

        val btns = ArrayList<Btn>()
        btns.add(Btn("OCT -", 0, 22f, 505f, 170f, 100f))
        btns.add(Btn("OCT +", 1, 216f, 505f, 170f, 100f))
        for (r in 0 until 2) {
            for (c in 0 until 6) {
                btns.add(Btn(BUTTON_NAMES[r][c], 2 + r * 6 + c, 21f + c * 176f, 895f + r * 150f, 158f, 125f))
            }
        }

        val keys = ArrayList<Key>()
        val kbX = SIDE
        val kbW = REF_W - 2f * SIDE
        val inner = kbW - 2f * 16f
        // top tier: F4 .. G5 (9 white keys, 6 black); bottom tier: F3 .. E4 (7 white keys, 5 black)
        keyRow(keys, kbX + 16f, inner, TOP_H + 16f, rowH,
            intArrayOf(12, 14, 16, 18, 19, 21, 23, 24, 26), intArrayOf(13, 15, 17, 20, 22, 25), intArrayOf(0, 1, 2, 4, 5, 7))
        keyRow(keys, kbX + 16f, inner, TOP_H + 16f + rowH + GAP, rowH,
            intArrayOf(0, 2, 4, 6, 7, 9, 11), intArrayOf(1, 3, 5, 8, 10), intArrayOf(0, 1, 2, 4, 5))
        return Layout(s, ox, oy, encs, pot, btns, keys,
            420f, 0f, 638f, 449f, 29f, 580f, kbX, TOP_H, kbW, kbH, used)
    }
}
