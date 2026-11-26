// SPDX-License-Identifier: GPL-3.0-only
// The flasher's protocol (app/src/main/java/app/fm1mix/Fm1Ota.kt) against felucca-bsp's real update loader
// (loader/ldr_core.c, chip/ota.c) built for this computer (bsp/tests/ldr_pipe.c: SysEx on stdin and stdout, a file
// as the FM-1's flash), as fm1-base's tests/installer_fast_test.mjs does for the web installer: the fast install
// (the table, only the changed sectors, the bar never back), the same package again (nothing sent), the classic
// install, a loader from before the fast install, a power cut and the install resumed, a damaged package refused before
// anything is erased, the vectors the other hosts share; and real packages read (identity, image, checks).
//   tools/ota_check/run.sh BSP_DIR [PACKAGE.fwsc ...]
import app.fm1mix.*
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

var fails = 0
fun check(ok: Boolean, msg: String) { println(msg.padEnd(84) + if (ok) " ok" else " FAIL"); if (!ok) fails++ }
fun hex(a: ByteArray) = a.joinToString("") { "%02x".format(it) }

const val APP_LO = 0x4000
const val APP_HI = 0x93000
const val FL_OFF = 0x400
const val CHUNK = 4096
lateinit var bsp: File
val tmp: File = kotlin.io.path.createTempDirectory("fm1-ota").toFile()

fun build(name: String, fast: Boolean): File {
    val exe = File(tmp, name)
    val cmd = mutableListOf("cc", "-std=c11", "-D_DEFAULT_SOURCE", "-O1", "-w", "-I${bsp}/chip")
    if (fast) cmd += listOf("-DLDR_FAST_CHUNK=4096u", "-DOTA_FRAME_MAX=4200u")
    cmd += listOf("$bsp/tests/ldr_pipe.c", "-o", exe.path)
    check(ProcessBuilder(cmd).inheritIO().start().waitFor() == 0) { "cc failed" }
    return exe
}

class Dev(val flash: File, val image: ByteArray)
fun device(exe: File, name: String): Dev {
    val flash = File(tmp, "$name.nor"); val img = File(tmp, "$name.img")
    check(ProcessBuilder(exe.path, "init", flash.path, img.path).start().waitFor() == 0)
    return Dev(flash, img.readBytes())
}
fun app(flash: File) = flash.readBytes().copyOfRange(APP_LO, APP_HI)
fun done(flash: File, image: ByteArray): Boolean {
    val nor = flash.readBytes()
    return nor.copyOfRange(APP_LO, APP_HI).contentEquals(image.copyOfRange(FL_OFF + APP_LO, FL_OFF + APP_HI)) &&
        nor.copyOfRange(0xE4000, 0xE5000).all { it == 0xFF.toByte() }
}
fun changed(cur: ByteArray, image: ByteArray): Int {
    var k = 0
    var a = 0
    while (a < APP_HI - APP_LO) {
        if (!cur.copyOfRange(a, a + CHUNK).contentEquals(image.copyOfRange(FL_OFF + APP_LO + a, FL_OFF + APP_LO + a + CHUNK))) k++
        a += CHUNK
    }
    return k
}

