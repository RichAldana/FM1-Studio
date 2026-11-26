package app.fm1mix

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper

/**
 * The plugged FM-1's panel lent to the emulated one: LOOM-8's PANEL MIDI (the fork's app/panelmidi.c).
 *
 * One thread holds a conversation with LOOM-8 (a [LoomLink], confirmed by SB_PING):
 *  - SB_PANEL (F0 7D 46 4C 30 v F7: 1 on, 0 off, 2 asks), answered with the state; asked every second, since the
 *    FM-1 can switch it itself (Setup, or GLO + FX held a second);
 *  - while it is on, SB_LEDS (F0 7D 46 4C 31 + 41 levels 0..127 by matrix id, F7): the emulated FM-1's lights,
 *    when they change (at most 20 a second) and every half second anyway, so the FM-1 shows what it now plays.
 * An FM-1 that does not run LOOM-8, or a LOOM-8 without PANEL MIDI (no answer to SB_PANEL), is left alone.
 * [onChange] runs on the screen's thread: null = no LOOM-8 with PANEL MIDI, else whether it is on.
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

    @Suppress("DEPRECATION")
    private fun find(): LoomLink? {
        val mm = ctx.getSystemService(Context.MIDI_SERVICE) as MidiManager
        for (info in mm.devices) {
            if (info.type != MidiDeviceInfo.TYPE_USB) continue
            val n = SampleTargets.nameOf(info)
            if (!SampleTargets.Loom8.nameMatches(n)) continue
            val l = LoomLink.open(ctx, info) ?: continue
            // a LOOM-8 that knows SB_PANEL answers it; an older one does not
            val r = runCatching { l.retry(SB_PANEL, intArrayOf(2), 400, 2) }.getOrNull()
            if (r == null || r.isEmpty()) { l.close(); continue }
            name = n
            tell(r[0] == 1)
            return l
        }
        return null
    }

    private fun nap(ms: Long) = try { Thread.sleep(ms) } catch (e: InterruptedException) { }

    private fun loop(me: Int) {
        var link: LoomLink? = null
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
                val now = System.currentTimeMillis()
                val w = want
                if (w >= 0) {
                    want = -1
                    val r = link.retry(SB_PANEL, intArrayOf(w), 500, 2)
                    if (r.isNotEmpty()) tell(r[0] == 1)
                    lastAsk = now
                    lastLeds = 0L
                } else if (now - lastAsk >= 1000) {
                    lastAsk = now
                    val r = link.req(SB_PANEL, intArrayOf(2), 500)
                    if (r.isNotEmpty()) tell(r[0] == 1)
                    misses = 0
                }
                if (state == true && (ledsNew && now - lastLeds >= 50 || now - lastLeds >= 500)) {
                    val a = synchronized(leds) { ledsNew = false; leds.copyOf() }
                    link.post(SB_LEDS, a)
                    lastLeds = now
                }
                nap(20)
            } catch (e: LoomLink.NoAnswer) {
                if (++misses >= 3) { link?.close(); link = null; name = null; tell(null) }
            } catch (e: Exception) {
                // unplugged, or the port taken (the slicer): try again later
                link?.close(); link = null; name = null; tell(null)
                nap(2000)
            }
        }
        if (link != null && state == true) runCatching { link.req(SB_PANEL, intArrayOf(0), 500) }
        link?.close()
        if (thread == null) { name = null; state = null }   // (restarted: the new thread says what it finds)
    }
}
