// FM1 Mix's Chop.kt (and SlicerModel, WavIO) against SLOOP's own CHOP code (gen.mjs) and SLOOP's own test claims.
//   node gen.mjs /path/to/sloop-fm1 REF && kotlinc ../../app/src/main/java/app/fm1mix/{Chop,SlicerModel,WavIO,LoomSamples}.kt \
//     check.kt -include-runtime -d check.jar && java -jar check.jar REF
import app.fm1mix.Chop
import app.fm1mix.SlicerModel
import app.fm1mix.WavIO
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

var failures = 0
fun check(ok: Boolean, msg: String) { println((if (ok) "  ok   " else "  FAIL ") + msg); if (!ok) failures++ }
fun f64(f: File): DoubleArray { val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN); return DoubleArray(b.remaining() / 8) { b.double } }
fun s16(f: File): ShortArray { val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN); return ShortArray(b.remaining() / 2) { b.short } }
fun ints(s: String): List<Int> = if (s.isEmpty()) emptyList() else s.split(",").map { it.toInt() }

fun main(args: Array<String>) {
    val ref = File(args[0])
    val lines = File(ref, "ref.txt").readLines().filter { it.isNotBlank() }.map { it.split("|") }
    val x = f64(File(ref, "x.f64"))
    val R = Chop.RATE

    // the same numbers as SLOOP's code
    val nov = Chop.novelty(x)
    val novRef = f64(File(ref, "nov.f64"))
    val lvRef = f64(File(ref, "level.f64"))
    check(nov.nov.contentEquals(novRef) && nov.level.contentEquals(lvRef), "novelty and levels: ${nov.size} frames, identical to SLOOP's")
    var hOk = 0
    for (l in lines.filter { it[0] == "hits" }) if (Chop.hits(x, nov, l[1].toInt()).toList() == ints(l[2])) hOk++
    check(hOk == 10, "hits at sensitivities 1..10: $hOk of 10 identical")
    var sOk = 0
    val snaps = lines.filter { it[0] == "snap" }
    for (l in snaps) if (Chop.snap(x, nov, l[1].toDouble()) == l[2].toInt()) sOk++
    check(sOk == snaps.size, "taps snapped: $sOk of ${snaps.size} identical")
    var gOk = 0
    val grids = lines.filter { it[0] == "grid" }
    for (l in grids) if (Chop.grid(1000, x.size, l[1].toDouble(), l[2].toDouble()).toList() == ints(l[3])) gOk++
    check(gOk == grids.size, "tempo grids: $gOk of ${grids.size} identical")
    var eOk = 0
    val eqs = lines.filter { it[0] == "equal" }
    for (l in eqs) if (Chop.equal(100, 90001, l[1].toInt()).toList() == ints(l[2])) eOk++
    check(eOk == eqs.size, "equal parts: $eOk of ${eqs.size} identical")
    val lst = Chop.list(intArrayOf(10, 50, 400), 1000, 100, listOf(null, Chop.Opt(off = true), Chop.Opt(len = 30)))
        .joinToString(",") { "${it.start}-${it.end}-${it.i}-${if (it.off) 1 else 0}-${it.full}" }
    check(lst == lines.first { it[0] == "list" }[1], "chops with options: $lst")
    val fitRef = lines.first { it[0] == "fit" }
    check(Chop.fit(listOf(Chop.Piece(0, 100, 0, false, 100), Chop.Piece(0, 300, 1, false, 300)), 250) == fitRef[1].toInt()
        && fitRef[2] == "Infinity" && Chop.fit(listOf(Chop.Piece(0, 100, 0, false, 100)), 250) == Int.MAX_VALUE, "fit (150, and Infinity when all fit)")
    val z = lines.first { it[0] == "zones" }
    val hits = Chop.hits(x, nov, 5)
    val chops = Chop.list(hits, x.size)
    val pk = Chop.peak(x, Chop.pick(chops))
    var zOk = 0
    for (k in 0 until z[2].toInt()) if (Chop.zoneSamples(x, chops[k], pk).contentEquals(s16(File(ref, "zone$k.s16")))) zOk++
    check(zOk == z[2].toInt(), "chops' samples (common level, fades): $zOk of ${z[2]} identical")

    // SLOOP's own claims (web/test_web.mjs chopTests)
    val HITS = ints(lines.first { it[0] == "HITS" }[1])
    fun near(a: Int, b: Int, ms: Int) = Math.abs(a - b) <= ms * R / 1000
    check(hits.size == 8 && hits.indices.all { near(hits[it], HITS[it] - Chop.PRE, 3) }, "SLOOP: 8 hits, each within 3 ms of its attack")
    val h1 = Chop.hits(x, nov, 1).size
    check(h1 < 8 && h1 >= 4, "SLOOP: low sensitivity keeps the hard hits only ($h1)")
    check(near(Chop.snap(x, nov, HITS[3] + 0.035 * R), HITS[3] - Chop.PRE, 3) && near(Chop.snap(x, nov, HITS[4] - 0.03 * R), HITS[4] - Chop.PRE, 3)
        && Chop.snap(x, nov, 1.1 * R) == Math.round(1.1 * R).toInt(), "SLOOP: a TAP 35 ms late / 30 ms early lands on its hit; away from hits it stays")

    // the app's side: a recording at 44.1 kHz stereo 24-bit, sliced, cut in its own format
    val sr = 44100
    val n = (x.size.toLong() * sr / R).toInt()
    val st = FloatArray(n * 2)
    for (f in 0 until n) { val p = f.toDouble() * R / sr; val i0 = p.toInt(); val t = p - i0; val v = (x[minOf(x.size - 1, i0)] * (1 - t) + x[minOf(x.size - 1, i0 + 1)] * t).toFloat(); st[2 * f] = v; st[2 * f + 1] = 0.5f * v }
    val wav = WavIO.write(st, 2, sr, 24)
    val snd = WavIO.read(wav)
    check(snd.rate == sr && snd.channels == 2 && snd.bits == 24 && Math.abs(snd.data[2001] - 0.5f * snd.data[2000]) < 1e-6, "WAV 24-bit stereo written and read back")
    val f32 = WavIO.read(WavIO.write(floatArrayOf(1.5f, -0.25f), 1, 48000, 32))
    check(f32.float && f32.data[0] == 1.5f && f32.rate == 48000, "WAV float keeps 1.5 (over full scale)")
    val m = SlicerModel(snd)
    m.detect(5)
    check(m.marks.size == 8 && m.marks.indices.all { near(m.marks[it], HITS[it] - Chop.PRE, 4) }, "slicer on the 44.1 kHz recording: the same 8 hits (within 4 ms)")
    m.tap(Math.round((HITS[3] + 0.035 * R) * sr / R.toDouble()))
    check(m.marks.size == 8, "a TAP near a marker moves it, it does not add one")
    m.toggleKeep(1)
    val kept = m.kept()
    check(kept.size == 7 && kept.none { it.i == 1 }, "a chop left out")
    val g = m.commonGain(kept)
    val r0 = m.render(kept[0], g)
    check(r0.size / 2 == m.toFile(kept[0].end) - m.toFile(kept[0].start) && r0[0] == 0f && r0[1] == 0f, "a chop cut at the file's rate, stereo, faded in from 0")
    check(r0.maxOf { Math.abs(it) } <= 30000f / 32768f + 1e-4f, "levels at SLOOP's 30000 at most")
    val out = WavIO.read(WavIO.write(r0, 2, sr, snd.bits))
    check(out.frames == r0.size / 2 && out.bits == 24, "a chop exported as a 24-bit WAV like its source")
    m.equal(4)
    check(m.marks.size == 4 && m.marks[0] == m.region()[0], "equal parts over the markers' region")
    m.clear(); m.grid(90.0, 1.0)
    check(m.marks.size >= 5 && Math.abs(m.marks[1] - m.marks[0] - Math.round(R * 60 / 90.0)) <= 1, "a 90 BPM grid, a beat apart")
    check(m.barsAt(120.0, 2.0) == 1 && m.barsAt(120.0, 8.0) == 4 && m.barsAt(60.0, 100.0) == 16, "bars from the tempo (1, 4, at most 16)")

    println(if (failures == 0) "ALL OK" else "$failures FAILURES")
    if (failures != 0) System.exit(1)
}
