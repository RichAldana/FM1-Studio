// SPDX-License-Identifier: GPL-3.0-only
// The firmware manager's backup and restore (Fm1Backup.kt) against an emulated FM-1 (the app's own engine,
// libfm1mix.so built for this computer) running a real firmware with backup = true (LOOM-8), over the emulated USB-MIDI
// (Native.emuUsb*, EmuUsbLink): the identity, a backup checked whole, its sample bank (LSMP), a restore answered yes on
// the FM-1's screen (SEL), one answered no (HOME), a damaged file refused before it is sent.
//   tools/ota_check/run_emu.sh LOOM-8.fwsc
import app.fm1mix.*
import java.io.File

var bad = 0
fun ok(c: Boolean, msg: String) { println(msg.padEnd(84) + if (c) " ok" else " FAIL"); if (!c) bad++ }

const val SEL = 3
const val HOME = 0

fun press(id: Int) { Native.key(id, true); Thread.sleep(150); Native.key(id, false) }

fun main(args: Array<String>) {
    Native.loadError?.let { println("libfm1mix: $it"); kotlin.system.exitProcess(2) }
    val pkg = File(args[0]).readBytes()
    // the emulator runs as fast as its sound is read: a reader as the phone's audio
    val audio = Thread {
        val buf = ShortArray(882)
        while (!Thread.currentThread().isInterrupted) {
            Native.readMixed(buf, 1f, 0f, 0f)
            try { Thread.sleep(10) } catch (e: InterruptedException) { break }
        }
    }.apply { isDaemon = true; start() }

    Native.setEmuUsb(true)
    val err = Native.start(pkg, null, 240, false)
    ok(err.isEmpty(), "the emulator starts ($err)")
    var t = System.currentTimeMillis()
    while (Native.emuUsbProduct().isEmpty() && System.currentTimeMillis() - t < 30000) Thread.sleep(50)
    println("  status: ${Native.status()} load ${Native.load()}")
    ok(Native.emuUsbProduct().isNotEmpty(), "its USB enumerates: \"${Native.emuUsbProduct()}\" in ${System.currentTimeMillis() - t} ms")
    Thread.sleep(1500)   // the firmware's own start

    val link = EmuUsbLink()
    val id = Fm1Ota.handshake(link)
    ok(id != null && id.model.isNotEmpty(), "the identity: ${id?.text}")
    val kind = id?.let { Fm1Backup.kindOf(it.text) }
    ok(kind?.magic == "LM8B", "a firmware the app backs up: ${kind?.name}")
    if (kind == null) { Native.stop(); kotlin.system.exitProcess(1) }

    t = System.currentTimeMillis()
    var last = 0
    val img = runCatching { Fm1Backup.backup(link, kind.magic) { last = it } }.onFailure { println("  $it") }.getOrNull()
    ok(img != null && img.size == last, "a backup: ${img?.size} bytes in ${System.currentTimeMillis() - t} ms")
    if (img == null) { Native.stop(); kotlin.system.exitProcess(1) }
    val p = Fm1Backup.parse(img)
    ok(p.magic == "LM8B" && p.objs.isNotEmpty(), "checked whole: version ${p.version}, ${p.objs.size} parts, ids ${p.objs.map { it.first }.take(12)}…")
    ok(!Fm1Backup.playing(img), "not taken while playing")

    // the sample bank: LOOM-8 starts with none, so a small one is written, read back and carried in the file
    val bank = LoomBank(link)
    val before = runCatching { bank.read() }.onFailure { println("  $it") }
    ok(before.isSuccess, "its sample bank read (${before.getOrNull()?.size ?: "none"})")
    val pcm = ShortArray(4410) { (kotlin.math.sin(it * 0.0627) * 12000).toInt().toShort() }
    val built = runCatching { LoomSamples.build(listOf(LoomSamples.Item("test", 60, null, 0, 22050, s = pcm))).img }.getOrNull()
    var lsmp: ByteArray? = null
    if (built != null) {
        runCatching { bank.write(built) }.onFailure { println("  $it") }
        lsmp = runCatching { bank.read() }.onFailure { println("  $it") }.getOrNull()
        // (LOOM-8 keeps its unused entries erased, 0xFF, where the app writes zeros: the same bank)
        val hb = built.let { LoomSamples.parseHeader(it.copyOfRange(0, LoomSamples.BANK_HDR)) }
        val hr = lsmp?.let { LoomSamples.parseHeader(it.copyOfRange(0, LoomSamples.BANK_HDR)) }
        val used = LoomSamples.ENT0 + (hb?.count ?: 0) * LoomSamples.ENT
        ok(lsmp != null && hr != null && hb != null && hr.count == hb.count && hr.dataLen == hb.dataLen && hr.crc == hb.crc &&
            lsmp.copyOfRange(0, used).contentEquals(built.copyOfRange(0, used)) &&
            lsmp.copyOfRange(LoomSamples.BANK_HDR, lsmp.size).contentEquals(built.copyOfRange(LoomSamples.BANK_HDR, built.size)),
            "a bank written then read back the same: ${hr?.count} sample, ${lsmp?.size} bytes")
    } else println("  (LoomSamples.build not usable here: the bank skipped)")
    val file = Fm1Backup.join(img, listOfNotNull(lsmp?.let { "LSMP" to it }))
    val (back, secs) = Fm1Backup.split(file)
    ok(back.contentEquals(img) && secs.size == (if (lsmp != null) 1 else 0) && (lsmp == null || secs[0].second.contentEquals(lsmp)),
        "the file split back: the backup and ${secs.size} section(s)")

    // a damaged file is refused before anything is sent
    val broken = img.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
    ok(runCatching { Fm1Backup.restore(link, broken) }.isFailure, "a damaged backup refused")

    // something changed on the FM-1 (knobs turned), so that the restore has something to put back
    for (e in 0..3) Native.enc(e, 9)
    Thread.sleep(6000)
    val changedImg = runCatching { Fm1Backup.backup(link, kind.magic) }.getOrNull()
    val moved = changedImg != null && !changedImg.contentEquals(img)
    println("  after turning the knobs, the FM-1's backup ${if (moved) "changed" else "did not change"}")

    // a restore answered no on the FM-1
    var asked = false
    var rc = runCatching { Fm1Backup.restore(link, img, onAsk = { asked = true; Thread { Thread.sleep(400); press(HOME) }.start() }) }
    ok(asked && rc.getOrNull() == Fm1Backup.DECLINED, "a restore answered no on the FM-1: ${rc.getOrNull()?.let { Fm1Backup.error(it) } ?: rc.exceptionOrNull()}")
    Thread.sleep(800)

    // and yes
    asked = false
    var pct = 0
    t = System.currentTimeMillis()
    rc = runCatching { Fm1Backup.restore(link, img, onAsk = { asked = true; Thread { Thread.sleep(400); press(SEL) }.start() }, onPct = { pct = it }) }
    ok(asked && rc.getOrNull() == 0, "a restore answered yes: ${rc.getOrNull()?.let { if (it == 0) "restored" else Fm1Backup.error(it) } ?: rc.exceptionOrNull()} in ${System.currentTimeMillis() - t} ms ($pct %)")

    // what it saved, read again: the same backup (no part changed)
    Thread.sleep(1500)
    while (Native.emuUsbProduct().isEmpty() && System.currentTimeMillis() - t < 60000) Thread.sleep(50)
    Thread.sleep(1500)
    val again = runCatching { Fm1Backup.backup(link, kind.magic) }.onFailure { println("  $it") }.getOrNull()
    ok(again != null && Fm1Backup.parse(again).objs.map { it.first to it.second.toList() } == p.objs.map { it.first to it.second.toList() },
        "backed up again after the restore: the parts of the first backup" + if (moved) " (not the changed ones)" else "")

    // the resource region (LOOM-8's NAME-res.bin), written as after an install (Fm1Updater.installRes)
    if (args.size > 1) {
        val ports = Fm1OtaPorts { filter ->
            if (Native.emuUsbProduct().isEmpty()) null
            else { val l = EmuUsbLink(); Fm1Ota.handshake(l)?.takeIf(filter)?.let { OtaDevice(l, it, "emu") } }
        }
        val res = File(args[1]).readBytes()
        ok(runCatching { Fm1Ota.resPayload(res) }.isSuccess, "a resource file checked: ${res.size} bytes")
        val bad = res.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        ok(runCatching { Fm1Ota.resPayload(bad) }.isFailure, "a damaged resource file refused")
        var rp = 0
        t = System.currentTimeMillis()
        val rr = runCatching { Fm1Updater(ports).installRes(res, id!!.text) { rp = it } }
        ok(rr.getOrNull() == 0, "the resources written: ${rr.getOrNull() ?: rr.exceptionOrNull()} in ${System.currentTimeMillis() - t} ms ($rp %)")
    }

    Native.stop()
    audio.interrupt()
    println(if (bad == 0) "all ok" else "$bad FAILED")
    kotlin.system.exitProcess(if (bad == 0) 0 else 1)
}
