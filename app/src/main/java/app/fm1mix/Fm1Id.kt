package app.fm1mix

/**
 * Telling the FM-1 from other USB audio devices (a USB-C hub's headphone output, a USB headset), so that capture,
 * "output on the FM-1" and the built-in USB driver never take a headset for it. No Android in here.
 *
 * An FM-1 is a USB device that: has the identity Felucca's family of firmwares share (pid.codes 1209:0001: Chordism,
 * LOOM-8, SLOOP...), or a name of the FM-1's ("FM-1", "M-VAVE", "Felucca"; firmwares name themselves "<name> FM-1"),
 * or a MIDI interface beside its audio (a headphone output never has one).
 */
object Fm1Id {
    private val NAME = Regex("FM-?1(\\b|$)|M-?VAVE|FELUCCA", RegexOption.IGNORE_CASE)

    fun looksLikeFm1(vid: Int, pid: Int, name: String, hasMidi: Boolean): Boolean =
        (vid == 0x1209 && pid == 0x0001) || NAME.containsMatchIn(name) || hasMidi

    /** Android's name for a USB audio device ("USB-Audio - Chordism FM-1") without its prefix, lower case */
    fun bare(audioName: String): String =
        audioName.trim().replace(Regex("^USB[- ]?Audio\\s*-\\s*", RegexOption.IGNORE_CASE), "").lowercase()

    /** whether the audio device [audioName] is one of the USB devices named [usbNames] */
    fun isOneOf(audioName: String, usbNames: Collection<String>): Boolean {
        val a = bare(audioName)
        if (a.isEmpty()) return false
        return usbNames.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { u -> a == u || a.contains(u) || u.contains(a) }
    }
}
