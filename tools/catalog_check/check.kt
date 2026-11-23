// The catalogue and the store on the real fm1-emu list (web/firmwares.json), a Hub catalogue and local downloads.
//   kotlinc ../../app/src/main/java/app/fm1mix/{Json,FirmwareCatalog,FirmwareStore}.kt check.kt -include-runtime -d check.jar
//   java -jar check.jar path/to/fm1-emu/web/firmwares.json path/to/a.fwsc
import app.fm1mix.FirmwareCatalog as C
import app.fm1mix.FirmwareStore
import app.fm1mix.Json
import java.io.File

var failures = 0
fun check(ok: Boolean, msg: String) { println((if (ok) "  ok   " else "  FAIL ") + msg); if (!ok) failures++ }

fun main(args: Array<String>) {
    val listText = File(args[0]).readText()
    val pkg = File(args[1])

    // JSON
    val j = Json.obj(Json.parse("""{"a": [1, 2.5e1, -3], "b": "x\"yé\\n", "c": true, "d": null, "e": {}}"""))!!
    check(Json.arr(j["a"]) == listOf(1.0, 25.0, -3.0) && j["b"] == "x\"yé\\n" && j["c"] == true && j.containsKey("d") && j["d"] == null, "JSON: numbers, escapes, booleans, null")
    check(runCatching { Json.parse("{\"a\": }") }.isFailure && runCatching { Json.parse("[1,2] x") }.isFailure, "JSON: bad text refused")
    check(Json.parse(Json.quote("a\"b\\c\n")) == "a\"b\\c\n", "JSON: quote round trip")

    // fm1-emu's list
    val base = C.parseFm1emu(listText)
    val n = base.entries.size
    check(n > 150, "fm1-emu's list read: $n firmwares")
    val fel = base.entries.first { it.id == "felucca" }
    check(fel.license == "GPL-3.0" && fel.pkg!!.startsWith(C.HUB) && fel.hub == "felucca", "an entry: Felucca ${fel.version}, ${fel.license}, from the Hub")
    val mv = base.entries.first { it.id == "mvave" }
    check(mv.byo && mv.pkg == null && mv.download != null, "M-VAVE's current firmware: bring your own (${mv.download?.take(40)}...)")
    check(base.entries.none { it.pkg == null && !it.byo }, "no entry without a package (but bring-your-own)")
    check(base.entries.count { it.minMhz != null } > 40, "measured clocks: ${base.entries.count { it.minMhz != null }}")

    // versions
    check(C.newer("1.10.2", "1.9") && C.newer("0.9.1", "0.9") && C.newer("0.9", "0.9-beta") && C.newer("2.0", "1.99.9"), "versions: newer ones")
    check(!C.newer("1.9", "1.10.2") && !C.newer("0.9", "0.9") && !C.newer("0.9-beta", "0.9") && !C.newer("v1.0", "1.0"), "versions: not newer")

    // with the Hub: a newer Felucca, Felucca's kind and SHA-256 when the package is the same, Chordism added
    val hub = C.parseHub("""{"firmwares": [
        {"id": "felucca", "version": "9.9.9", "pkg": "fw/felucca-9.9.9.fwsc", "sha256": "ABC", "size": 1000, "kind": "synth"},
        {"id": "sloop", "version": "${base.entries.first { it.id == "sloop" }.version}", "pkg": "${base.entries.first { it.id == "sloop" }.pkg!!.removePrefix(C.HUB)}", "sha256": "def", "size": 2000, "kind": "groovebox"}]}""")
    val all = C.merge(base, hub)
    val f2 = all.entries.first { it.id == "felucca" }
    check(f2.version == "9.9.9" && f2.pkg == C.HUB + "fw/felucca-9.9.9.fwsc" && f2.sha256 == "abc" && f2.minMhz == fel.minMhz, "merge: the Hub's newer version, its package and SHA-256, the clock kept")
    val s2 = all.entries.first { it.id == "sloop" }
    check(s2.sha256 == "def" && s2.kind == "groovebox" && s2.size == 2000L, "merge: the same package: the Hub's SHA-256, size and kind")
    check(all.entries.first().id == "chordism" && all.entries.size == n + 1, "merge: Chordism added, first (the default)")
    check(C.kinds(all) == listOf("synth", "groovebox") || C.kinds(all).toSet() == setOf("synth", "groovebox"), "kinds for the filters: ${C.kinds(all)}")
    check(C.search(all, "acid groovebox").isNotEmpty() && C.search(all, "zzzz").isEmpty() && C.search(all, "", "groovebox").map { it.id } == listOf("sloop"), "search by words and by kind")
    check(C.merge(base, null).entries.size == n + 1, "merge without the Hub's list")

    // the store: the shipped list until a refresh, a day's wait, a refresh that half fails
    val dir = File(System.getProperty("java.io.tmpdir"), "fm1mix-store-${ProcessHandle.current().pid()}").apply { deleteRecursively(); mkdirs() }
    val store = FirmwareStore(dir) { listText }
    check(store.catalog().entries.size == n + 1 && store.updatedAt() == 0L, "store: the shipped list at first")
    val msg = store.refresh(false, { u -> if (u == C.FM1EMU_URL) listText.replace("\"Felucca\"", "\"Felucca X\"") else throw java.io.IOException("offline") }, now = 10 * FirmwareStore.DAY_MS)
    check(msg.startsWith("Liste à jour en partie") && store.catalog().entries.any { it.name == "Felucca X" }, "refresh: fm1-emu's list taken, the Hub's missing: $msg")
    check(store.refresh(false, { throw IllegalStateException("must not be called") }, now = 10 * FirmwareStore.DAY_MS + 1000) == "", "refresh: not again within a day")
    val bad = store.refresh(true, { "not json" }, now = 11 * FirmwareStore.DAY_MS)
    check(bad.startsWith("Pas de réseau") && store.catalog().entries.any { it.name == "Felucca X" }, "refresh: a bad list does not replace the good one")

    // downloads from a local file: checked, kept, replaced by a newer version, deleted
    val sha = FirmwareStore.sha256(pkg)
    val e = C.EXTRAS.first().copy(pkg = pkg.toURI().toString(), sha256 = sha)
    var seen = 0L
    val inst = store.download(e, { k, _ -> seen = k })
    check(inst.file.exists() && inst.file.length() == pkg.length() && seen == pkg.length() && store.isCurrent(e), "download: ${inst.file.name}, ${seen} B, checked")
    val wrong = e.copy(sha256 = "0".repeat(64), version = "1.0")
    check(runCatching { store.download(wrong) }.exceptionOrNull()?.message?.contains("SHA-256") == true && store.installed(e.id)!!.version == e.version, "download: a wrong SHA-256 refused, the installed one kept")
    val v2 = store.download(e.copy(version = "1.0", sha256 = null))
    check(v2.version == "1.0" && !inst.file.exists() && store.installed().size == 1, "download: a newer version replaces the older file")
    check(runCatching { store.download(e.copy(pkg = null)) }.isFailure, "download: nothing to fetch for a bring-your-own")
    store.delete(e.id)
    check(store.installed().isEmpty() && !v2.file.exists(), "delete")

    println(if (failures == 0) "ALL OK" else "$failures FAILURES")
    if (failures != 0) System.exit(1)
}
