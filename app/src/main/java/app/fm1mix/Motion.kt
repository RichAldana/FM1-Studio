package app.fm1mix

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor

/**
 * Motion recording: the gestures of a pass, played back round and round with the sound.
 *
 * Each track (FxState: 0 TOUT, 1 ÉMU, 2 FM-1, 3-6 the loops) has four patterns, A B C D. A pattern holds lanes, each
 * the moves of one thing over 1, 2, 4 or 8 bars (a bar: 4 beats, 16 steps; kept 6 points a step, 96 a bar, so a
 * gesture stays smooth):
 *  - "x", "y", "down": the finger on the track's pad (its effect);
 *  - "vol0".."vol3": the mixer's faders (emulated, real, master, loops), "eq0".."eq8": its EQ knobs;
 *  - "pot": the emulated FM-1's MASTER; "enc0".."enc5": its encoders (turns: played as turns, and turned back at the
 *    end of each pass, so every pass starts where the first did).
 *
 * REC arms (red): the recording starts on the next bar, lasts the pattern's length, then the pattern plays (green).
 * Recording writes over the pattern ("écraser"), or over only what is moved during the pass ("ajouter par-dessus").
 * A tap on a pattern: it takes over on the next bar (a tap on the one playing: it stops there). While a pattern
 * plays, a finger on the pad or a fader moved takes the hand from it (an option), and lets it go when it lifts.
 *
 * The player is a thread of its own (every 5 ms): the finger's lanes go straight to the effects (Native.fxTouch), the
 * rest through [Host] (the mixer, the panel).
 */
object Motion {
    const val TRACKS = 7
    const val PATTERNS = 4
    /** points a beat: 6 a step, 96 a bar */
    const val TPB = 24
    const val TICKS_PER_BAR = 4 * TPB
    val BARS = intArrayOf(1, 2, 4, 8)
    private const val NONE = -2

    interface Host {
        /** the beat now (Native.fxBeat) */
        fun beat(): Double
        /** a lane that is not the pad's (a fader, a knob, MASTER): to apply (from the player's thread) */
        fun apply(id: String, v: Float)
        /** an encoder's turns (from the player's thread) */
        fun encoder(e: Int, clicks: Int)
        /** the patterns changed (to draw again, to keep) */
        fun changed()
    }

    class Lane {
        var t = IntArray(32)
        var v = FloatArray(32)
        var n = 0

        /** a point (ticks kept in order; one tick holds one value, an encoder's turns add up) */
        fun add(tick: Int, value: Float, sum: Boolean) {
            var i = n
            while (i > 0 && t[i - 1] > tick) i--
            if (i > 0 && t[i - 1] == tick) { v[i - 1] = if (sum) v[i - 1] + value else value; return }
            if (n == t.size) { t = t.copyOf(n * 2); v = v.copyOf(n * 2) }
            System.arraycopy(t, i, t, i + 1, n - i)
            System.arraycopy(v, i, v, i + 1, n - i)
            t[i] = tick; v[i] = value; n++
        }

        /** a fader's value at [pos] (ticks): between its points, in a line; before the first, the first */
        fun at(pos: Float): Float {
            if (n == 0) return 0f
            if (pos <= t[0]) return v[0]
            if (pos >= t[n - 1]) return v[n - 1]
            var i = 1
            while (t[i] < pos) i++
            val a = t[i - 1].toFloat(); val b = t[i].toFloat()
            return v[i - 1] + (v[i] - v[i - 1]) * (pos - a) / (b - a)
        }

        /** a switch's value at [pos]: the last point at or before it (before the first: the last one, from the pass before) */
        fun stepAt(pos: Float): Float {
            if (n == 0) return 0f
            var r = v[n - 1]
            for (i in 0 until n) { if (t[i] <= pos) r = v[i] else break }
            return r
        }

        /** the turns of the points in (a, b] */
        fun sumBetween(a: Float, b: Float): Float {
            var s = 0f
            for (i in 0 until n) if (t[i] > a && t[i] <= b) s += v[i]
            return s
        }

