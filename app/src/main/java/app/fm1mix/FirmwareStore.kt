package app.fm1mix

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The firmwares on the phone: the lists (refreshed at most once a day, or when asked; the copy shipped in the app
 * until then), and the packages downloaded when a firmware is chosen, checked against the Hub's SHA-256 when it
 * gives one, kept until deleted. No Android in here: [dir] is the app's own folder, [bundled] the shipped list.
 */
class FirmwareStore(private val dir: File, private val bundled: () -> String) {
    companion object {
        const val DAY_MS = 24L * 3600 * 1000
        const val MAX_BYTES = 8L shl 20
        const val UA = "FM1-Mix/0.10 (Android; emulator of the M-VAVE FM-1; github.com/joakimeriksson/fm1-emu)"

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** an HTTP(S) GET (or any URL Java opens: file: for the tests), redirects followed */
        fun open(url: String): Pair<InputStream, Long> {
            val c = java.net.URI(url).toURL().openConnection()
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", UA)
            if (c is HttpURLConnection) {
                c.instanceFollowRedirects = true
                val code = c.responseCode
                if (code !in 200..299) { c.disconnect(); throw java.io.IOException("HTTP $code") }
            }
            return Pair(c.getInputStream(), c.contentLengthLong)
        }

        fun get(url: String): String = open(url).first.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private val pkgDir = File(dir, "catalog").apply { mkdirs() }
    private val fm1emuCache = File(dir, "list-fm1emu.json")
    private val hubCache = File(dir, "list-hub.json")
    private val stamp = File(dir, "list-updated.txt")
    private val index = File(dir, "installed.json")

    // ------------------------------------------------------------------ the lists

    /** the lists merged: the last ones read (else the shipped copy) */
    fun catalog(): FirmwareCatalog.Catalog {
        val base = runCatching { FirmwareCatalog.parseFm1emu(fm1emuCache.readText()) }.getOrNull()
            ?: FirmwareCatalog.parseFm1emu(bundled())
        val hub = runCatching { FirmwareCatalog.parseHub(hubCache.readText()) }.getOrNull()
        return FirmwareCatalog.merge(base, hub)
    }

    /** when the lists were last read (0: never: the shipped copy) */
    fun updatedAt(): Long = runCatching { stamp.readText().trim().toLong() }.getOrDefault(0L)

    /**
     * Read the lists again when the last time is a day old (or [force]). Each list is kept only when it reads as one.
     * Returns what happened, for the screen. Blocking: network.
     */
    fun refresh(force: Boolean, fetch: (String) -> String = ::get, now: Long = System.currentTimeMillis()): String {
        if (!force && now - updatedAt() < DAY_MS) return ""
        var got = 0
        val errors = ArrayList<String>()
        runCatching { val t = fetch(FirmwareCatalog.FM1EMU_URL); FirmwareCatalog.parseFm1emu(t); writeSafely(fm1emuCache, t); got++ }
            .onFailure { errors += tr("liste de fm1-emu : ${it.message}", "fm1-emu list: ${it.message}") }
        runCatching { val t = fetch(FirmwareCatalog.HUB_URL); if (FirmwareCatalog.parseHub(t).isEmpty()) throw Json.Bad(tr("vide", "empty")); writeSafely(hubCache, t); got++ }
            .onFailure { errors += tr("catalogue du Hub : ${it.message}", "Hub catalogue: ${it.message}") }
        if (got > 0) writeSafely(stamp, now.toString())
        return if (errors.isEmpty()) tr("Liste à jour", "List up to date") else if (got > 0) tr("Liste à jour en partie (${errors.joinToString("; ")})", "List partly updated (${errors.joinToString("; ")})") else tr("Pas de réseau : liste gardée (${errors.joinToString("; ")})", "No network: list kept (${errors.joinToString("; ")})")
    }

    private fun writeSafely(f: File, text: String) {
        val t = File(f.parentFile, f.name + ".tmp")
        t.writeText(text)
        if (!t.renameTo(f)) { f.delete(); t.renameTo(f) }
    }

    // ------------------------------------------------------------------ the packages

    class Installed(val id: String, val version: String, val file: File, val sha256: String)

    private fun readIndex(): MutableMap<String, Installed> {
        val out = LinkedHashMap<String, Installed>()
        val root = runCatching { Json.obj(Json.parse(index.readText())) }.getOrNull() ?: return out
        for ((id, v) in root) {
            val m = Json.obj(v) ?: continue
            val f = File(pkgDir, Json.str(m, "file") ?: continue)
            if (f.exists()) out[id] = Installed(id, Json.str(m, "version") ?: "", f, Json.str(m, "sha256") ?: "")
        }
        return out
    }

    private fun writeIndex(m: Map<String, Installed>) {
        val body = m.values.joinToString(",\n") {
            "${Json.quote(it.id)}: {\"version\": ${Json.quote(it.version)}, \"file\": ${Json.quote(it.file.name)}, \"sha256\": ${Json.quote(it.sha256)}}"
        }
        writeSafely(index, "{\n$body\n}\n")
    }

    fun installed(): Map<String, Installed> = readIndex()
    fun installed(id: String): Installed? = readIndex()[id]

    /** whether [e]'s current version is on the phone */
    fun isCurrent(e: FirmwareCatalog.Entry): Boolean = installed(e.id)?.version == e.version

    class Bad(msg: String) : Exception(msg)

    /**
     * Download [e]'s package (blocking): checked against its SHA-256 when known, at most MAX_BYTES; an older version of
     * the same firmware removed. [progress] gets (bytes, total or -1).
     */
    fun download(e: FirmwareCatalog.Entry, progress: (Long, Long) -> Unit = { _, _ -> }, opener: (String) -> Pair<InputStream, Long> = ::open): Installed {
        val url = e.pkg ?: throw Bad(tr("${e.name} : aucun paquet à télécharger", "${e.name}: no package to download"))
        val safe = e.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val ver = e.version.filter { it.isLetterOrDigit() || it == '.' || it == '-' }
        val tmp = File(pkgDir, "$safe.part")
        val (input, len) = opener(url)
        var n = 0L
        input.use { s ->
            tmp.outputStream().use { o ->
                val b = ByteArray(1 shl 16)
                while (true) {
                    val k = s.read(b)
                    if (k < 0) break
                    n += k
                    if (n > MAX_BYTES) throw Bad(tr("${e.name} : paquet trop gros", "${e.name}: package too big"))
                    o.write(b, 0, k)
                    progress(n, len)
                }
            }
        }
        if (n == 0L) { tmp.delete(); throw Bad(tr("${e.name} : paquet vide", "${e.name}: empty package")) }
        val sum = sha256(tmp)
        if (e.sha256 != null && e.sha256 != sum) { tmp.delete(); throw Bad(tr("${e.name} : le paquet reçu n'est pas celui du catalogue (SHA-256)", "${e.name}: the package received is not the catalogue's (SHA-256)")) }
        val dst = File(pkgDir, "$safe-$ver.fwsc")
        dst.delete()
        if (!tmp.renameTo(dst)) throw Bad(tr("${e.name} : rangement impossible", "${e.name}: could not be stored"))
        val m = readIndex()
        m[e.id]?.let { old -> if (old.file != dst) old.file.delete() }
        val inst = Installed(e.id, e.version, dst, sum)
        m[e.id] = inst
        writeIndex(m)
        return inst
    }

    fun delete(id: String) {
        val m = readIndex()
        m.remove(id)?.file?.delete()
        writeIndex(m)
    }

    /** bytes taken by the downloaded packages */
    fun used(): Long = readIndex().values.sumOf { it.file.length() }
}
