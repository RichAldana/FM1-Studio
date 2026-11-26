package app.fm1mix

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper

/**
 * The plugged FM-1's panel lent to the emulated one: PANEL MIDI (docs/PANEL-MIDI.md), whatever the firmware.
 *
 * One thread holds a conversation with the FM-1 that has it:
 *  - protocol 1 (0.29.2: Skiff-Remote, and any firmware with panel_link.c): every USB MIDI port is asked HELLO
 *    (F0 7D 50 4D 01 F7) once while it stays plugged; the one that answers says its name, what it does and what it
 *    leaves aside while lent; then PANEL (02 v: 1 lend, 0 take back, 2 ask), answered and pushed by the FM-1 when it
 *    switches itself, and LEDS (03 + 41 levels);
 *  - the earlier form, LOOM-8 PANEL MIDI 0.18.2-pm1 (found by its name, confirmed by SB_PING): SB_PANEL (F0 7D 46 4C
 *    30 v F7) and SB_LEDS (31 + 41 levels), inside LOOM-8's own commands.
 * Asked every second either way (a push can be missed). The lights go when a level changed (at most 20 a second)
 * and every half second, as LedLevels shows them.
 * [onChange] runs on the screen's thread: null = no FM-1 with PANEL MIDI, else whether it is lent.
 */
