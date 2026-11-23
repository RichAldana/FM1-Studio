// kotlinc ../../app/src/main/java/app/fm1mix/CaptureFormat.kt check.kt -include-runtime -d check.jar && java -jar check.jar
import app.fm1mix.CaptureFormat as C

var failures = 0
fun check(ok: Boolean, msg: String) { if (!ok) { failures++; println("FAIL: $msg") } }

fun main(args: Array<String>) {
    // 10 channels: the master on 1-2 (values 1000+f, -1000-f), tracks 3..10 (value c*10)
    val ch = 10
    val frames = 37
    val src = ShortArray(frames * ch + 3)          // + a partial frame
    for (f in 0 until frames) for (c in 0 until ch)
        src[f * ch + c] = when (c) { 0 -> (1000 + f); 1 -> (-1000 - f); else -> c * 10 }.toShort()
    val dst = ShortArray(512)
    val n = C.firstTwo(src, src.size, ch, dst)
    check(n == frames * 2, "10 ch: ${frames * 2} samples, got $n")
    var ok = true
    for (f in 0 until frames) if (dst[2 * f] != (1000 + f).toShort() || dst[2 * f + 1] != (-1000 - f).toShort()) ok = false
    check(ok, "10 ch: channels 1 and 2 kept, in order")

    // 2 channels: a copy, an odd count drops the last half frame
    val s2 = ShortArray(9) { (it + 1).toShort() }
    val d2 = ShortArray(16)
    check(C.firstTwo(s2, 9, 2, d2) == 8 && d2[7] == 8.toShort(), "2 ch copy")

    // the destination is the limit
    val small = ShortArray(6)
    check(C.firstTwo(src, src.size, ch, small) == 6 && small[4] == 1002.toShort(), "a small destination")

    // what is tried
    val t10 = C.tries(intArrayOf(10))
    check(t10.map { it.toString() } == listOf("2 canaux (index)", "2 canaux (stéréo)", "10 canaux (index)"), "tries for 10: $t10")
    val t8 = C.tries(intArrayOf(2, 8, 4))
    check(t8.map { it.channels } == listOf(2, 2, 8, 4), "tries for 2,8,4: ${t8.map { it.channels }}")
    check(C.tries(IntArray(0)).size == 2, "unknown device: the two stereo tries")
    check(C.indexMask(2) == 3 && C.indexMask(10) == 1023, "index masks")

    println(if (failures == 0) "ALL OK" else "$failures FAILURES")
}