        fun total(): Float { var s = 0f; for (i in 0 until n) s += v[i]; return s }

        fun copy(): Lane = Lane().also { it.t = t.copyOf(); it.v = v.copyOf(); it.n = n }

        /** the points up to [ticks] (a pattern made shorter) */
        fun cut(ticks: Int) { while (n > 0 && t[n - 1] >= ticks) n-- }
    }

    class Pattern {
        var bars = 2
        val lanes = LinkedHashMap<String, Lane>()
        fun isEmpty() = lanes.values.all { it.n == 0 }
        fun ticks() = bars * TICKS_PER_BAR
        fun copy() = Pattern().also { p -> p.bars = bars; for ((k, l) in lanes) p.lanes[k] = l.copy() }
    }

    class Track {
        val pats = Array(PATTERNS) { Pattern() }
        var playing = -1
        var queued = NONE
        /** the beat its pattern started on (a bar) */
        var start = 0.0
        /** the pattern REC writes into, and the length of the next recording */
        var target = 0
        var bars = 2
        var armed = false
        var overdub = false
        var recording = false
        var recStart = 0.0
        val touched = HashSet<String>()
        /** the finger's last state written in this pass (-1 none yet) */
        var recDown = -1
        // the player's
        var lastBeat = Double.NaN
        var lastPos = -1f
        val sent = HashMap<String, Float>()
        var padSent = false
        // the finger on this track's pad now (it takes the hand), and where
        var padHeld = false
        var fingerDown = false
        var fx = 0.5f
        var fy = 0.5f
        // where the pattern's finger is, for the pad to draw it
        @Volatile var px = 0.5f
        @Volatile var py = 0.5f
        @Volatile var pdown = false
    }

    val tracks = Array(TRACKS) { Track() }
    /** a finger (or a fader) takes the hand from a pattern playing */
    @Volatile var takeOver = true
    var host: Host? = null
    private val heldUntil = HashMap<String, Long>()
    private var thread: Thread? = null
    @Volatile private var running = false

    // ------------------------------------------------------------------ states, for the screen

    const val EMPTY = 0
    const val READY = 1
    const val PLAYING = 2
    const val NEXT = 3
    const val RECORDING = 4
    const val ARMED = 5

    @Synchronized fun state(t: Int, p: Int): Int {
        val tr = tracks[t]
        return when {
            tr.recording && tr.target == p -> RECORDING
            tr.armed && tr.target == p -> ARMED
            tr.queued == p -> NEXT
            tr.playing == p -> PLAYING
            !tr.pats[p].isEmpty() -> READY
            else -> EMPTY
        }
    }

    /** the track is recording, or waits for its bar to */
    @Synchronized fun recState(t: Int): Int = tracks[t].let { if (it.recording) RECORDING else if (it.armed) ARMED else EMPTY }
    @Synchronized fun hasAny(t: Int) = tracks[t].pats.any { !it.isEmpty() }
    @Synchronized fun isPlaying(t: Int) = tracks[t].playing >= 0
    /** a pattern's moves on the pad's x (or its first lane), as [n] points 0..1, for a small picture */
    @Synchronized fun curve(t: Int, p: Int, n: Int): FloatArray? {
        val pat = tracks[t].pats[p]
        if (pat.isEmpty()) return null
        val l = pat.lanes["x"]?.takeIf { it.n > 0 } ?: pat.lanes.values.firstOrNull { it.n > 0 } ?: return null
        val lo = (0 until l.n).minOf { l.v[it] }; val hi = (0 until l.n).maxOf { l.v[it] }
        val span = if (hi - lo < 1e-6f) 1f else hi - lo
        return FloatArray(n) { k -> ((l.at(k * pat.ticks() / (n - 1f)) - lo) / span).coerceIn(0f, 1f) }
    }

    // ------------------------------------------------------------------ the buttons

    /** a pattern touched: an empty one becomes where REC writes; one with moves plays on the next bar (stops, if it plays) */
    @Synchronized fun tapPattern(t: Int, p: Int) {
        val tr = tracks[t]
        if (tr.recording) return
        if (tr.pats[p].isEmpty()) { tr.target = p; tr.armed = false }
        else tr.queued = if (tr.playing == p && tr.queued == NONE) -1 else p
        host?.changed()
    }

