package app.fm1mix

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager

/**
 * Where a slice can go besides a WAV file: the firmware running on the plugged FM-1, each with its own way.
 *
 * The slicer itself works on the app's sound (its recordings: WAV files), so what language a firmware is written in,
 * or how it makes its sound, does not matter to it. What does depend on the firmware is sending samples INTO it: each
 * one that can take samples has its own protocol. So:
 *  1. detection: the FM-1's MIDI name (the USB product name each firmware gives itself: "LOOM-8 FM-1",
 *     "Chordism FM-1"...), then, for a firmware with a protocol here, its own answer to a harmless request (LOOM-8:
 *     SB_PING) before anything else is sent to it;
 *  2. adaptation: a [Target] per firmware says what it takes (a loop following the tempo, one-shots, how many) and
 *     does it; a firmware with no target here, or none recognised, gets the WAV export only.
 * Adding a firmware: a Target in [ALL] (and its protocol beside LoomLink).
 */
object SampleTargets {

    /**
     * Sending samples into a firmware: frozen (0.22) until it has been tried on a real LOOM-8. While false the slicer
     * sends nothing to the FM-1 (not even the detection's SB_PING) and offers the WAV export only. PANEL MIDI
     * (PanelRemote) is not concerned.
     */
    const val SEND_ENABLED = false

    /** What the slicer may send to a firmware. */
    class Can(val loop: Boolean, val oneShots: Boolean, val maxSamples: Int, val note: String)

    interface Target {
        /** shown to the user */
        val label: String
        /** a quick test on the MIDI device's name, before anything is sent to it */
        fun nameMatches(name: String): Boolean
        val can: Can
        /** Open and confirm it (blocking); null when it does not answer as this firmware. */
        fun connect(ctx: Context, info: MidiDeviceInfo): Session?
    }

    /** A confirmed firmware, ready to take samples. */
    interface Session {
        val target: Target
        /**
         * Add [items] (new samples: mono sound and how to play it) to what the firmware holds; [progress] gets a step
         * name and a fraction. Returns what it did, for the screen. Blocking.
         */
        fun add(items: List<NewSample>, progress: (String, Double) -> Unit): String
        /** Put back what the firmware held before the last [add], when it keeps such a copy. Blocking. */
        fun restoreLast(progress: (String, Double) -> Unit): String = throw IllegalStateException(tr("${target.label} : pas de retour arrière", "${target.label}: no way back"))
        val canRestore: Boolean get() = false
        fun close()
    }

    /** A sample to send: [x] mono at [sr]; a loop of [bars] bars (0: a one-shot) played from [root]. */
    class NewSample(val name: String, val x: DoubleArray, val sr: Int, val root: Int, val bars: Int)

    // ------------------------------------------------------------------ LOOM-8

    object Loom8 : Target {
        override val label = "LOOM-8"
        override fun nameMatches(name: String) = Regex("LOOM-8", RegexOption.IGNORE_CASE).containsMatchIn(name)
        override val can = Can(loop = true, oneShots = true, maxSamples = LoomSamples.BANK_N,
            note = tr("Banque d'échantillons de LOOM-8 : une boucle suit le tempo du morceau (moteur slice), les morceaux ", "LOOM-8's sample bank: a loop follows the song's tempo (slice engine), the slices ") +
                tr("se jouent comme des sons. Les échantillons déjà présents sont gardés ; un échantillon du même nom est remplacé.", "play as sounds. The samples already there are kept; a sample with the same name is replaced."))

        override fun connect(ctx: Context, info: MidiDeviceInfo): Session? {
            val link = LoomLink.open(ctx, info) ?: return null
            return LoomSession(ctx, link)
        }
    }

    private class LoomSession(private val ctx: Context, private val link: LoomLink) : Session {
        override val target: Target get() = Loom8

