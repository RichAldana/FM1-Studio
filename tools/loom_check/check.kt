// FM1 Mix's LoomSamples.kt against LOOM-8's loom_samples.js (gen.mjs wrote its results): byte for byte.
//   node gen.mjs /path/to/fm1-loom REF && kotlinc ../../app/src/main/java/app/fm1mix/LoomSamples.kt check.kt \
//     -include-runtime -d check.jar && java -jar check.jar REF
import app.fm1mix.LoomSamples as L
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

var failures = 0
fun check(ok: Boolean, msg: String) { println((if (ok) "  ok   " else "  FAIL ") + msg); if (!ok) failures++ }

fun s16(f: File): ShortArray { val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN); return ShortArray(b.remaining() / 2) { b.short } }
fun f64(f: File): DoubleArray { val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN); return DoubleArray(b.remaining() / 8) { b.double } }
fun firstDiff(a: ByteArray, b: ByteArray): Int { for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return i; return if (a.size == b.size) -1 else minOf(a.size, b.size) }

fun main(args: Array<String>) {
    val ref = File(args[0])
    val meta = File(ref, "meta.txt").readLines().filter { it.isNotBlank() }.map { it.split("|") }

    // A. int16 samples -> the same bank image; built again from its own read-back -> the same
    val specs = meta.filter { it[0] == "A" }.map { m ->
        val k = m[1].toInt()
        L.Item(m[2], m[3].toInt(), if (m[4].toInt() >= 0) intArrayOf(m[4].toInt(), m[5].toInt()) else null, m[6].toInt(), m[7].toInt(),
            s = s16(File(ref, "A$k.s16")))
    }
    val a = L.userBank(specs)
    val aRef = File(ref, "A.img").readBytes()
    check(a.contentEquals(aRef), "A: 11 samples -> the bank image, ${a.size} B (first difference: ${firstDiff(a, aRef)})")
    val a2 = L.userBank(L.keptOf(a))
    check(a2.contentEquals(File(ref, "A2.img").readBytes()), "A: read back and built again -> the same image")
    check(L.parseHeader(a)!!.entries.map { it.name } == specs.map { L.cleanName(it.name) }, "A: the header's names (cleaned: ${L.cleanName("Test_0!")})")

    // B. a sound at its rate -> each bank rate: the same int16 samples, the same loop points
    var bOk = 0
    var bAll = 0
    var worst = 0
    for (m in meta.filter { it[0] == "B" }) {
        val k = m[1].toInt()
        val sr = m[2].toInt()
        val x = f64(File(ref, "B$k.f64"))
        for (r in L.RATES) {
            val got = L.atRate(x, sr, intArrayOf(100, x.size - 50), r)
            val want = s16(File(ref, "B${k}_$r.s16"))
            val lp = meta.first { it[0] == "BL" && it[1].toInt() == k && it[2].toInt() == r }
            bAll++
            var d = if (got.s.size == want.size) 0 else 99999
            if (d == 0) for (i in want.indices) d = maxOf(d, Math.abs(got.s[i] - want[i]))
            worst = maxOf(worst, d)
            if (d == 0 && got.loop!![0] == lp[3].toInt() && got.loop!![1] == lp[4].toInt()) bOk++
        }
    }
    check(bOk == bAll, "B: 3 sounds x 4 rates resampled, emphasised, to int16: $bOk of $bAll identical (largest difference $worst)")

    // C. kept samples + new sounds, the rate chosen by build -> the same image, the same rate
    val kept = L.keptOf(aRef).take(6)
    val news = meta.filter { it[0] == "B" }.map { m ->
        val k = m[1].toInt()
        L.Item("new $k", if (k != 0) 60 else L.LOOP_ROOT, null, if (k != 0) 0 else 2, null, x = f64(File(ref, "B$k.f64")), sr = m[2].toInt())
    }
    val c = L.build(kept + news)
    val cRef = File(ref, "C.img").readBytes()
    val chose = meta.first { it[0] == "C" }[1].toInt()
    check(c.chose == chose && c.img.contentEquals(cRef), "C: 6 kept + 3 new -> the same bank at ${c.chose} Hz (reference ${chose} Hz; first difference ${firstDiff(c.img, cRef)})")

    // D. the upload's frames
    val syx = java.io.ByteArrayOutputStream()
    for (f in L.frames(c.img)) syx.write(L.sysex(f.cmd, f.args))
    val d = syx.toByteArray()
    val dRef = File(ref, "D.syx").readBytes()
    check(d.contentEquals(dRef), "D: the upload's SysEx frames, ${d.size} B (first difference ${firstDiff(d, dRef)})")
    File(ref, "D_kotlin.syx").writeBytes(d)
    File(ref, "C_kotlin.bin").writeBytes(c.img)

    // a few more: pack7 round trip, ADPCM round trip close to the source, TooLong
    val raw = ByteArray(300) { (it * 37).toByte() }
    check(L.unpack7(L.pack7(raw)).contentEquals(raw), "pack7 / unpack7 round trip")
    val sine = ShortArray(5000) { (10000 * Math.sin(it * 0.05)).toInt().toShort() }
    val dec = L.imaDecode(L.imaEncode(sine).adpcm, sine.size)
    val err = sine.indices.drop(200).maxOf { Math.abs(sine[it] - dec[it]) }
    check(err < 600, "ADPCM: encoded and decoded back within $err of the source")
    var tooLong = false
    try { L.build(List(3) { L.Item("big $it", 60, null, 0, 32000, s = ShortArray(160000)) }) } catch (e: L.TooLong) { tooLong = true }
    check(tooLong, "a bank that cannot hold its samples: TooLong")

    println(if (failures == 0) "ALL OK" else "$failures FAILURES")
    if (failures != 0) System.exit(1)
}