class PanelRemote(private val ctx: Context, private val onChange: (Boolean?) -> Unit) {
    companion object {
        const val SB_PANEL = 0x30
        const val SB_LEDS = 0x31
        const val NLED = 41
    }

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var gen = 0                   // the thread that is current (an old one finishes on its own)
    @Volatile private var want = -1                 // a switch asked by the app: 0 off, 1 on
    @Volatile var state: Boolean? = null            // what LOOM-8 last said
        private set
    @Volatile var name: String? = null              // its MIDI name, for the screen
        private set
    private val leds = IntArray(NLED)
    @Volatile private var ledsNew = false
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        val me = ++gen
        thread = Thread({ loop(me) }, "panel-remote").apply { isDaemon = true; start() }
    }

    /** The app goes away: the panel goes back to LOOM-8 (it would be deaf otherwise), then the link is closed. */
    fun stop() {
        gen++
        thread = null
    }

    /** Ask LOOM-8 to lend its panel (or take it back). */
    fun set(on: Boolean) { want = if (on) 1 else 0 }

    /** The emulated FM-1's lights (0..1, by matrix id), from the screen's refresh: as LOOM-8 shows them (LedLevels). */
    fun lights(l: FloatArray) {
        if (state != true) return
        synchronized(leds) {
            if (ledLevels.feed(l)) { System.arraycopy(ledLevels.sent, 0, leds, 0, NLED); ledsNew = true }
        }
    }

    private val ledLevels = LedLevels(NLED)

    private fun tell(s: Boolean?) {
        if (s == state || thread == null) return
        state = s
        ui.post { onChange(s) }
    }

    /** what the app talks to: the protocol, or LOOM-8's earlier form */
    private interface Target {
        /** PANEL: v 1 lend, 0 take back, 2 ask -> the state, null when no answer */
        fun panel(v: Int, ms: Long): Boolean?
        fun leds(levels: IntArray)
        /** a state the FM-1 pushed since the last call, or null */
        fun pushed(): Boolean?
        val lost: Boolean
        fun close()
    }

    private class Pm(private val link: AndroidSysexLink) : Target {
        private var push: Boolean? = null
        private fun frames(want: Int, ms: Long): IntArray? {
            val end = System.currentTimeMillis() + ms
            while (true) {
                val left = end - System.currentTimeMillis()
                val f = link.read(if (left > 0) left else 0) ?: return null
                if (f.size >= 6 && f[1].toInt() == 0x7D && f[2].toInt() == 0x50 && f[3].toInt() == 0x4D) {
                    val cmd = f[4].toInt()
                    if (cmd == 0x02 && f.size >= 7) {
                        val st = f[5].toInt() == 1
                        if (want != 0x02) push = st
                        else return intArrayOf(f[5].toInt())
                    } else if (cmd == want) return IntArray(f.size - 6) { f[5 + it].toInt() and 0x7F }
                }
                if (left <= 0) return null
            }
        }
        override fun panel(v: Int, ms: Long): Boolean? {
            link.send(byteArrayOf(0xF0.toByte(), 0x7D, 0x50, 0x4D, 0x02, v.toByte(), 0xF7.toByte()))
            return frames(0x02, ms)?.let { it[0] == 1 }
        }
        override fun leds(levels: IntArray) {
            val b = ByteArray(6 + levels.size)
            b[0] = 0xF0.toByte(); b[1] = 0x7D; b[2] = 0x50; b[3] = 0x4D; b[4] = 0x03
            for (i in levels.indices) b[5 + i] = levels[i].coerceIn(0, 127).toByte()
            b[b.size - 1] = 0xF7.toByte()
            link.send(b)
        }
        override fun pushed(): Boolean? { frames(-1, 0); return push.also { push = null } }
        override val lost get() = link.lost
        override fun close() = link.close()

        companion object {
            /** HELLO on [link]: (name, masked) when it answers protocol 1 */
            fun hello(link: AndroidSysexLink): Pair<String, String>? {
                link.drain()
                link.send(byteArrayOf(0xF0.toByte(), 0x7D, 0x50, 0x4D, 0x01, 0xF7.toByte()))
                val end = System.currentTimeMillis() + 400
                while (true) {
                    val left = end - System.currentTimeMillis()
                    if (left <= 0) return null
                    val f = link.read(left) ?: return null
                    if (f.size >= 12 && f[1].toInt() == 0x7D && f[2].toInt() == 0x50 && f[3].toInt() == 0x4D && f[4].toInt() == 0x01 && f[5].toInt() >= 1) {
                        val s = String(f, 10, f.size - 11, Charsets.US_ASCII).split('\u0000')
                        return (s.getOrNull(0) ?: "FM-1") + (s.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "") to (s.getOrNull(2) ?: "")
                    }
                }
            }
        }
    }

    private class Loom(private val l: LoomLink) : Target {
        override fun panel(v: Int, ms: Long): Boolean? =
            runCatching { l.retry(SB_PANEL, intArrayOf(v), ms, 2) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { it[0] == 1 }
        override fun leds(levels: IntArray) = l.post(SB_LEDS, levels)
        override fun pushed(): Boolean? = null
        override val lost get() = false
        override fun close() = l.close()
    }

    /** what the FM-1 leaves aside while its panel is lent (protocol 1's HELLO), for the screen */
    @Volatile var masked: String = ""
        private set

    /** the USB MIDI ports asked HELLO that did not answer (asked again once unplugged and back) */
    private val quiet = HashSet<Int>()

    @Suppress("DEPRECATION")
    private fun find(): Target? {
        val mm = ctx.getSystemService(Context.MIDI_SERVICE) as MidiManager
        val devs = mm.devices.filter { it.type == MidiDeviceInfo.TYPE_USB }
        quiet.retainAll(devs.map { it.id }.toSet())
        for (info in devs) {
            val n = SampleTargets.nameOf(info)
            if (SampleTargets.Loom8.nameMatches(n)) {
                // LOOM-8: the earlier form first (0.18.2-pm1), then protocol 1 (a later LOOM8-Remote)
                LoomLink.open(ctx, info)?.let { l ->
                    val r = runCatching { l.retry(SB_PANEL, intArrayOf(2), 400, 2) }.getOrNull()
                    if (r != null && r.isNotEmpty()) { name = n; masked = ""; tell(r[0] == 1); return Loom(l) }
                    l.close()
                }
            }
            if (info.id in quiet) continue
            val link = AndroidSysexLink.open(ctx, info) ?: continue
            val h = runCatching { Pm.hello(link) }.getOrNull()
            if (h == null) { link.close(); quiet += info.id; continue }
            val t = Pm(link)
            val st = t.panel(2, 500)
            if (st == null) { t.close(); quiet += info.id; continue }
            name = h.first
            masked = h.second
            tell(st)
            return t
        }
        return null
    }

    private fun nap(ms: Long) = try { Thread.sleep(ms) } catch (e: InterruptedException) { }

    private fun loop(me: Int) {
        var link: Target? = null
        var lastAsk = 0L
        var lastLeds = 0L
        var misses = 0
        while (gen == me) {
            try {
                if (link == null) {
                    link = find()
                    if (link == null) { want = -1; tell(null); nap(3000); continue }
                    misses = 0
                }
                if (link.lost) throw IllegalStateException("unplugged")
                val now = System.currentTimeMillis()
                val w = want
                link.pushed()?.let { tell(it) }
                if (w >= 0) {
                    want = -1
                    link.panel(w, 500)?.let { tell(it) }
                    lastAsk = now
                    lastLeds = 0L
                } else if (now - lastAsk >= 1000) {
                    lastAsk = now
                    val r = link.panel(2, 500)
                    if (r == null) { if (++misses >= 3) throw IllegalStateException("no answer") } else { misses = 0; tell(r) }
                }
                if (state == true && (ledsNew && now - lastLeds >= 50 || now - lastLeds >= 500)) {
                    val a = synchronized(leds) { ledsNew = false; leds.copyOf() }
                    link.leds(a)
                    lastLeds = now
                }
                nap(20)
            } catch (e: Exception) {
                // unplugged, no answer, or the port taken (the slicer): try again later
                link?.close(); link = null; name = null; masked = ""; tell(null)
                nap(2000)
            }
        }
        if (link != null && state == true) runCatching { link.panel(0, 500) }
        link?.close()
        if (thread == null) { name = null; state = null }   // (restarted: the new thread says what it finds)
    }
}