        override fun add(items: List<NewSample>, progress: (String, Double) -> Unit): String {
            progress(tr("Lecture de la banque de LOOM-8", "Reading LOOM-8's bank"), 0.0)
            val before = link.readBank { progress(tr("Lecture de la banque de LOOM-8", "Reading LOOM-8's bank"), it) }
                ?: throw IllegalStateException(tr("LOOM-8 n'a pas de banque d'échantillons (même ses sons d'usine) : ", "LOOM-8 has no sample bank (not even its factory sounds): ") +
                    tr("réinstalle-le depuis sa page d'installation, puis recommence.", "reinstall it from its install page, then try again."))
            // a copy of the bank as it was, to put it back if needed (Settings of the slicer)
            backup.writeBytes(before)
            val names = items.map { LoomSamples.cleanName(it.name) }.toSet()
            val kept = LoomSamples.keptOf(before).filter { it.isFactory || LoomSamples.cleanName(it.name) !in names }
            val own = kept.count { !it.isFactory }
            if (own + items.size > LoomSamples.BANK_N)
                throw IllegalStateException(tr("LOOM-8 garde $own échantillons à toi : il n'en prend que ${LoomSamples.BANK_N} en tout.", "LOOM-8 keeps $own samples of yours: it only takes ${LoomSamples.BANK_N} in all."))
            // the user's samples first, then the new ones, then the factory set (as loom_samples.py orders them)
            val news = items.map { LoomSamples.Item(it.name, it.root, null, it.bars, null, x = it.x, sr = it.sr) }
            val all = kept.filter { !it.isFactory } + news + kept.filter { it.isFactory }
            progress(tr("Préparation", "Preparing"), 0.0)
            val built = try { LoomSamples.build(all) } catch (e: LoomSamples.TooLong) {
                throw IllegalStateException(tr("Pas assez de place dans la banque de LOOM-8 (${e.message}). Raccourcis ou garde moins de morceaux.", "Not enough room in LOOM-8's bank (${e.message}). Shorten them or keep fewer slices."))
            }
            link.sendBank(built.img) { progress(tr("Écriture dans LOOM-8", "Writing into LOOM-8"), it) }
            return tr("${items.size} échantillon(s) dans LOOM-8, à ${built.chose} Hz", "${items.size} sample(s) in LOOM-8, at ${built.chose} Hz")
        }

        private val backup get() = java.io.File(ctx.filesDir, "loom8-bank-before.bin")
        override val canRestore: Boolean get() = backup.exists()

        override fun restoreLast(progress: (String, Double) -> Unit): String {
            if (!backup.exists()) throw IllegalStateException(tr("Aucune sauvegarde de la banque de LOOM-8.", "No backup of LOOM-8's bank."))
            link.sendBank(backup.readBytes()) { progress(tr("Restauration de la banque", "Restoring the bank"), it) }
            return tr("Banque de LOOM-8 remise comme avant le dernier envoi", "LOOM-8's bank put back as before the last send")
        }

        override fun close() = link.close()
    }

    // ------------------------------------------------------------------ the registry

    /** The firmwares the slicer can send to, tried in order. */
    val ALL: List<Target> = listOf(Loom8)

    /** What was found on MIDI: the FM-1's name, and the session if a firmware here confirmed it. */
    class Found(val deviceName: String?, val session: Session?)

    fun nameOf(info: MidiDeviceInfo): String {
        val p = info.properties
        return listOfNotNull(p.getString(MidiDeviceInfo.PROPERTY_NAME), p.getString(MidiDeviceInfo.PROPERTY_PRODUCT))
            .distinct().joinToString(" / ")
    }

    /** Find the plugged FM-1 and the firmware it runs (blocking). USB only: samples never go over Bluetooth. */
    @Suppress("DEPRECATION")
    fun detect(ctx: Context): Found {
        val mm = ctx.getSystemService(Context.MIDI_SERVICE) as MidiManager
        val usb = mm.devices.filter { it.type == MidiDeviceInfo.TYPE_USB }
        var firstName: String? = null
        for (info in usb) {
            val name = nameOf(info)
            if (firstName == null) firstName = name
            for (t in ALL) {
                if (!t.nameMatches(name)) continue
                val s = t.connect(ctx, info)
                if (s != null) return Found(name, s)
            }
        }
        return Found(firstName, null)
    }
}
