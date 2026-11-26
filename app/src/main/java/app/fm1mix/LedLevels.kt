package app.fm1mix

/**
 * The emulated FM-1's lights as LOOM-8's PANEL MIDI shows them (PanelRemote.kt, 0.29.1). No Android in here.
 *
 * The emulator gives each LED the share of the time it was lit. The FM-1 lights its LEDs by scanning (one column of
 * its matrix at a time), so a LED fully lit is lit only about a tenth of the time (Chordism about 0.06, LOOM-8 0.09):
 * sent as they were (0.20 to 0.29), every lit LED showed dim on LOOM-8 (it shows 64 and up lit, 24 and up the middle
 * level, 6 and up dim), a dimmer one not at all, and those near 6 flickered. Here:
 *  - each value is taken against the scan's full level: the highest lit share seen lately (a peak that falls slowly,
 *    never under 0.02), so a LED fully lit is 1;
 *  - LOOM-8's four levels are chosen here, with a margin each way (a level changes only once the value is clearly
 *    past its threshold), and sent as 127, 40, 12 or 0: a value hovering at a threshold no longer flickers.
 */
class LedLevels(private val n: Int) {
    companion object {
        /** what is sent for each of LOOM-8's levels (panelmidi.c: 64 and up lit, 24 and up middle, 6 and up dim) */
        val SENT = intArrayOf(0, 12, 40, 127)
        /** the levels' lower thresholds, in the scan's full level, and the margin each way */
        private val UP = floatArrayOf(0f, 0.12f, 0.35f, 0.70f)
        private const val MARGIN = 0.05f
        private const val FULL_MIN = 0.02f
        /** the full level falls 0.2 % a refresh (half in about 10 s at 30 a second) when nothing is that bright any more */
        private const val FULL_FALL = 0.998f

        /** the level for [v] (0..1 of the full level), [was] the one shown: moving up or down needs the margin */
        fun levelOf(v: Float, was: Int): Int {
            var lv = 0
            for (k in 3 downTo 1) if (v >= UP[k]) { lv = k; break }
            if (lv == was) return was
            return if (lv > was) { if (v >= UP[lv] + MARGIN) lv else was }
            else { if (v < UP[was] - MARGIN) lv else was }
        }
    }

    /** the scan's full level: the highest lit share seen lately */
    var full = FULL_MIN
        private set
    /** the level shown for each LED: 0 off, 1 dim, 2 middle, 3 lit */
    val level = IntArray(n)
    /** what to send for each LED (0..127) */
    val sent = IntArray(n)

    /** a refresh's lights (0..1 each): true when a level changed */
    fun feed(l: FloatArray): Boolean {
        var top = 0f
        for (i in 0 until n) top = maxOf(top, l[i])
        full = maxOf(top, full * FULL_FALL, FULL_MIN)
        var changed = false
        for (i in 0 until n) {
            val lv = levelOf(l[i] / full, level[i])
            if (lv != level[i]) { level[i] = lv; sent[i] = SENT[lv]; changed = true }
        }
        return changed
    }
}