    /** REC: arms (records from the next bar, into the track's chosen pattern); again: disarms, or stops a recording there */
    @Synchronized fun tapRec(t: Int, overdub: Boolean = false) {
        val tr = tracks[t]
        when {
            tr.recording -> finishRecording(tr, tr.recStart + tr.pats[tr.target].bars * 4.0)
            tr.armed -> tr.armed = false
            else -> { tr.armed = true; tr.overdub = overdub && !tr.pats[tr.target].isEmpty() }
        }
        host?.changed()
    }

    /** REC into pattern [p]: over it ([overdub] false) or over only what moves */
    @Synchronized fun recordInto(t: Int, p: Int, overdub: Boolean) {
        val tr = tracks[t]
        if (tr.recording) return
        tr.target = p
        if (!overdub) tr.bars = tr.pats[p].bars.takeIf { !tr.pats[p].isEmpty() } ?: tr.bars
        tr.armed = true
        tr.overdub = overdub && !tr.pats[p].isEmpty()
        host?.changed()
    }

    @Synchronized fun cycleBars(t: Int) {
        val tr = tracks[t]
        tr.bars = BARS[(BARS.indexOf(tr.bars) + 1).mod(BARS.size)]
        host?.changed()
    }

    @Synchronized fun setLength(t: Int, p: Int, bars: Int) {
        val pat = tracks[t].pats[p]
        pat.bars = bars
        for (l in pat.lanes.values) l.cut(pat.ticks())
        if (pat.isEmpty() || tracks[t].target == p) tracks[t].bars = bars
        host?.changed()
    }

    @Synchronized fun stop(t: Int) { val tr = tracks[t]; if (tr.playing >= 0) tr.queued = -1; host?.changed() }

    @Synchronized fun stopAll() {
        for (tr in tracks) { if (tr.playing >= 0) tr.queued = -1; tr.armed = false }
        host?.changed()
    }

    @Synchronized fun erase(t: Int, p: Int) {
        val tr = tracks[t]
        if (tr.recording && tr.target == p) return
        if (tr.playing == p) { releasePad(t, tr); tr.playing = -1; tr.queued = NONE }
        tr.pats[p] = Pattern().also { it.bars = tr.pats[p].bars }
        host?.changed()
    }

    @Synchronized fun copy(fromT: Int, fromP: Int, toT: Int, toP: Int) {
        if (fromT == toT && fromP == toP) return
        val dst = tracks[toT]
        if (dst.recording && dst.target == toP) return
        val c = tracks[fromT].pats[fromP].copy()
        // (the pad's lanes go with it: they drive the other track's effect there)
        dst.pats[toP] = c
        host?.changed()
    }

    // ------------------------------------------------------------------ what the hands do (from the screen)

    /** the finger on track [t]'s pad: recorded when the track records, and it takes the hand from a pattern playing */
    @Synchronized fun pad(t: Int, x: Float, y: Float, down: Boolean) {
        val tr = tracks[t]
        tr.fingerDown = down
        tr.padHeld = down && takeOver
        tr.fx = x; tr.fy = y
        if (!tr.recording) return
        val tick = tickNow(tr) ?: return
        if (down) { put(tr, "x", tick, x, false); put(tr, "y", tick, y, false) }
        val dv = if (down) 1 else 0
        if (dv != tr.recDown) { put(tr, "down", tick, dv.toFloat(), false); tr.recDown = dv }
    }

    /** a fader, a knob or MASTER moved (absolute), or an encoder turned ([id] "enc…": [v] the turns) */
    @Synchronized fun input(id: String, v: Float) {
        if (takeOver) heldUntil[id] = System.currentTimeMillis() + 400
        val tr = tracks.firstOrNull { it.recording } ?: return
        val tick = tickNow(tr) ?: return
        put(tr, id, tick, v, id.startsWith("enc"))
    }

