// SPDX-License-Identifier: GPL-3.0-only
// The slicer's state, as SLOOP's CHOP panel keeps it (chopSetMarks, chopAdd, region: web/editor.html,
// https://github.com/isod89/sloop-fm1, GPL-3.0-only), over a sound of any rate and channels. No Android in here.
package app.fm1mix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A recording being sliced. The analysis runs on [x], the sound in mono at [Chop.RATE] (SLOOP's rate: its
 * thresholds are tuned for it); markers are kept there and turned into the file's own frames to cut the file's
 * sound, in its own rate and channels, untouched but for the fades.
 */
class SlicerModel(val sound: WavIO.Sound) {
    val x: DoubleArray = LoomSamples.resample(sound.mono(), sound.rate, Chop.RATE)
    val nov: Chop.Novelty = Chop.novelty(x)
    var marks: IntArray = IntArray(0)
        private set
    var opts: MutableList<Chop.Opt> = ArrayList()
        private set
    /** the selected chop (-1: none) */
    var sel = -1

    val seconds: Double get() = sound.frames.toDouble() / sound.rate

    /** markers set (rounded, inside, each once, sorted); a marker kept keeps its options unless [opt] gives some */
    fun setMarks(m: IntArray, opt: List<Chop.Opt?>? = null) {
        val was = HashMap<Int, Chop.Opt>()
        for (i in marks.indices) was[marks[i]] = opts[i]
        val seen = HashSet<Int>()
        val pairs = ArrayList<Pair<Int, Chop.Opt>>()
        for (i in m.indices) {
            val p = max(0, min(x.size - 1, m[i]))
            if (!seen.add(p)) continue
            val o = if (opt != null) opt.getOrNull(i) else was[p]
            pairs.add(Pair(p, if (o != null) Chop.Opt(o.off, o.len) else Chop.Opt()))
        }
        pairs.sortBy { it.first }
        marks = pairs.map { it.first }.toIntArray()
        opts = pairs.map { it.second }.toMutableList()
        if (sel >= marks.size) sel = marks.size - 1
    }

    /** a new marker at [pos] (analysis samples); one within 30 ms is moved there instead */
    fun add(pos: Int) {
        val near = marks.indexOfFirst { abs(it - pos) < 0.03 * Chop.RATE }
        val m = marks.toMutableList()
        val o: MutableList<Chop.Opt?> = opts.toMutableList()
        if (near >= 0) m[near] = pos else { m.add(pos); o.add(null) }
        setMarks(m.toIntArray(), o)
        sel = marks.indexOf(max(0, min(x.size - 1, pos)))
    }

    /** a TAP heard at [fileFrame] of the sound: snapped to the hit it meant when [snap] */
    fun tap(fileFrame: Long, snap: Boolean = true) {
        val pos = fileFrame.toDouble() * Chop.RATE / sound.rate
        add(if (snap) Chop.snap(x, nov, pos) else Math.round(pos).toInt())
    }

    /** first..last marker when there are two, else from the first (or the start) to the end */
    fun region(): IntArray = if (marks.size >= 2) intArrayOf(marks[0], marks[marks.size - 1]) else intArrayOf(if (marks.isEmpty()) 0 else marks[0], x.size)

    fun detect(sens: Int) = setMarks(Chop.hits(x, nov, sens))
    fun grid(bpm: Double, beats: Double) { val r = region(); setMarks(Chop.grid(r[0], r[1], max(40.0, bpm), beats)) }
    fun equal(n: Int) { val r = region(); setMarks(Chop.equal(r[0], r[1], max(1, n))) }
    fun clear() = setMarks(IntArray(0))

    fun removeSelected() {
        if (sel < 0 || sel >= marks.size) return
        val m = marks.toMutableList(); val o: MutableList<Chop.Opt?> = opts.toMutableList()
        m.removeAt(sel); o.removeAt(sel)
        setMarks(m.toIntArray(), o)
    }

    fun toggleKeep(i: Int) { if (i in opts.indices) opts[i].off = !opts[i].off }

    fun pieces(): List<Chop.Piece> = Chop.list(marks, x.size, 0, opts)
    fun kept(): List<Chop.Piece> = pieces().filter { !it.off }

    /** the chop under analysis sample [pos] (-1: before the first marker) */
    fun pieceAt(pos: Int): Int { var k = -1; for (i in marks.indices) if (marks[i] <= pos) k = i; return k }

    /** an analysis position as a frame of the sound */
    fun toFile(p: Int): Int = min(sound.frames, Math.round(p.toDouble() * sound.rate / Chop.RATE).toInt())

    /** A chop of the sound, in its rate and channels, faded as SLOOP fades its chops (times kept at the file's rate). */
    fun render(c: Chop.Piece, gain: Float = 1f): FloatArray {
        val a = toFile(c.start)
        val b = max(a + 1, toFile(c.end))
        val n = b - a
        val ch = sound.channels
        val fi = Math.round(Chop.FADE_IN.toDouble() * sound.rate / Chop.RATE).toInt()
        val fo = Math.round(Chop.FADE_OUT.toDouble() * sound.rate / Chop.RATE).toInt()
        val out = FloatArray(n * ch)
        for (i in 0 until n) {
            val g = (Chop.fade(i, n, fi, fo) * gain).toFloat()
            for (k in 0 until ch) out[i * ch + k] = sound.data[(a + i) * ch + k] * g
        }
        return out
    }

    /** One gain for the chops [use] so their loudest peak reaches 30000/32768 (SLOOP's level; their levels kept). */
    fun commonGain(use: List<Chop.Piece>): Float {
        var pk = 1e-9f
        for (c in use) {
            val a = toFile(c.start); val b = toFile(c.end)
            for (i in a * sound.channels until b * sound.channels) pk = max(pk, abs(sound.data[i]))
        }
        return (30000f / 32768f) / pk
    }

    /** the sound from the first kept chop to the end of the last, in mono: a loop for a firmware's bank */
    fun loopMono(): DoubleArray {
        val k = kept()
        val m = sound.mono()
        if (k.isEmpty()) return m
        val a = toFile(k.first().start)
        val b = max(a + 1, toFile(k.last().end))
        return m.copyOfRange(a, b)
    }

    /** a kept chop in mono, faded: a one-shot for a firmware's bank */
    fun pieceMono(c: Chop.Piece): DoubleArray {
        val r = render(c)
        val ch = sound.channels
        return DoubleArray(r.size / ch) { f -> var a = 0.0; for (k in 0 until ch) a += r[f * ch + k]; a / ch }
    }

    /** bars of 4 beats at [bpm] in [seconds] of sound, at least 1, at most LOOM-8's 16 */
    fun barsAt(bpm: Double, secs: Double): Int = max(1, min(LoomSamples.BARS_MAX, Math.round(secs * bpm / 240.0).toInt()))
}
