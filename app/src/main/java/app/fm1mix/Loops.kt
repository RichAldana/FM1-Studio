package app.fm1mix

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * The loop player's four slots as the app keeps them (native/src/loops.rs plays them): the file each came from, how
 * it plays and starts, its volume, and a picture of its wave for the page. Kept in the app's settings and loaded again
 * at the next start (the file read again: the permission to read it is kept with it).
 *
 * WAV only for now (what the app records, and most others: WavStream). A slot holds 30 s at most: a passage of its
 * file ([Slot.from] to [Slot.to], chosen on the passage screen for a longer file).
 */
class LoopBank(private val ctx: Context) {
    class Slot {
        var uri: String? = null
        var name = ""
        var seconds = 0f
        var dir = 0            // 0 forward, 1 reverse, 2 ping-pong
        var once = false
        var trig = 0           // 0 clock, 1 touch, 2 note, 3 Start, 4 after another slot, 5 level
        var quant = 4          // the clock's step in beats: 1, 4, 8, 16
        var note = -1          // -1: any note
        var after = 0
        var levelDb = -30
        var stop = 1           // 0 at once, 1 at the clock's next step, 2 at the end of the pass
        var volPct = 100
        var wave = FloatArray(0)
        /** the passage of the file it plays (s), and the file's length */
        var from = 0f
        var to = 0f
        var fileSec = 0f
        /** loading, or its file could not be read again */
        var busy = false
        var missing = false
    }

