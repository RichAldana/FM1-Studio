// SPDX-License-Identifier: GPL-3.0-only
// CHOP: chopping a recording at its hits, by taps, on a tempo grid or in equal parts.
// Ported from SLOOP's web editor (web/editor.html, "chop" block: chopNovelty, chopAttack, chopHits, chopSnap,
// chopGrid, chopEqual, chopList, chopPick, chopFit, chopZones' fades), https://github.com/isod89/sloop-fm1,
// Copyright (C) 2026 Leo Kuroshita (@kurogedelic), Hügelton Instruments, and SLOOP's authors; GPL-3.0-only.
// Kotlin port for FM1 Mix: the same steps and constants, checked against SLOOP's own code and tests
// (tools/chop_check). No Android in here.
package app.fm1mix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object Chop {
    /** The rate the analysis runs at (SLOOP's SMP.RATE): its frames, gaps and thresholds are tuned for it. */
    const val RATE = 22050
    /** 5.8 ms frames; fades 1 / 6 ms; a chop starts 1.5 ms before its attack */
    const val HOP = 128
    const val MAX = 16
    const val FADE_IN = 22
    const val FADE_OUT = 132
    const val PRE = 32

    /** onset novelty per frame, and each frame's log10 power (0 = the loudest) */
    class Novelty(val nov: DoubleArray, val level: DoubleArray) { val size get() = nov.size }

    /**
     * Onset novelty per frame: the rise of the log energy of the signal's first difference (the attack of a hit, not
     * its body), over the louder of the two frames before.
     */
    fun novelty(x: DoubleArray): Novelty {
        val h = HOP
        val f = (x.size + h - 1) / h
        val l = DoubleArray(f)
        val p = DoubleArray(f)
        val nov = DoubleArray(f)
        var top = 1e-12
        var ptop = 1e-12
        for (k in 0 until f) {
            var e = 0.0
            var pp = 0.0
            var i = k * h
            val n = min(x.size, i + h)
            while (i < n) { val d = x[i] - (if (i != 0) x[i - 1] else 0.0); e += d * d; pp += x[i] * x[i]; i++ }
            l[k] = e
            p[k] = pp
            if (e > top) top = e
            if (pp > ptop) ptop = pp
        }
        for (k in 0 until f) { l[k] = StrictMath.log10(l[k] / top + 1e-9); p[k] = StrictMath.log10(p[k] / ptop + 1e-9) }
        for (k in 0 until f) {
            val before = max(if (k != 0) l[k - 1] else -9.0, if (k > 1) l[k - 2] else -9.0)
            nov[k] = if (l[k] > -3.5) max(0.0, l[k] - before) else 0.0      // (under -35 dB: no hit)
        }
        return Novelty(nov, p)
    }

    /** the first sample of the hit in frame f: where the signal first reaches 30 % of its peak over f-1..f+2, PRE earlier */
    fun attack(x: DoubleArray, f: Int): Int {
        val a = max(0, (f - 1) * HOP)
        val b = min(x.size, (f + 3) * HOP)
        var pk = 0.0
        for (i in a until b) pk = max(pk, abs(x[i]))
        for (i in a until b) if (abs(x[i]) >= 0.3 * pk) return max(0, i - PRE)
        return a
    }

    /**
     * hits: [sens] 1 (only the hardest and loudest) .. 10 (every small one); at least 60 ms apart. A hit is a sharp rise
     * (nov over thr) to a level (its loudest frame within 3) over the gate.
     */
    fun hits(x: DoubleArray, n: Novelty, sens: Int): IntArray {
        val k = max(1, min(10, sens))
        val thr = 0.15 + (10 - k) * 0.08
        val gate = -(0.5 + k * 0.35)
        val gap = Math.round(0.06 * RATE / HOP).toInt()
        val nov = n.nov
        val lv = n.level
        val out = ArrayList<Int>()
        var last = -1000000000
        for (f in 0 until nov.size - 1) {
            if (nov[f] < thr || (f != 0 && nov[f] < nov[f - 1]) || nov[f] < nov[f + 1]) continue
            if (max(lv[f], max(lv[min(lv.size - 1, f + 1)], lv[min(lv.size - 1, f + 2)])) < gate) continue
            if (f - last < gap) {
                if (out.isNotEmpty() && nov[f] > nov[last]) { out[out.size - 1] = attack(x, f); last = f }
                continue
            }
            out.add(attack(x, f))
            last = f
        }
        return out.toIntArray()
    }

    /** a tap at [pos] (as heard) moved to the hit it meant: the strongest attack within +-[win] s, if any */
    fun snap(x: DoubleArray, n: Novelty, pos: Double, win: Double = 0.05): Int {
        val w = Math.round(win * RATE / HOP).toInt()
        val c = Math.round(pos / HOP).toInt()
        var best = -1
        var bv = 0.12
        val nov = n.nov
        for (f in max(1, c - w)..min(nov.size - 1, c + w)) if (nov[f] > bv) { bv = nov[f]; best = f }
        return if (best < 0) max(0, min(x.size - 1, Math.round(pos).toInt())) else attack(x, best)
    }

    /** markers every [beats] beats at [bpm] from [start] to [end] */
    fun grid(start: Int, end: Int, bpm: Double, beats: Double): IntArray {
        val step = RATE * 60.0 / bpm * beats
        val out = ArrayList<Int>()
        var p = start.toDouble()
        while (p < end - step * 0.25 && out.size < 64) { out.add(Math.round(p).toInt()); p += step }
        return out.toIntArray()
    }

    /** [n] equal parts of start..end */
    fun equal(start: Int, end: Int, n: Int): IntArray =
        IntArray(n) { i -> Math.round(start + (end - start).toDouble() * i / n).toInt() }

    /** a chop: [start, end) in samples; [i] its marker's number; [off] left out; [full] up to the next marker */
    class Piece(val start: Int, val end: Int, val i: Int, val off: Boolean, val full: Int)

    /** a chop's own options: left out, or its own length in samples (0: up to the next marker) */
    class Opt(var off: Boolean = false, var len: Int = 0)

    /**
     * the chops of sorted markers: each to the next marker (the last to the end), cut at [maxLen] samples when > 0.
     * A chop is never longer than up to the next marker ([Piece.full]).
     */
    fun list(marks: IntArray, total: Int, maxLen: Int = 0, opt: List<Opt?>? = null): List<Piece> =
        marks.indices.map { i ->
            val m = marks[i]
            val full = max(m + 1, if (i + 1 < marks.size) marks[i + 1] else total)
            val o = opt?.getOrNull(i)
            var e = full
            if (o != null && o.len > 0) e = min(e, m + o.len)
            else if (maxLen > 0) e = min(e, m + maxLen)
            Piece(m, max(m + 1, e), i, o?.off ?: false, full)
        }

    /** mode 0: the kept chops, the first MAX of them; mode 1: the chop [sel] */
    fun pick(chops: List<Piece>, mode: Int = 0, sel: Int = 0, limit: Int = MAX): List<Piece> {
        if (mode == 1) return if (chops.isEmpty()) emptyList() else listOf(chops[max(0, min(chops.size - 1, sel))])
        return chops.filter { !it.off }.take(limit)
    }

    /** the longest common length L (samples) so the chops, each cut at L, fit in [room]; Int.MAX_VALUE if they fit */
    fun fit(chops: List<Piece>, room: Int): Int {
        val len = chops.map { it.end - it.start }
        fun sum(l: Int): Long = len.fold(0L) { a, n -> a + min(n, l) }
        if (sum(Int.MAX_VALUE) <= room) return Int.MAX_VALUE
        var lo = 0
        var hi = len.maxOrNull() ?: 0
        while (lo < hi) { val l = (lo + hi + 1) shr 1; if (sum(l) <= room) lo = l else hi = l - 1 }
        return lo
    }

    /** a chop's fade gain at sample i of n (chopZones: in over FADE_IN, out over FADE_OUT, each at most n/4) */
    fun fade(i: Int, n: Int, fadeIn: Int = FADE_IN, fadeOut: Int = FADE_OUT): Double {
        val fi = min(fadeIn, n shr 2)
        val fo = min(fadeOut, n shr 2)
        var g = 1.0
        if (i < fi) g = i.toDouble() / fi
        if (i >= n - fo) g = min(g, (n - i).toDouble() / fo)
        return g
    }

    /** chopZones' samples for one chop: x[start, end) over a common [peak], to 30000, faded (SLOOP's slot samples) */
    fun zoneSamples(x: DoubleArray, c: Piece, peak: Double): ShortArray {
        val n = c.end - c.start
        val s = ShortArray(n)
        for (i in 0 until n) {
            val v = x[c.start + i] / peak * 30000 * fade(i, n)
            s[i] = max(-32768.0, min(32767.0, if (v < 0) Math.ceil(v) else Math.floor(v))).toInt().toShort()
        }
        return s
    }

    /** the common peak of the chops used (chopZones keeps their levels: one gain for all) */
    fun peak(x: DoubleArray, use: List<Piece>): Double {
        var pk = 1e-9
        for (c in use) for (i in c.start until c.end) pk = max(pk, abs(x[i]))
        return pk
    }
}