    private fun tickNow(tr: Track): Int? {
        val h = host ?: return null
        val pat = tr.pats[tr.target]
        val pos = ((h.beat() - tr.recStart) * TPB).toInt()
        return if (pos in 0 until pat.ticks()) pos else null
    }

    private fun put(tr: Track, id: String, tick: Int, v: Float, sum: Boolean) {
        val pat = tr.pats[tr.target]
        if (tr.touched.add(id)) {
            // the first move of this lane in the pass: it starts again (over what was there)
            val l = Lane()
            pat.lanes[id] = l
            // (a fader starts the pass where it was: no jump at the pass' start)
            if (!sum && id != "down" && tick > 0) l.add(0, v, false)
        }
        pat.lanes.getOrPut(id) { Lane() }.add(tick, v, sum)
    }

    // ------------------------------------------------------------------ the player

    fun start(h: Host) {
        host = h
        if (running) return
        running = true
        thread = Thread({
            while (running) {
                runCatching { step() }
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
            }
        }, "motion").apply { priority = Thread.MAX_PRIORITY - 1; isDaemon = true; start() }
    }

    fun stopPlayer() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun releasePad(t: Int, tr: Track) {
        if (tr.padSent) { Native.fxTouch(t, tr.px, tr.py, false); tr.padSent = false }
        tr.pdown = false
    }

    private fun finishRecording(tr: Track, end: Double) {
        tr.recording = false
        val pat = tr.pats[tr.target]
        if (!pat.isEmpty()) {
            tr.playing = tr.target
            tr.start = end
            tr.queued = NONE
            tr.lastPos = -1f
        }
        tr.touched.clear()
    }

    @Synchronized private fun step() {
        val h = host ?: return
        val beat = h.beat()
        val now = System.currentTimeMillis()
        var changed = false
        for (t in 0 until TRACKS) {
            val tr = tracks[t]
            val last = tr.lastBeat
            tr.lastBeat = beat
            if (last.isNaN()) continue
            if (beat < last - 0.5) {
                // the clock started again (the FM-1's Start): the patterns from its first bar
                tr.start = 0.0; tr.lastPos = -1f
                if (tr.recording) tr.recStart = 0.0
                continue
            }
            val bar = floor(beat / 4.0)
            val crossed = bar > floor(last / 4.0)
            if (tr.armed && crossed) {
                tr.armed = false
                tr.recording = true
                tr.recStart = bar * 4.0
                val pat = tr.pats[tr.target]
                if (!tr.overdub) { pat.lanes.clear(); pat.bars = tr.bars }
                tr.touched.clear()
                tr.recDown = -1
                if (tr.playing == tr.target && !tr.overdub) releasePad(t, tr)
                // a finger already on the pad: it is in the pass from its first point
                if (tr.fingerDown) { put(tr, "x", 0, tr.fx, false); put(tr, "y", 0, tr.fy, false); put(tr, "down", 0, 1f, false); tr.recDown = 1 }
                changed = true
            }
            if (tr.recording) {
                val end = tr.recStart + tr.pats[tr.target].bars * 4.0
                if (beat >= end) { finishRecording(tr, end); changed = true }
            }
            if (tr.queued != NONE && crossed) {
                releasePad(t, tr)
                tr.playing = tr.queued
                tr.queued = NONE
                tr.start = bar * 4.0
                tr.lastPos = -1f
                tr.sent.clear()
                changed = true
            }
            if (tr.playing < 0) continue
            if (tr.recording && tr.target == tr.playing && !tr.overdub) continue
            val pat = tr.pats[tr.playing]
            val len = pat.ticks().toFloat()
            var pos = (((beat - tr.start) * TPB) % len).toFloat()
            if (pos < 0) pos += len
            val wrapped = tr.lastPos >= 0f && pos < tr.lastPos
            var hasPad = false
            var x = tr.px; var y = tr.py; var down = tr.pdown
            for ((id, lane) in pat.lanes) {
                if (lane.n == 0) continue
                // (while recording over it, what is being written is not played)
                if (tr.recording && tr.target == tr.playing && tr.touched.contains(id)) continue
                when {
                    id == "x" -> { hasPad = true; x = lane.at(pos) }
                    id == "y" -> { hasPad = true; y = lane.at(pos) }
                    id == "down" -> { hasPad = true; down = lane.stepAt(pos) > 0.5f }
                    id.startsWith("enc") -> {
                        if (tr.lastPos < 0f || (heldUntil[id] ?: 0) > now) continue
                        val d = if (wrapped) lane.sumBetween(tr.lastPos, len) - lane.total() + lane.sumBetween(-1f, pos)
                                else lane.sumBetween(tr.lastPos, pos)
                        val clicks = Math.round(d)
                        if (clicks != 0) h.encoder(id.removePrefix("enc").toIntOrNull() ?: continue, clicks)
                    }
                    else -> {
                        if ((heldUntil[id] ?: 0) > now) continue
                        val v = lane.at(pos)
                        if (tr.sent[id] != v) { tr.sent[id] = v; h.apply(id, v) }
                    }
                }
            }
            tr.px = x; tr.py = y; tr.pdown = down
            if (hasPad && !tr.padHeld) {
                Native.fxTouch(t, x, y, down)
                tr.padSent = down
            }
            tr.lastPos = pos
        }
        if (changed) h.changed()
    }

