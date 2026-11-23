package app.fm1mix

/**
 * The firmwares the app can run, from three lists, all read here (no Android in this file):
 *  1. fm1-emu's list (joakimeriksson.github.io/fm1-emu/firmwares.json, MIT): the FM-1 Firmware Hub's catalogue
 *     (thefirmwarehub.com, zvenson's) as fm1-emu runs it, with each firmware's licence, description and the lowest
 *     clock it plays at; firmwares fm1-emu cannot run yet are left out of it. The app's engine is fm1-emu, so this
 *     is the list of what works here. A copy ships in the app (assets) for the first start and offline.
 *  2. the Hub's own catalogue (thefirmwarehub.com/catalog.json), when it can be read: a firmware's newer version
 *     (the Hub is updated within hours), its package's size and SHA-256, and its kind (synth, drums...).
 *  3. our own few (EXTRAS): firmwares neither lists, from their author's GitHub release (Chordism).
 * Packages are fetched only when a firmware is chosen, from where the lists say (the Hub's unchanged mirror of each
 * author's release, or the author's release itself).
 */
object FirmwareCatalog {
    const val FM1EMU_URL = "https://joakimeriksson.github.io/fm1-emu/firmwares.json"
    const val HUB = "https://thefirmwarehub.com/"
    const val HUB_URL = HUB + "catalog.json"

    class Entry(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val license: String,
        val about: String,
        /** where the package is downloaded from; null: none to download ([byo]: the user brings it) */
        val pkg: String?,
        val repo: String?,
        /** the Hub's id: its card at thefirmwarehub.com/#fw-<hub> */
        val hub: String?,
        val installer: String?,
        /** the user's own copy is needed (M-VAVE's current firmware: its server does not let others download it) */
        val byo: Boolean,
        /** where the user gets it, for [byo] */
        val download: String?,
        /** the lowest emulated clock it plays at without running out of time (MHz); null: not measured */
        val minMhz: Int?,
        val sha256: String?,
        val size: Long?,
        /** the Hub's kind: "synth", "drums", "groovebox"... (null when the Hub's list was not read) */
        val kind: String?,
        /** listed by us (EXTRAS) */
        val extra: Boolean = false
    ) {
        val hubPage: String? get() = hub?.let { "${HUB}#fw-$it" }
        /** what the search looks in */
        val haystack: String by lazy { listOf(name, author, about, kind ?: "", license, id).joinToString(" ").lowercase() }

        fun copy(version: String = this.version, pkg: String? = this.pkg, sha256: String? = this.sha256,
                 size: Long? = this.size, kind: String? = this.kind) =
            Entry(id, name, version, author, license, about, pkg, repo, hub, installer, byo, download, minMhz, sha256, size, kind, extra)
    }

    class Catalog(val entries: List<Entry>, val default: String?, val updated: String?)

    /** Firmwares neither list has yet: their author's own release. */
    val EXTRAS = listOf(
        Entry("chordism", "Chordism", "0.9", "Charles Vestal", "MPL-2.0",
            "Un synthé d'accords : chaque touche joue un accord de quatre voix, avec arpégiateur. Le firmware de départ de FM1 Mix.",
            "https://github.com/charlesvestal/fm1-chordism/releases/download/v0.9/chordism-0.9.fwsc",
            "https://github.com/charlesvestal/fm1-chordism", null, null, false, null, null,
            "bb7b055f45ec3e8dc0cf2b5f9a79298dec0ec398f3131c3de90eb570fdc4d7a2", 616059L, "synth", extra = true)
    )
    const val DEFAULT_ID = "chordism"

    /** fm1-emu's firmwares.json */
    fun parseFm1emu(text: String): Catalog {
        val root = Json.obj(Json.parse(text)) ?: throw Json.Bad("not an object")
        val list = Json.arr(root["firmwares"]) ?: throw Json.Bad("no firmwares")
        val out = ArrayList<Entry>()
        for (v in list) {
            val m = Json.obj(v) ?: continue
            val id = Json.str(m, "id") ?: continue
            val pkg = Json.str(m, "package")
            val byo = Json.bool(m, "byo")
            if (pkg == null && !byo) continue                  // (fm1-emu's own built-in page firmware: nothing to fetch)
            out.add(Entry(id, Json.str(m, "name") ?: id, Json.str(m, "version") ?: "", Json.str(m, "author") ?: "",
                Json.str(m, "license") ?: "?", Json.str(m, "about") ?: "", pkg, Json.str(m, "repo"), Json.str(m, "hub"),
                Json.str(m, "installer"), byo, Json.str(m, "download"), Json.num(m, "minMhz")?.toInt(), null, null, null))
        }
        return Catalog(out, Json.str(root, "default"), null)
    }