    val slots = Array(4) { Slot() }
    private val prefs = ctx.getSharedPreferences("loops", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    var onChange: (() -> Unit)? = null

    init {
        for (i in slots.indices) {
            val s = slots[i]
            fun k(n: String) = "$i.$n"
            s.uri = prefs.getString(k("uri"), null)
            s.name = prefs.getString(k("name"), "") ?: ""
            s.seconds = prefs.getFloat(k("sec"), 0f)
            s.dir = prefs.getInt(k("dir"), 0)
            s.once = prefs.getBoolean(k("once"), false)
            s.trig = 0             // (the clock only, for now: the other starts are in loops.rs, not on the page)
            s.quant = prefs.getInt(k("quant"), 4)
            s.note = prefs.getInt(k("note"), -1)
            s.after = prefs.getInt(k("after"), (i + 3) % 4)
            s.levelDb = prefs.getInt(k("lvl"), -30)
            s.stop = prefs.getInt(k("stop"), 1)
            s.volPct = prefs.getInt(k("vol"), 100)
            s.wave = prefs.getString(k("wave"), "")!!.split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()
            s.from = prefs.getFloat(k("from"), 0f)
            s.to = prefs.getFloat(k("to"), 0f)
            s.fileSec = prefs.getFloat(k("file"), 0f)
        }
    }

    /** the settings to the player, and the files read again (at the start) */
    fun restore() {
        for (i in slots.indices) {
            apply(i)
            val u = slots[i].uri ?: continue
            load(i, Uri.parse(u), slots[i].from, slots[i].to, quiet = true) {}
        }
    }

    fun save(i: Int) {
        val s = slots[i]
        fun k(n: String) = "$i.$n"
        prefs.edit()
            .putString(k("uri"), s.uri).putString(k("name"), s.name).putFloat(k("sec"), s.seconds)
            .putInt(k("dir"), s.dir).putBoolean(k("once"), s.once).putInt(k("trig"), s.trig).putInt(k("quant"), s.quant)
            .putInt(k("note"), s.note).putInt(k("after"), s.after).putInt(k("lvl"), s.levelDb).putInt(k("stop"), s.stop)
            .putFloat(k("from"), s.from).putFloat(k("to"), s.to).putFloat(k("file"), s.fileSec)
            .putInt(k("vol"), s.volPct).putString(k("wave"), s.wave.joinToString(",") { "%.3f".format(java.util.Locale.ROOT, it) })
            .apply()
    }

    /** slot [i]'s settings to the player, and kept */
    fun apply(i: Int) {
        val s = slots[i]
        Native.loopSet(i, s.dir, s.once, s.trig, s.quant, s.note, s.after, s.levelDb.toFloat(), s.stop, s.volPct / 100f)
        save(i)
        onChange?.invoke()
    }

    /** The file's length in seconds (its header), or an error: [done] on the screen's thread. */
    fun probe(uri: Uri, done: (Double?, String) -> Unit) {
        io.execute {
            val r = runCatching { WavStream.open(ctx, uri).seconds }
            ui.post { done(r.getOrNull(), r.exceptionOrNull()?.let { it.message ?: it.toString() } ?: "") }
        }
    }

    /**
     * A file into slot [i]: its passage [fromSec]..[toSec] (0: from the start, 30 s at most), read, decoded and
     * resampled off the screen's thread; [done] gets "" or the error.
     */
    fun load(i: Int, uri: Uri, fromSec: Float = 0f, toSec: Float = 0f, quiet: Boolean = false, done: (String) -> Unit) {
        val s = slots[i]
        s.busy = true
        onChange?.invoke()
        io.execute {
            val err = runCatching {
                val w = WavStream.open(ctx, uri)
                val from = (fromSec * w.rate).toLong().coerceIn(0, maxOf(0, w.frames - 1))
                val end = if (toSec > fromSec) (toSec * w.rate).toLong() else w.frames
                val count = minOf(end, w.frames, from + (30.0 * w.rate).toLong()) - from
                val data = w.readAll(from, count)
                val sec = Native.loopLoad(i, data, w.channels, w.rate)
                if (sec < 0f) throw IllegalStateException(tr("son refusé", "sound refused"))
                val wave = overview(data, w.channels)
                val name = displayName(uri)
                if (!quiet) runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                ui.post {
                    s.uri = uri.toString(); s.name = name; s.seconds = sec; s.wave = wave; s.missing = false
                    s.from = from.toFloat() / w.rate; s.to = s.from + sec; s.fileSec = w.seconds.toFloat()
                    apply(i)
                }
                if (!quiet && w.seconds - fromSec > 30.05 && toSec <= fromSec) tr("coupé à 30 s", "cut at 30 s") else ""
            }.fold({ it }, { e ->
                if (quiet) ui.post { s.missing = true; onChange?.invoke() }   // (its file is gone: shown so)
                tr("Boucle ${i + 1} : ", "Loop ${i + 1}: ") + (e.message ?: e.toString())
            })
            ui.post { s.busy = false; onChange?.invoke(); done(err) }
        }
    }

    /**
     * The passage screen's choice into slot [i]: frames [a]..[b] of the file it holds (native/src/loops.rs, the
     * source), at [rate]; [done] gets "" or the error once it is in.
     */
    fun commit(i: Int, uri: Uri, rate: Int, a: Int, b: Int, fileSec: Float, done: (String) -> Unit) {
        val s = slots[i]
        s.busy = true
        onChange?.invoke()
        io.execute {
            val sec = Native.loopSrcCommit(i, a, b)
            val wave = FloatArray(96).also { Native.loopSrcPeaks(a, b, it) }
            val name = displayName(uri)
            if (sec >= 0f) runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            ui.post {
                s.busy = false
                if (sec >= 0f) {
                    s.uri = uri.toString(); s.name = name; s.seconds = sec; s.wave = wave; s.missing = false
                    s.from = a.toFloat() / rate; s.to = b.toFloat() / rate; s.fileSec = fileSec
                    apply(i)
                    done("")
                } else { onChange?.invoke(); done(tr("Boucle ${i + 1} : passage refusé", "Loop ${i + 1}: passage refused")) }
            }
        }
    }

    /** a long job off the screen's thread (the passage screen reading its file) */
    fun background(job: () -> Unit) = io.execute(job)

    fun clear(i: Int) {
        val s = slots[i]
        Native.loopClear(i)
        s.uri?.let { u -> runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        s.uri = null; s.name = ""; s.seconds = 0f; s.wave = FloatArray(0); s.missing = false
        save(i)
        onChange?.invoke()
    }

    /** 96 peaks over what plays */
    private fun overview(data: FloatArray, channels: Int): FloatArray {
        val frames = maxOf(1, data.size / channels)
        val bins = 96
        val out = FloatArray(bins)
        for (b in 0 until bins) {
            val a = b.toLong() * frames / bins
            val z = maxOf(a + 1, (b + 1).toLong() * frames / bins)
            var m = 0f
            var f = a
            val stepF = maxOf(1L, (z - a) / 400)            // (a few hundred reads per bin are enough)
            while (f < z && f < frames) {
                for (c in 0 until channels) m = maxOf(m, kotlin.math.abs(data[(f * channels + c).toInt()]))
                f += stepF
            }
            out[b] = m.coerceAtMost(1f)
        }
        return out
    }

    fun displayName(uri: Uri): String {
        val n = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "?"
        return n.substringAfterLast('/').removeSuffix(".wav").removeSuffix(".WAV")
    }

    /** the next clock step (beat, bar, 2 bars, 4 bars, round) */
    fun nextQuant(i: Int) {
        val s = slots[i]
        s.quant = QUANTS[(QUANTS.indexOf(s.quant) + 1).mod(QUANTS.size)]
        apply(i)
    }

    companion object {
        val QUANTS = intArrayOf(1, 4, 8, 16)
        fun quantName(q: Int) = when (q) {
            1 -> tr("temps", "beat"); 4 -> tr("mesure", "bar"); else -> tr("${q / 4} mesures", "${q / 4} bars")
        }
        /** the trigger, short, for the page */
        fun trigShort(s: Slot) = when (s.trig) {
            0 -> tr("CALÉ · ", "SYNC · ") + quantName(s.quant).uppercase()
            1 -> tr("TOUCHER", "TOUCH")
            2 -> "NOTE " + if (s.note < 0) tr("TOUTES", "ANY") else PanelView.noteName(s.note)
            3 -> "START FM-1"
            4 -> tr("APRÈS ", "AFTER ") + (s.after + 1)
            else -> tr("SEUIL ", "LEVEL ") + s.levelDb + " dB"
        }
    }
}