/** the loader on a pipe: one FM-1 in update mode; [cutAfter] sectors written, it loses its power */
class Loader(exe: File, flash: File, val cutAfter: Int? = null) : SysexLink {
    val proc: Process = ProcessBuilder(exe.path, "run", flash.path).also { it.environment()["LDR_PIPE_TRY_MS"] = "3000" }.start()
    val reqs = java.util.Collections.synchronizedList(ArrayList<Pair<Long, Int>>())
    val checks = java.util.Collections.synchronizedList(ArrayList<Pair<Int, Int>>())
    @Volatile var hellos = 0
    val err = StringBuffer()
    private val q = LinkedBlockingQueue<ByteArray>()
    init {
        Thread {
            val inp = proc.inputStream
            val buf = java.io.ByteArrayOutputStream()
            while (true) {
                val x = try { inp.read() } catch (e: java.io.IOException) { -1 }      // (the power cut: the pipe closed)
                if (x < 0) break
                if (x == 0xF0) buf.reset()
                buf.write(x)
                if (x == 0xF7) { got(buf.toByteArray()); buf.reset() }
            }
        }.apply { isDaemon = true }.start()
        Thread { err.append(proc.errorStream.bufferedReader().readText()) }.apply { isDaemon = true }.start()
    }
    fun sectors() = synchronized(reqs) { reqs.filter { it.second == CHUNK && it.first < 0xD0000000L }.map { it.first } }
    private fun got(pkt: ByteArray) {
        if (pkt[0] != 0xF0.toByte()) return
        val r = Fm1Ota.parseRequest(pkt); val f = Fm1Ota.parseFrame(pkt)
        if (r != null) {
            reqs += r.addr to r.len
            if (cutAfter != null && sectors().size > cutAfter) { proc.destroyForcibly(); return }
        } else if (f != null && f.cmd == 0x32) checks += ((f.body[0].toInt() and 0xFF) or ((f.body[1].toInt() and 0xFF) shl 8)) to ((f.body[2].toInt() and 0xFF) or ((f.body[3].toInt() and 0xFF) shl 8))
        else if (f != null && f.cmd == 0x31) hellos++
        q.put(pkt)
    }
    override fun send(b: ByteArray): Boolean = try { proc.outputStream.write(b); proc.outputStream.flush(); true } catch (e: Exception) { false }
    override fun read(ms: Long): ByteArray? = q.poll(maxOf(ms, 0), TimeUnit.MILLISECONDS)
    override fun drain() = q.clear()
    override val lost get() = !proc.isAlive
    override fun close() {}
    fun waitExit(): Int? = if (proc.waitFor(5, TimeUnit.SECONDS)) proc.exitValue() else { proc.destroyForcibly(); null }
}

/** the ports: this one loader */
fun ports(l: Loader) = Fm1OtaPorts { filter -> Fm1Ota.handshake(l, 2)?.takeIf(filter)?.let { OtaDevice(l, it, "Felucca Update") } }

class Result(val ok: Boolean, val code: String?, val steps: List<List<Any?>>) {
    val bar get() = steps.filter { it[0] == "write" && it[3] != null }.map { it[3] as Int }
}
fun write(l: Loader, image: ByteArray, classic: Boolean = false): Result {
    val up = Fm1Updater(ports(l)).also { it.classic = classic; it.pause = 0 }
    val steps = java.util.Collections.synchronizedList(ArrayList<List<Any?>>())
    var ok = false; var code: String? = null
    try { ok = up.resume(image, { k, a, b, pct, t -> steps += listOf(k, a, b, pct, t) }) } catch (e: OtaError) { code = e.code } catch (e: Exception) { code = e.toString() }
    return Result(ok, code, steps)
}

