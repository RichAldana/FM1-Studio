package app.fm1mix

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The firmware manager's bank, in the phone's Documents, beside the projects (one folder for all the app keeps):
 *
 *   Documents/FM1 Mix/Firmwares/
 *     Initial/                     the packages as the Hub publishes them (each one the app downloaded or flashed)
 *     Sauvegardes/<firmware>/<date>/
 *       firmware.fwsc              the package that was on the FM-1 (when the app knows it) or the one written
 *       donnees.<ext>              what the firmware saved (Fm1Backup: its file, its sections: LOOM-8's sounds)
 *       fiche.json                 which FM-1, when, its identity, the package's SHA-256, what the backup holds
 *     Historique.json              every flash and backup, newest first
 *
 * Files the app wrote itself (MediaStore, no permission asked): another app or a computer sees them too. Blocking.
 */
class FirmwareBank(private val ctx: Context) {
    companion object {
        const val ROOT = "FM1 Mix"
        val REL get() = Environment.DIRECTORY_DOCUMENTS + "/$ROOT/Firmwares/"
        val INITIAL get() = REL + "Initial/"
        val SAVES get() = REL + "Sauvegardes/"
        const val HISTORY = "Historique.json"
        private val STAMP = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT)

        fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifEmpty { "FM-1" }
    }

    private val cr get() = ctx.contentResolver
    private val coll: Uri get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** a file in the bank: its folder (relative, ending in /), its name, its uri, its size, when written (ms) */
    class Item(val dir: String, val name: String, val uri: Uri, val size: Long, val at: Long)

    private fun query(dirLike: String): List<Item> {
        val out = ArrayList<Item>()
        runCatching {
            cr.query(coll, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(dirLike), "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val dir = c.getString(1) ?: continue
                    out += Item(if (dir.endsWith("/")) dir else "$dir/", c.getString(2) ?: continue, ContentUris.withAppendedId(coll, c.getLong(0)), c.getLong(3), c.getLong(4) * 1000)
                }
            }
        }
        return out
    }

    /** every file of the bank */
    fun all(): List<Item> = query("$REL%")

    fun read(uri: Uri): ByteArray = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException(tr("fichier illisible", "unreadable file"))

    private fun mime(name: String) = if (name.endsWith(".json")) "application/json" else "application/octet-stream"

    /** [bytes] as [dir]/[name] (over the file of that name when there is one), whole or not at all */
    fun write(dir: String, name: String, bytes: ByteArray): Uri {
        query(dir).firstOrNull { it.dir == dir && it.name == name }?.let { old ->
            val ok = runCatching { cr.openOutputStream(old.uri, "wt")?.use { it.write(bytes) } != null }.getOrDefault(false)
            if (ok) return old.uri
        }
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(coll, v) ?: throw IllegalStateException(tr("Documents/$ROOT inaccessible", "Documents/$ROOT not reachable"))
        try {
            cr.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException(tr("fichier inaccessible", "file not reachable"))
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    // ------------------------------------------------------------------ Initial

    fun initials(): List<Item> = query("$INITIAL%").filter { it.name.endsWith(".fwsc", true) }

    /** a package as published, kept once ([name]: "chordism-0.9.fwsc") */
    fun keepInitial(name: String, bytes: ByteArray) {
        val n = clean(name)
        if (initials().any { it.name == n && it.size == bytes.size.toLong() }) return
        write(INITIAL, n, bytes)
    }

    // ------------------------------------------------------------------ Sauvegardes

    /** a backup's folder: its fiche, its package, its data (each may be missing but the fiche) */
    class Save(val dir: String, val fiche: JSONObject, val pkg: Item?, val data: Item?, val at: Long) {
        val firmware get() = fiche.optString("firmware", dir.removeSuffix("/").substringBeforeLast('/').substringAfterLast('/'))
        val when_ get() = dir.removeSuffix("/").substringAfterLast('/')
        val fm1 get() = fiche.optString("fm1", "A")
        val identity get() = fiche.optString("identity")
        val version get() = fiche.optString("version")
        val playing get() = fiche.optBoolean("playing", false)
        val hasSounds get() = fiche.optBoolean("sounds", false)
    }

    fun saves(): List<Save> {
        val byDir = query("$SAVES%").groupBy { it.dir }
        return byDir.mapNotNull { (dir, items) ->
            val f = items.firstOrNull { it.name == "fiche.json" } ?: return@mapNotNull null
            val j = runCatching { JSONObject(read(f.uri).toString(Charsets.UTF_8)) }.getOrNull() ?: return@mapNotNull null
            Save(dir, j, items.firstOrNull { it.name.endsWith(".fwsc") }, items.firstOrNull { it.name.startsWith("donnees.") }, f.at)
        }.sortedByDescending { it.at }
    }

    /** the backups of the firmware [name] (LOOM-8...) that hold data, newest first */
    fun savesOf(name: String): List<Save> = saves().filter { it.data != null && it.firmware.equals(name, true) }

    /** A new folder Sauvegardes/[firmware]/<now>/ with what is given; where it went (relative) */
    fun save(firmware: String, fiche: JSONObject, pkg: ByteArray?, data: ByteArray?, ext: String?): String {
        val dir = SAVES + clean(firmware) + "/" + STAMP.format(Date()) + "/"
        fiche.put("firmware", firmware).put("date", System.currentTimeMillis()).put("app", APP_VERSION)
        if (pkg != null) { fiche.put("package", "firmware.fwsc").put("sha256", Fm1Ota.sha256hex(pkg)); write(dir, "firmware.fwsc", pkg) }
        if (data != null && ext != null) { fiche.put("data", "donnees.$ext"); write(dir, "donnees.$ext", data) }
        write(dir, "fiche.json", fiche.toString(2).toByteArray(Charsets.UTF_8))
        return dir
    }

    // ------------------------------------------------------------------ Historique

    fun history(): JSONArray = query(REL).firstOrNull { it.dir == REL && it.name == HISTORY }
        ?.let { runCatching { JSONArray(read(it.uri).toString(Charsets.UTF_8)) }.getOrNull() } ?: JSONArray()

    /** an event at the top of Historique.json (at most 200 kept) */
    fun log(e: JSONObject) {
        e.put("date", System.currentTimeMillis())
        val old = history()
        val a = JSONArray().put(e)
        for (i in 0 until minOf(old.length(), 199)) a.put(old.get(i))
        runCatching { write(REL, HISTORY, a.toString(2).toByteArray(Charsets.UTF_8)) }
    }

    /** the package last written by the app onto an FM-1 that now says [identity] (to keep it with its backup) */
    fun packageFor(identity: String): ByteArray? {
        val h = history()
        for (i in 0 until h.length()) {
            val e = h.optJSONObject(i) ?: continue
            if (e.optString("what") == "flash" && e.optBoolean("ok") && e.optString("identity") == identity) {
                val dir = e.optString("dir").ifEmpty { return null }
                val f = query(dir).firstOrNull { it.dir == dir && it.name == "firmware.fwsc" } ?: return null
                return runCatching { read(f.uri) }.getOrNull()
            }
            if (e.optString("what") == "flash" && e.optBoolean("ok")) return null   // (something else was written after)
        }
        return null
    }

    /** whether the app installed m-bootloader on an FM-1 (the last flash that said so) */
    fun bootInstalled(): Boolean {
        val h = history()
        for (i in 0 until h.length()) {
            val e = h.optJSONObject(i) ?: continue
            if (e.optString("what") == "flash" && e.optBoolean("ok") && e.optBoolean("boot")) return true
        }
        return false
    }

    // ------------------------------------------------------------------ export

    /** the whole bank as a zip (its folders kept) */
    fun export(out: OutputStream): Int {
        var n = 0
        ZipOutputStream(out.buffered(1 shl 16)).use { z ->
            for (it in all()) {
                val bytes = runCatching { read(it.uri) }.getOrNull() ?: continue
                z.putNextEntry(ZipEntry("Firmwares/" + it.dir.removePrefix(REL) + it.name))
                z.write(bytes); z.closeEntry(); n++
            }
        }
        return n
    }
}
