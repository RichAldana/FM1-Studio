// kotlinc ../../app/src/main/java/app/fm1mix/PanelLayout.kt check.kt -include-runtime -d check.jar && java -jar check.jar
import app.fm1mix.PanelLayout as L

var failures = 0
fun check(ok: Boolean, msg: String) { if (!ok) { failures++; println("FAIL: $msg") } }

fun main(args: Array<String>) {
    // view sizes (pixels) a phone or tablet gives the panel once the top bar and system bars are taken away
    val sizes = arrayOf(
        floatArrayOf(1080f, 2050f), floatArrayOf(1080f, 2200f), floatArrayOf(1080f, 1700f), floatArrayOf(1080f, 1400f),
        floatArrayOf(720f, 1250f), floatArrayOf(1440f, 2900f), floatArrayOf(1600f, 2300f), floatArrayOf(2560f, 1400f)
    )
    for (sz in sizes) {
        val w = sz[0]; val h = sz[1]
        val l = L.build(w, h)
        val tag = "${w.toInt()}x${h.toInt()}"
        check(l.keys.size == 27, "$tag: 27 keys")
        check(l.keys.map { it.id }.toSet() == (14..40).toSet(), "$tag: key ids 14..40")
        check(l.buttons.map { it.id }.toSet() == (0..13).toSet(), "$tag: button ids 0..13")
        check(l.encs.map { it.id }.toSet() == (0..6).toSet(), "$tag: encoder ids 0..6")
        check(l.ox >= -0.01f && l.ox + L.REF_W * l.scale <= w + 0.01f, "$tag: fits the width")
        check(l.oy >= -0.01f && l.oy + l.usedHeight * l.scale <= h + 0.01f, "$tag: fits the height")
        for (i in l.buttons.indices) {
            val b = l.buttons[i]
            val hit = l.hit(b.x + b.w / 2f, b.y + b.h / 2f)
            check(hit != null && hit.kind == L.HIT_BTN && hit.index == i, "$tag: button ${b.name}")
        }
        for (i in l.encs.indices) {
            val e = l.encs[i]
            val hit = l.hit(e.cx, e.cy)
            check(hit != null && hit.kind == L.HIT_ENC && hit.index == i, "$tag: encoder ${e.name}")
        }
        val ph = l.hit(l.pot.cx, l.pot.cy)
        check(ph != null && ph.kind == L.HIT_POT, "$tag: MASTER")
        check(l.hit(l.lcdX + 290f, l.lcdY + 290f) == null, "$tag: the screen is not a control")
        for (k in l.keys) {
            val y = if (k.black) k.y + k.h / 2f else k.y + k.h * 0.9f
            val hit = l.hit(k.x + k.w / 2f, y)
            check(hit != null && hit.kind == L.HIT_KEY && l.keys[hit.index].id == k.id, "$tag: key ${k.id}")
            check(k.x >= 0f && k.x + k.w <= L.REF_W, "$tag: key ${k.id} inside the width")
        }
        // notes rise left to right on each tier, and the top tier is the higher octave
        val top = l.keys.filter { it.y < L.TOP_H + 100f }.sortedBy { it.x + it.w / 2f }.map { it.id }
        val bot = l.keys.filter { it.y >= L.TOP_H + 100f }.sortedBy { it.x + it.w / 2f }.map { it.id }
        check(top == (26..40).toList(), "$tag: top tier F4..G5 in order, got $top")
        check(bot == (14..25).toList(), "$tag: bottom tier F3..E4 in order, got $bot")
        val lastBtn = l.buttons.maxBy { it.y + it.h }!!
        check(lastBtn.y + lastBtn.h < L.TOP_H, "$tag: buttons end above the keys")
        check(l.bezelY + l.bezelSize < 745f - 58f, "$tag: the screen ends above the knobs")
        val k0 = l.keys.first { it.id == 14 }
        check(L.keyVelocity(k0, k0.y - 50f) == 1 && L.keyVelocity(k0, k0.y + k0.h + 50f) == 127, "$tag: velocity ends")
        val wk = l.keys.first { !it.black }
        println("$tag: scale %.3f, white key %.0f x %.0f px, panel %.0f px high".format(l.scale, wk.w * l.scale, wk.h * l.scale, l.usedHeight * l.scale))
    }
    println(if (failures == 0) "ALL OK" else "$failures FAILURES")
}