fun main(args: Array<String>) {
    bsp = File(args[0])
    val fastExe = build("ldr_fast", true); val oldExe = build("ldr_old", false)
    val nApp = (APP_HI - APP_LO) / CHUNK
    run {
        val d = device(fastExe, "fast")
        val want = changed(app(d.flash), d.image)
        val l = Loader(fastExe, d.flash)
        val r = write(l, d.image)
        val ex = l.waitExit()
        check(r.ok && ex == 0 && done(d.flash, d.image) && l.hellos == 1 && l.sectors().size == want &&
            l.reqs.any { it.first >= 0xD0000000L && it.first < 0xE0000000L } && r.steps.any { it[0] == "mode" && it[4] == "fast" },
            "fast install: the table, then only the $want changed sectors of $nApp, 4 KiB each")
        val bar = r.bar
        check(l.checks.isNotEmpty() && l.checks.last().first == nApp && bar.isNotEmpty() && bar.zipWithNext().all { (a, b) -> b > a } && bar.last() <= 99,
            "fast install: the loader's 0x32 frames (${l.checks.size}), the bar from them, never back")
        check(ProcessBuilder(fastExe.path, "rearm", d.flash.path).start().waitFor() == 0)
        val l2 = Loader(fastExe, d.flash)
        val r2 = write(l2, d.image)
        check(r2.ok && l2.waitExit() == 0 && done(d.flash, d.image) && l2.sectors().isEmpty(), "the same package again: the table only, no sector sent")
    }
    run {
        val d = device(fastExe, "classic")
        val l = Loader(fastExe, d.flash)
        val r = write(l, d.image, classic = true)
        check(r.ok && l.waitExit() == 0 && done(d.flash, d.image) && l.hellos == 0 &&
            l.reqs.filter { it.first < 0xE0000000L }.maxOf { it.second } <= 512 && r.steps.any { it[0] == "write" && it[3] == null && (it[2] as Long) > 0 },
            "classic install, new loader (${l.reqs.size} reads of 512 B at most)")
    }
    run {
        val d = device(oldExe, "old")
        val l = Loader(oldExe, d.flash)
        val r = write(l, d.image)
        check(r.ok && l.waitExit() == 0 && done(d.flash, d.image) && l.hellos == 0 && r.steps.any { it[0] == "mode" && it[4] == "classic" },
            "a loader from before the fast install: no answer, the classic install")
    }
    run {
        val d = device(fastExe, "noloader")
        val plain = d.image.copyOf(); java.util.Arrays.fill(plain, 0x200, 0x210, 0)
        val l = Loader(fastExe, d.flash)
        val r = write(l, plain)
        check(r.ok && l.waitExit() == 0 && l.hellos == 0 && l.reqs.none { it.first >= 0xD0000000L && it.first < 0xE0000000L },
            "a package without a Felucca loader (M-VAVE's): no hello, the classic install")
    }
    run {
        val d = device(fastExe, "cut")
        val all = changed(app(d.flash), d.image)
        val l = Loader(fastExe, d.flash, cutAfter = 30)
        val r = write(l, d.image)
        l.waitExit()
        val rest = changed(app(d.flash), d.image)
        check(!r.ok && !done(d.flash, d.image) && rest < all, "power cut after 30 sectors: stopped (${r.code ?: "not finished"}), $rest of $all left")
        val l2 = Loader(fastExe, d.flash)
        val r2 = write(l2, d.image)
        check(r2.ok && l2.waitExit() == 0 && done(d.flash, d.image) && l2.sectors().size == rest, "... resumed: only the $rest sectors still different are sent")
    }
    run {
        val d = device(fastExe, "damaged")
        val before = d.flash.readBytes()
        val bad = d.image.copyOf(); bad[FL_OFF + 0x7005] = (bad[FL_OFF + 0x7005].toInt() xor 0x10).toByte()
        val l = Loader(fastExe, d.flash)
        val t0 = System.currentTimeMillis()
        val th = Thread { write(l, bad) }.apply { isDaemon = true; start() }
        Thread.sleep(3000); l.proc.destroyForcibly(); th.join(10000)
        Thread.sleep(200)
        check(Regex("session -12").containsMatchIn(l.err) && d.flash.readBytes().contentEquals(before) && System.currentTimeMillis() - t0 < 10000,
            "a damaged package: the table refused (-12), nothing erased or written")
    }
    check(hex(Fm1Ota.FAST_HELLO) == "f0003245190000400000203807f7", "the hello: the vector shared with the other hosts")
    run {
        val d = ByteArray(8192) { (((it * 31) xor (it shr 8)) and 0xFF).toByte() }
        check(hex(Fm1Ota.sectorTable(d, 0, 2)) == "6204a25f925f5cc59b283583", "the table: the vector shared with the other hosts")
    }
    // real packages: identity, image, checks
    for (p in args.drop(1)) {
        val f = File(p).readBytes()
        val product = Fm1Pkg.productOf(f)
        val img = Fm1Pkg.logicalImage(f)
        val ok = runCatching { Fm1Pkg.checkUfw(img) }.isSuccess
        check(ok && product.isNotEmpty(), "${File(p).name}: $product, ${img.size} bytes, checks pass, Felucca loader: ${Fm1Ota.hasLoader(img)}")
        val bad = img.copyOf(); bad[0x30] = (bad[0x30].toInt() xor 1).toByte()
        check(runCatching { Fm1Pkg.checkUfw(bad) }.isFailure, "${File(p).name}: one byte changed in its header: refused")
    }
    tmp.deleteRecursively()
    println(if (fails > 0) "OTA TESTS FAILED ($fails)" else "ota tests passed")
    kotlin.system.exitProcess(if (fails > 0) 1 else 0)
}