    /** the Hub's catalog.json, by its id: (version, package URL, SHA-256, size, kind) */
    class HubItem(val version: String?, val pkg: String?, val sha256: String?, val size: Long?, val kind: String?)

    fun parseHub(text: String): Map<String, HubItem> {
        val root = Json.obj(Json.parse(text)) ?: return emptyMap()
        val list = Json.arr(root["firmwares"]) ?: return emptyMap()
        val out = HashMap<String, HubItem>()
        for (v in list) {
            val m = Json.obj(v) ?: continue
            val id = Json.str(m, "id") ?: continue
            out[id] = HubItem(Json.str(m, "version"), Json.str(m, "pkg")?.let { if (it.startsWith("http")) it else HUB + it },
                Json.str(m, "sha256")?.lowercase(), Json.num(m, "size")?.toLong(), Json.str(m, "kind"))
        }
        return out
    }

    /** whether version [a] is newer than [b]: "1.10.2" > "1.9", "0.9.1" > "0.9", "0.9" > "0.9-beta" */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-', '+', '_').filter { it.isNotEmpty() }
        val pa = parts(a)
        val pb = parts(b)
        for (k in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(k)
            val y = pb.getOrNull(k)
            if (x == null) return y != null && y.toIntOrNull() == null    // "0.9" vs "0.9-beta": the release is newer
            if (y == null) return x.toIntOrNull() != null                  // "0.9.1" vs "0.9": newer; "0.9-beta" vs "0.9": not
            val xi = x.toIntOrNull()
            val yi = y.toIntOrNull()
            if (xi != null && yi != null) { if (xi != yi) return xi > yi; continue }
            if (xi != null) return true                                    // a number after a word ("1.0.1" vs "1.0-rc")
            if (yi != null) return false
            if (x != y) return x > y
        }
        return false
    }

    /**
     * The lists together: fm1-emu's (what runs here), each one brought up to the Hub's newer version when the Hub has
     * one (its package; the measured clock kept), with the Hub's size, SHA-256 and kind; then our extras not already
     * listed. Sorted by name, the default first.
     */
    fun merge(fm1emu: Catalog, hub: Map<String, HubItem>?): Catalog {
        val out = ArrayList<Entry>()
        for (e in fm1emu.entries) {
            val h = e.hub?.let { hub?.get(it) } ?: hub?.get(e.id)
            if (h == null) { out.add(e); continue }
            val hubNewer = h.version != null && h.pkg != null && newer(h.version, e.version)
            val same = h.pkg != null && h.pkg == e.pkg
            out.add(when {
                hubNewer -> e.copy(version = h.version!!, pkg = h.pkg, sha256 = h.sha256, size = h.size, kind = h.kind)
                same -> e.copy(sha256 = h.sha256, size = h.size, kind = h.kind)
                else -> e.copy(kind = h.kind)
            })
        }
        val ids = out.map { it.id }.toHashSet()
        val names = out.map { it.name.lowercase().filter { c -> c.isLetterOrDigit() } }.toHashSet()
        for (x in EXTRAS) if (x.id !in ids && x.name.lowercase().filter { it.isLetterOrDigit() } !in names) out.add(x)
        out.sortWith(compareBy<Entry>({ it.byo }, { it.name.lowercase() }))   // (no firmware first: the user picks)
        return Catalog(out, DEFAULT_ID, fm1emu.updated)
    }

    /** the kinds present, most common first (for the filter chips) */
    fun kinds(c: Catalog): List<String> =
        c.entries.mapNotNull { it.kind }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    /** the entries matching every word of [query] (name, author, description, kind, licence), and [kind] if given */
    fun search(c: Catalog, query: String, kind: String? = null): List<Entry> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return c.entries.filter { e -> (kind == null || e.kind == kind) && words.all { e.haystack.contains(it) } }
    }
}
