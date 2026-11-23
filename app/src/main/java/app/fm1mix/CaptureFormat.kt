package app.fm1mix

/*
 * The formats tried to capture the FM-1 over USB, and taking its master out of what comes in. No Android in here, so
 * it is checked on its own (tools/capture_check).
 *
 * LOOM-8 sends 10 channels (the master on 1 and 2, tracks 1-8 on 3-10), most firmwares 2. Android's USB driver handles
 * up to 8 (or 24) channels depending on the phone, and a plain "stereo" request may be downmixed from all of them.
 * So the first try asks by channel index for channels 1 and 2 only, which no driver mixes.
 */
object CaptureFormat {
    /** A way to ask: [channels] interleaved in what comes in; [indexMask] true: by channel index, else positional. */
    class Try(val channels: Int, val indexMask: Boolean) {
        override fun toString(): String = "$channels canaux" + if (indexMask) " (index)" else " (stéréo)"
    }

    /** What to try, in order, for a device that reports [deviceCounts] (empty: unknown). */
    fun tries(deviceCounts: IntArray): List<Try> {
        val out = ArrayList<Try>()
        out.add(Try(2, true))                         // channels 1 and 2 by index: the master, never a downmix
        out.add(Try(2, false))                        // plain stereo
        for (n in deviceCounts.sortedDescending()) {  // all of what the device has, by index: 1 and 2 kept
            if (n > 2 && n <= 24 && out.none { it.channels == n && it.indexMask }) out.add(Try(n, true))
        }
        return out
    }

    /** The bit mask of the first [n] channels. */
    fun indexMask(n: Int): Int = if (n >= 31) -1 else (1 shl n) - 1

    /**
     * Copy channels 1 and 2 of [samples] interleaved samples of [channels] channels from [src] into [dst] (stereo,
     * interleaved). Returns the number of samples written (2 per whole frame); a partial frame at the end is dropped.
     */
    fun firstTwo(src: ShortArray, samples: Int, channels: Int, dst: ShortArray): Int {
        if (channels == 2) {
            val n = minOf(samples and 1.inv(), dst.size)
            System.arraycopy(src, 0, dst, 0, n)
            return n
        }
        val frames = minOf(samples / channels, dst.size / 2)
        for (f in 0 until frames) {
            dst[2 * f] = src[f * channels]
            dst[2 * f + 1] = src[f * channels + 1]
        }
        return frames * 2
    }
}
