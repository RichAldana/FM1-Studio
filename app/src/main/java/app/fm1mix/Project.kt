package app.fm1mix

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A project: the whole set-up kept in one file, to come back to it (Settings > Project).
 *
 * A ".fm1mix" file in Documents/Fm1mix projects, a zip holding:
 *  - project.json: the firmware (its id and name) and its settings in the app (clock, MIDI, profile, its volume, EQ,
 *    octave...), the mixer (the real FM-1, the master, the loops), each loop's settings and name;
 *  - firmware.fm1s: what the firmware saved itself (its patches, presets, projects: its flash, as on a real FM-1);
 *  - firmware.fwsc: the firmware's package when it was imported (one from the catalogue is fetched again by its id);
 *  - loop1.wav … loop4.wav: what each loop plays (its passage, 44.1 kHz stereo 16-bit): the project needs nothing else;
 *    or loop1.flac …: the same, compressed without loss (about half the size; Exporter > "Sons compressés");
 *    or nothing: a light project (Exporter > Léger) keeps the loops' settings and names, not their sounds.
 * Since 0.28 (version 2) project.json also keeps each track's effect ("fx") and the motions ("motion").
 * The same zip may be called ".zip" (Exporter > .zip): anything opens it, and so does the app.
 */
object Project {
    const val FOLDER = "Fm1mix projects"
    const val EXT = ".fm1mix"
    const val FORMAT = "fm1mix-project"
    const val VERSION = 2
    /** what a mail usually lets through */
    const val MAIL_MB = 20

    /** What a project file holds, read: the loops' sounds already out as files. */
    class Read(val json: JSONObject, val state: ByteArray?, val pkg: ByteArray?, val loops: Map<Int, File>)

    /** The project at one moment: what the screen knows ([json]), the firmware's memory and package, the loops' sounds
     *  (stereo 16-bit at 44.1 kHz). Taken once, written as often as asked (saved, exported, sent). */
    class Snapshot(val name: String, val json: JSONObject, val state: ByteArray?, val pkg: ByteArray?, val loops: Map<Int, ShortArray>) {
        /** its size written, about: [light] without the sounds, [flac] with them compressed (about 55 %, music) */
        fun size(light: Boolean, flac: Boolean): Long {
            val base = (state?.size ?: 0).toLong() * 6 / 10 + (pkg?.size ?: 0) + json.toString().length / 3 + 2000
            if (light) return base
            val pcm = loops.values.sumOf { it.size.toLong() * 2 + 44 }
            return base + if (flac) pcm * 55 / 100 else pcm * 92 / 100
        }
    }

    private val collection: Uri get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val relPath get() = Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER

    fun clean(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifEmpty { "Projet" }

    /** [s] as a zip into [out]: [light] without the loops' sounds, [flac] with them compressed. */
    fun write(out: OutputStream, s: Snapshot, light: Boolean, flac: Boolean) {
        val json = JSONObject(s.json.toString()).put("light", light)
        ZipOutputStream(out.buffered(1 shl 16)).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            /** already compressed: stored as is (deflating it again gains nothing, costs time) */
            fun store(n: String, b: ByteArray) {
                val e = ZipEntry(n)
                e.method = ZipEntry.STORED; e.size = b.size.toLong(); e.compressedSize = b.size.toLong()
                e.crc = CRC32().apply { update(b) }.value
                z.putNextEntry(e); z.write(b); z.closeEntry()
            }
            put("project.json", json.toString(2).toByteArray(Charsets.UTF_8))
            s.state?.let { put("firmware.fm1s", it) }
            s.pkg?.let { put("firmware.fwsc", it) }
            if (!light) for ((i, pcm) in s.loops) {
                val f = if (flac) Native.flacEncode(pcm, 2, 44100) else null
                if (f != null) store("loop${i + 1}.flac", f)
                else put("loop${i + 1}.wav", WavIO.write(FloatArray(pcm.size) { pcm[it] / 32768f }, 2, 44100, 16))
            }
        }
    }

    /** Save [s] in Documents/Fm1mix projects: over [over] (the project open) when it is still there, else as a new
     *  file. Its uri and where it went. */
    fun save(ctx: Context, s: Snapshot, over: Uri?): Pair<Uri, String> {
        val cr = ctx.contentResolver
        if (over != null) {
            val ok = runCatching {
                cr.openOutputStream(over, "wt")?.use { write(it, s, light = false, flac = false) } != null
            }.getOrDefault(false)
            if (ok) return over to "Documents/$FOLDER/" + (shownName(ctx, over) ?: (clean(s.name) + EXT))
        }
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, clean(s.name) + EXT)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(collection, v) ?: throw IllegalStateException(tr("Documents/$FOLDER inaccessible", "Documents/$FOLDER not reachable"))
        try {
            cr.openOutputStream(uri)?.use { write(it, s, light = false, flac = false) }
                ?: throw IllegalStateException(tr("fichier inaccessible", "file not reachable"))
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
        return uri to "Documents/$FOLDER/" + (shownName(ctx, uri) ?: (clean(s.name) + EXT))
    }

    /** the file's name as the phone shows it */
    fun shownName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** The project's file renamed with it (a file of ours, in Documents/Fm1mix projects): false when it cannot be. */
    fun rename(ctx: Context, uri: Uri, name: String): Boolean = runCatching {
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, clean(name) + EXT) }, null, null) > 0
    }.getOrDefault(false)

    /** A project file read: its sounds written into [dir] (names starting with [tag]). */
    fun read(input: InputStream, dir: File, tag: String): Read {
        dir.mkdirs()
        var json: JSONObject? = null
        var state: ByteArray? = null
        var pkg: ByteArray? = null
        val loops = HashMap<Int, File>()
        ZipInputStream(input.buffered(1 shl 16)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name.substringAfterLast('/')
                when {
                    n == "project.json" -> json = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                    n == "firmware.fm1s" -> state = z.readBytes()
                    n == "firmware.fwsc" -> pkg = z.readBytes()
                    n.matches(Regex("loop[1-4]\\.wav")) -> {
                        val i = n[4] - '1'
                        val f = File(dir, "$tag-${i + 1}.wav")
                        f.outputStream().use { z.copyTo(it) }
                        loops[i] = f
                    }
                    n.matches(Regex("loop[1-4]\\.flac")) -> {
                        val i = n[4] - '1'
                        val wav = Native.flacToWav(z.readBytes()) ?: throw IllegalStateException(tr("son ${n} illisible", "unreadable sound ${n}"))
                        val f = File(dir, "$tag-${i + 1}.wav")
                        f.writeBytes(wav)
                        loops[i] = f
                    }
                }
            }
        }
        val j = json ?: throw IllegalStateException(tr("ce n'est pas un projet FM1 Mix", "not an FM1 Mix project"))
        if (j.optString("format") != FORMAT) throw IllegalStateException(tr("ce n'est pas un projet FM1 Mix", "not an FM1 Mix project"))
        return Read(j, state, pkg, loops)
    }

    /** The projects the app saved, newest first: (name, uri, when saved in ms). */
    fun list(ctx: Context): List<Triple<String, Uri, Long>> {
        val out = ArrayList<Triple<String, Uri, Long>>()
        runCatching {
            ctx.contentResolver.query(collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("$relPath%", "%$EXT"), "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(1) ?: continue
                    out += Triple(n.removeSuffix(EXT), ContentUris.withAppendedId(collection, c.getLong(0)), c.getLong(2) * 1000)
                }
            }
        }
        return out
    }

    /** The system's picker opens here. */
    fun folderUri(): Uri = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$relPath")
}
