package app.fm1mix

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recording the app's sound to WAV in Music/FM1 Mix (Android's standard place for audio: any music, file or DAW app
 * finds it there; no storage permission needed). The files stay hidden ("pending") while they are written, and get
 * their final name when the recording stops. The writing itself is native (native/src/recorder.rs).
 */
class Recorder(private val ctx: Context) {
    companion object {
        const val FOLDER = "FM1 Mix"
        const val MODE_MIX = 0
        const val MODE_SPLIT = 1
        val BITS = intArrayOf(16, 24, 32)
        val RATES = intArrayOf(44100, 48000)
        val CHANNELS = intArrayOf(2, 1)

        /** A name a file can have: no path separators or characters Android's storage refuses. */
        fun clean(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(100)
                .ifEmpty { "FM1 Mix" }
    }

    /** What to record, and how (set from the settings, read when a recording starts). */
    var mode = MODE_MIX
    var bits = 16
    var rate = 44100
    var channels = 2

    /** A file of the recording in progress: its entry, and the end of its name ("" for the mix). */
    private class Part(val uri: Uri, val suffix: String)
    private var parts: List<Part> = emptyList()
    var defaultName = ""
        private set
    /** Writing now (after [stop], the files wait for [keep] or [discard]). */
    var recording = false
        private set
    private val stats = LongArray(3)

    private val cr get() = ctx.contentResolver
    private val collection: Uri get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun newEntry(display: String): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, display)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        return cr.insert(collection, v)
    }

    /** Start recording now. "" or an error. */
    fun start(): String {
        if (recording) return ""
        if (parts.isNotEmpty()) keep(defaultName)      // (a previous one never named: kept as it was)
        defaultName = "FM1 Mix " + SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(Date())
        val suffixes = if (mode == MODE_SPLIT) listOf(tr(" - émulé", " - emulated"), " - FM-1") else listOf("")
        val made = ArrayList<Part>()
        val fds = ArrayList<Int>()
        try {
            for (s in suffixes) {
                val uri = newEntry("$defaultName$s.wav") ?: throw IllegalStateException(tr("Musique/$FOLDER inaccessible", "Music/$FOLDER not reachable"))
                made += Part(uri, s)
                val pfd = cr.openFileDescriptor(uri, "rw") ?: throw IllegalStateException(tr("fichier inaccessible", "file not reachable"))
                fds += pfd.detachFd()                      // the native writer owns and closes it
            }
        } catch (e: Exception) {
            fds.forEach { runCatching { android.os.ParcelFileDescriptor.adoptFd(it).close() } }
            made.forEach { runCatching { cr.delete(it.uri, null, null) } }
            return e.message ?: tr("erreur", "error")
        }
        val err = Native.recStart(fds[0], if (fds.size > 1) fds[1] else -1, bits, rate, channels)
        if (err.isNotEmpty()) {
            made.forEach { runCatching { cr.delete(it.uri, null, null) } }
            return err
        }
        parts = made
        recording = true
        return ""
    }

    /** Seconds recorded so far. */
    fun seconds(): Long {
        if (!recording) return 0
        Native.recStats(stats)
        return stats[0] / 44100
    }

    /** Blocks lost because the storage was too slow (should stay 0). */
    fun dropped(): Long {
        if (!recording) return 0
        Native.recStats(stats)
        return stats[1]
    }

    /** Stop writing. The files stay hidden until [keep] or [discard]. "" or an error. */
    fun stop(): String {
        if (!recording) return ""
        recording = false
        return Native.recStop()
    }

    /** The files of the last recording kept (the mix, or the two tracks). */
    var lastKept: List<Uri> = emptyList()
        private set

    /** Give the files their name ([name] for the mix; "[name] - émulé", "[name] - FM-1" for two tracks) and show them. */
    fun keep(name: String): String {
        val base = clean(name)
        for (p in parts) {
            val v = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "$base${p.suffix}.wav")
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            runCatching { cr.update(p.uri, v, null, null) }
        }
        lastKept = parts.map { it.uri }
        parts = emptyList()
        return "Musique/$FOLDER/$base" + if (mode == MODE_SPLIT) tr(" (2 pistes)", " (2 tracks)") else ".wav"
    }

    fun discard() {
        parts.forEach { runCatching { cr.delete(it.uri, null, null) } }
        parts = emptyList()
    }
}