    // ------------------------------------------------------------------ kept (the app's file, the projects)

    @Synchronized fun toJson(): JSONObject {
        val o = JSONObject().put("version", 1).put("takeOver", takeOver)
        val ts = JSONArray()
        for (tr in tracks) {
            val pats = JSONArray()
            for (p in tr.pats) {
                val lanes = JSONObject()
                for ((id, l) in p.lanes) {
                    if (l.n == 0) continue
                    val a = JSONArray()
                    for (i in 0 until l.n) { a.put(l.t[i]); a.put(Math.round(l.v[i] * 10000) / 10000.0) }
                    lanes.put(id, a)
                }
                pats.put(JSONObject().put("bars", p.bars).put("lanes", lanes))
            }
            ts.put(JSONObject().put("patterns", pats).put("playing", tr.playing).put("bars", tr.bars).put("target", tr.target))
        }
        return o.put("tracks", ts)
    }

    @Synchronized fun fromJson(o: JSONObject?) {
        for ((t, tr) in tracks.withIndex()) {
            releasePad(t, tr)
            tr.playing = -1; tr.queued = NONE; tr.armed = false; tr.recording = false; tr.lastPos = -1f; tr.sent.clear()
            for (p in 0 until PATTERNS) tr.pats[p] = Pattern()
        }
        if (o == null) { host?.changed(); return }
        takeOver = o.optBoolean("takeOver", true)
        val ts = o.optJSONArray("tracks") ?: return
        for (t in 0 until minOf(TRACKS, ts.length())) {
            val to = ts.optJSONObject(t) ?: continue
            val tr = tracks[t]
            tr.bars = to.optInt("bars", 2).takeIf { it in BARS } ?: 2
            tr.target = to.optInt("target", 0).coerceIn(0, PATTERNS - 1)
            val pats = to.optJSONArray("patterns") ?: continue
            for (p in 0 until minOf(PATTERNS, pats.length())) {
                val po = pats.optJSONObject(p) ?: continue
                val pat = Pattern().also { it.bars = po.optInt("bars", 2).takeIf { b -> b in BARS } ?: 2 }
                val lanes = po.optJSONObject("lanes")
                lanes?.keys()?.forEach { id ->
                    val a = lanes.optJSONArray(id) ?: return@forEach
                    val l = Lane()
                    var i = 0
                    while (i + 1 < a.length()) { l.add(a.optInt(i), a.optDouble(i + 1).toFloat(), id.startsWith("enc")); i += 2 }
                    pat.lanes[id] = l
                }
                tr.pats[p] = pat
            }
            // (a pattern that played plays again: from the next bar)
            val pl = to.optInt("playing", -1)
            if (pl in 0 until PATTERNS && !tr.pats[pl].isEmpty()) tr.queued = pl
        }
        host?.changed()
    }
}
