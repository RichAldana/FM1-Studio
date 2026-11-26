// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Charles Vestal
// Copyright (C) 2026 Leo Kuroshita (@kurogedelic), Hügelton Instruments
// Copyright (C) 2026 Chris Farrell (finding the FM-1 under any port name, M-VAVE's Open firmware's identities, resume's
//   return check, checkUfw: from fm-static, github.com/seajaysec/fm-static, by his permission under MPL-2.0:
//   seajaysec/fm-static#1)
// Copyright (C) 2026 Kerem Kilic, Ellic Studio (the resource region after an install: Melodee's web/fm1res.js and its
//   installer step, used in fm1-base under MPL-2.0 with permission)
// Kotlin port for FM1 Mix of fm1-base's web/fm1ota.js and web/fm1pkg.js (github.com/charlesvestal/fm1-base,
// 2026-11-22), blocking: call it off the screen's thread. This file stays under MPL-2.0 (as LoomLink.kt).
//
// The FM-1 USB-MIDI update protocol (M-UPGRADE compatible). Step 1: the running firmware reads parts of the package and
// stages the update loader. Step 2: the loader reads the whole image and writes it. In both steps the device sends cmd
// 0x30 read requests on the logical image and we answer. Step 2 is the fast install when the loader can (felucca-bsp
// loader/ldr_core.c, LDR_FAST_CHUNK): before the upgrade command we say cmd 0x31 [1, 4096]; a loader that answers it
// within 0.5 s reads a table of flash.bin's 4 KiB sectors (CRC16, CRC-32) at 0xD0000000 + flash.bin's offset, checks it
// against the package before anything is erased, and asks only for the sectors that differ (a cut-off install resumes:
// what was written is skipped); it says how far it compared with cmd 0x32 [sectors compared, all]. A loader that says
// nothing gets the classic protocol; a package without a Felucca loader (M-VAVE's firmware) never hears the hello.
// No Android in here: the app gives it its MIDI ports (Fm1OtaPorts), the tests a pipe to felucca-bsp's real loader.
package app.fm1mix

import java.security.MessageDigest
import java.util.zip.CRC32

/** what a device answered the handshake: "FM-1_6010000", its model ("FM-1", "ota-FM-1"), its number */
class OtaIdentity(val text: String, val model: String, val version: Long, val open: Boolean = false) {
    val isLoader get() = model.startsWith("ota-", ignoreCase = true)
    override fun toString() = text
}

/** a failure, by code (the screens translate it): notfound, model, stopped, noloader, lost, noreturn, mismatch, badreq,
 *  foreign, open, bootrefused, resbad */
class OtaError(val code: String, msg: String, val detail: String = "") : Exception(msg)

/** one MIDI in/out pair to the device, SysEx only */
interface SysexLink {
    /** false when the port is gone */
    fun send(b: ByteArray): Boolean
    /** the next SysEx message, waiting up to [ms]; null when none came */
    fun read(ms: Long): ByteArray?
    fun drain()
    val lost: Boolean
    fun close()
}

/** a device that answered: its link (open), its identity, its port's name */
class OtaDevice(val link: SysexLink, var id: OtaIdentity, val name: String)

/** the MIDI ports to look among (Android's, a test's pipe) */
fun interface Fm1OtaPorts {
    /** every port pair that answers the handshake and passes [filter], the first one; its link left open */
    fun find(filter: (OtaIdentity) -> Boolean): OtaDevice?
}

/** a step of the work, for the screen: start, verify, loader, mode, write, reboot, done, bootstart, bootwrite, bootdone */
fun interface OtaStep {
    fun on(kind: String, served: Long, bytes: Long, pct: Int?, text: String?)
}

object Fm1Ota {
    val HS_QUERY = b(0xF0, 0x00, 0x32, 0x45, 0x00, 0x00, 0x00, 0x40, 0x7F, 0xF7)
    /** the handshake with the one-byte body 01: a firmware on felucca-bsp's chip/ota.c answers it with its whole identity */
    val HS_FULL = b(0xF0, 0x00, 0x32, 0x45, 0x08, 0x00, 0x00, 0x40, 0x00, 0x7E, 0x01, 0xF7)
    val UPGRADE = b(0xF0, 0x22, 0x24, 0x35, 0x7F, 0xF7)
    const val MAXDATA = 512
    const val FAST_CMD = 0x31
    const val CHECK_CMD = 0x32
    const val FAST_CHUNK = 4096
    const val TABLE = 0xD0000000L
    const val TABLE_END = 0xE0000000L
    const val FINISH_CHECK = 0xE0000000L
    const val FINISH_WRITE = 0xF0000000L
    private val LOADER_MARK = "FELUCCA-LOADER-1".toByteArray(Charsets.US_ASCII)
    private val OPEN_RE = Regex("^FM1_(OFFICIAL_OPEN|COMMUNITY_\\d+)$")
    private val MVAVE_OPEN_RE = Regex("^FM-1_80000\\d\\d$")
    private val OPEN_LOADER_RE = Regex("^ota-FM1C_[0-9a-f]+$")
    /** M-VAVE's FM-1 firmware, any version ("FM-1_016"); m-bootloader's installer is FM-1_099 */
    fun isStock(product: String) = Regex("^FM-1_0\\d\\d$").matches(product) && product != "FM-1_099"
    /** the update loader of M-VAVE's own firmware: written only a package of M-VAVE's own firmware */
    fun isMvaveLoader(id: OtaIdentity) = Regex("^ota-FM-1_0\\d\\d$", RegexOption.IGNORE_CASE).matches(id.text) || OPEN_LOADER_RE.matches(id.text)
    /** an FM-1 paired over Bluetooth MIDI never answers an update */
    fun isBluetooth(name: String?) = name?.contains("bluetooth", ignoreCase = true) == true

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    fun pack7(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size * 8 / 7 + 2)
        var acc = 0L; var nb = 0
        for (x in data) {
            acc = acc or ((x.toLong() and 0xFF) shl nb); nb += 8
            while (nb >= 7) { out.write((acc and 0x7F).toInt()); acc = acc ushr 7; nb -= 7 }
        }
        if (nb > 0) out.write((acc and 0x7F).toInt())
        return out.toByteArray()
    }

    fun unpack7(s: ByteArray, from: Int = 0, to: Int = s.size): ByteArray {
        val out = java.io.ByteArrayOutputStream((to - from) * 7 / 8 + 1)
        var acc = 0L; var nb = 0
        for (i in from until to) {
            acc = acc or ((s[i].toLong() and 0x7F) shl nb); nb += 7
            while (nb >= 8) { out.write((acc and 0xFF).toInt()); acc = acc ushr 8; nb -= 8 }
        }
        return out.toByteArray()
    }

    private fun sysex(body: ByteArray): ByteArray {
        val p = pack7(body)
        return ByteArray(p.size + 2).also { it[0] = 0xF0.toByte(); System.arraycopy(p, 0, it, 1, p.size); it[it.size - 1] = 0xF7.toByte() }
    }

    private fun isSysex(p: ByteArray) = p.size >= 2 && p[0] == 0xF0.toByte() && p[p.size - 1] == 0xF7.toByte()
    private fun u(a: ByteArray, i: Int) = a[i].toInt() and 0xFF

    /** the answer to a read request: [data] at [addr] */
    fun response(addr: Long, data: ByteArray, fl: Int = 0): ByteArray {
        val len = data.size
        val body = ByteArray(14 + len + 1)
        val head = intArrayOf(0x00, 0x59, 0x30, (len + 8) and 0xFF, ((len + 8) shr 8) and 0xFF, 0, fl,
            (addr and 0xFF).toInt(), ((addr ushr 8) and 0xFF).toInt(), ((addr ushr 16) and 0xFF).toInt(), ((addr ushr 24) and 0xFF).toInt(),
            len and 0xFF, (len shr 8) and 0xFF, 0)
        for (i in head.indices) body[i] = head[i].toByte()
        System.arraycopy(data, 0, body, 14, len)
        var s = 0
        for (i in 6 until 14 + len) s += body[i].toInt() and 0xFF
        body[14 + len] = (s.inv() and 0xFF).toByte()
        return sysex(body)
    }

    class Request(val fl: Int, val addr: Long, val len: Int)

    fun parseRequest(pkt: ByteArray): Request? {
        if (!isSysex(pkt)) return null
        val q = unpack7(pkt, 1, pkt.size - 1)
        if (q.size != 15 || u(q, 0) != 0 || u(q, 1) != 0x59 || u(q, 2) != 0x30) return null
        var s = 0
        for (i in 6 until 14) s += u(q, i)
        if ((s.inv() and 0xFF) != u(q, 14)) return null
        val addr = (u(q, 7).toLong() or (u(q, 8).toLong() shl 8) or (u(q, 9).toLong() shl 16) or (u(q, 10).toLong() shl 24))
        return Request(u(q, 6), addr, u(q, 11) or (u(q, 12) shl 8) or (u(q, 13) shl 16))
    }

    fun parseIdentity(pkt: ByteArray): OtaIdentity? {
        if (!isSysex(pkt)) return null
        val d = unpack7(pkt, 1, pkt.size - 1)
        if (d.size != 34 || u(d, 0) != 0 || u(d, 1) != 0x59 || u(d, 2) != 0x11) return null
        var z = -1
        for (i in 6 until d.size) if (d[i].toInt() == 0) { z = i; break }
        val end = if (z < 0 || z > 33) 33 else z
        val txt = String(d, 6, end - 6, Charsets.ISO_8859_1)
        if (OPEN_RE.matches(txt)) return OtaIdentity(txt, "FM-1", 0, open = true)
        if (OPEN_LOADER_RE.matches(txt)) return OtaIdentity(txt, "ota-FM1C", 0, open = true)
        val m = Regex("^([^_]+)_(\\d+)$").find(txt) ?: return null
        return OtaIdentity(txt, m.groupValues[1], m.groupValues[2].toBigInteger().min(Long.MAX_VALUE.toBigInteger()).toLong(), MVAVE_OPEN_RE.matches(txt))
    }

    fun frame(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        var s = 0
        for (x in payload) s += x.toInt() and 0xFF
        val body = ByteArray(6 + payload.size + 1)
        body[0] = 0; body[1] = 0x59; body[2] = cmd.toByte()
        body[3] = (payload.size and 0xFF).toByte(); body[4] = (payload.size shr 8).toByte(); body[5] = 0
        System.arraycopy(payload, 0, body, 6, payload.size)
        body[body.size - 1] = (s.inv() and 0xFF).toByte()
        return sysex(body)
    }

    val FAST_HELLO: ByteArray = frame(FAST_CMD, b(1, FAST_CHUNK and 0xFF, FAST_CHUNK shr 8))

    class Frame(val cmd: Int, val body: ByteArray)

    /** any frame in the update protocol's framing, its checksum checked */
    fun parseFrame(pkt: ByteArray): Frame? {
        if (!isSysex(pkt)) return null
        val q = unpack7(pkt, 1, pkt.size - 1)
        if (q.size < 7 || u(q, 0) != 0 || u(q, 1) != 0x59) return null
        val n = u(q, 3) or (u(q, 4) shl 8) or (u(q, 5) shl 16)
        if (q.size != 7 + n) return null
        var s = 0
        for (i in 6 until 6 + n) s += u(q, i)
        return if ((s.inv() and 0xFF) == u(q, 6 + n)) Frame(u(q, 2), q.copyOfRange(6, 6 + n)) else null
    }

    fun crc32(d: ByteArray, from: Int = 0, to: Int = d.size): Long = CRC32().apply { update(d, from, to - from) }.value

    private val UFW_CRC16_T = IntArray(256) { n ->
        var c = n shl 8
        repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
        c and 0xFFFF
    }

    /** the UFW's CRC16 (XMODEM: poly 0x1021, init 0) */
    fun ufwCrc16(d: ByteArray, from: Int = 0, to: Int = d.size, init: Int = 0): Int {
        var c = init
        for (i in from until to) c = (UFW_CRC16_T[((c shr 8) xor d[i].toInt()) and 0xFF] xor (c shl 8)) and 0xFFFF
        return c
    }

    /** the fast install's table: one entry per 4 KiB sector of [image] from [off]: CRC16, CRC-32, little-endian */
    fun sectorTable(image: ByteArray, off: Int, n: Int): ByteArray {
        val t = ByteArray(n * 6)
        for (k in 0 until n) {
            val a = (off + k * FAST_CHUNK).coerceAtMost(image.size)
            val e = (off + (k + 1) * FAST_CHUNK).coerceAtMost(image.size)
            val c = ufwCrc16(image, a, e)
            val c32 = crc32(image, a, e)
            t[k * 6] = (c and 0xFF).toByte(); t[k * 6 + 1] = (c shr 8).toByte()
            for (j in 0 until 4) t[k * 6 + 2 + j] = ((c32 ushr (8 * j)) and 0xFF).toByte()
        }
        return t
    }

    /** the package brings a Felucca update loader (its mark): only that loader hears the fast install's hello */
    fun hasLoader(image: ByteArray): Boolean {
        outer@ for (i in 0..image.size - LOADER_MARK.size) {
            for (k in LOADER_MARK.indices) if (image[i + k] != LOADER_MARK[k]) continue@outer
            return true
        }
        return false
    }

    fun sha256hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- the conversation

    private fun ask(link: SysexLink, query: ByteArray, ms: Long): Any? {          // OtaIdentity, null (no answer), false
        if (!link.send(query)) return false
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val p = link.read(end - System.currentTimeMillis()) ?: break
            parseIdentity(p)?.let { return it }
        }
        return null
    }

    /** the identity: HS_QUERY, then HS_FULL when the answer is NAME_ and three digits */
    fun handshake(link: SysexLink, tries: Int = 3): OtaIdentity? {
        link.drain()
        repeat(tries) {
            val id = ask(link, HS_QUERY, 1000)
            if (id == false) return null
            if (id !is OtaIdentity) return@repeat
            if (id.isLoader || !Regex("^[^_]+_\\d{3}$").matches(id.text)) return id
            val full = ask(link, HS_FULL, 600) as? OtaIdentity
            return if (full != null && full.model == id.model && full.text.startsWith(id.text)) full else id
        }
        return null
    }

    const val RES_REQ = 0x44
    private const val RES_MAGIC = 0x31534552L
    private const val RES_HEAD = 32

    /** a NAME-res.bin, its header checked (magic, the header's CRC, the payload's length and CRC) -> the payload;
     *  throws OtaError("resbad") otherwise */
    fun resPayload(region: ByteArray): ByteArray {
        fun rd(o: Int) = (region[o].toLong() and 0xFF) or ((region[o + 1].toLong() and 0xFF) shl 8) or ((region[o + 2].toLong() and 0xFF) shl 16) or ((region[o + 3].toLong() and 0xFF) shl 24)
        val bad = OtaError("resbad", "not a resource file, or a damaged one")
        if (region.size < RES_HEAD || rd(0) != RES_MAGIC || rd(28) != crc32(region, 0, 28)) throw bad
        val len = rd(4); val crc = rd(8)
        if (RES_HEAD + len > region.size) throw bad
        val payload = region.copyOfRange(RES_HEAD, RES_HEAD + len.toInt())
        if (crc32(payload) != crc) throw bad
        return payload
    }

    /** the identity a device answered is the package's: the whole of it, or NAME_ and three digits that start it */
    fun sameId(text: String, product: String) = text == product || (Regex("^[^_]+_\\d{3}$").matches(text) && product.startsWith(text))
}

/** M-VAVE / Felucca .fwsc packages: the identity they carry, the image the device reads, their checks (fm1pkg.js) */
object Fm1Pkg {
    private const val BLOCKS = 20
    private const val BLK = 0x30
    private const val KEEP = 0x2F

    /** the package's identity ("FM-1_904"): one marker byte after each of the first 20 blocks */
    fun productOf(fwsc: ByteArray): String {
        if (fwsc.size < BLOCKS * BLK) throw IllegalArgumentException("not an FM-1 package (too short)")
        val s = StringBuilder()
        for (i in 0 until BLOCKS) {
            val m = fwsc[i * BLK + KEEP].toInt() and 0xFF
            if (m != 0x7D) s.append(((m - i - 1) and 0xFF).toChar())
        }
        return s.toString()
    }

    /** the image the device addresses during an update: the .fwsc without the 20 marker bytes */
    fun logicalImage(fwsc: ByteArray): ByteArray {
        val out = ByteArray(fwsc.size - BLOCKS)
        for (i in 0 until BLOCKS) System.arraycopy(fwsc, i * BLK, out, i * KEEP, KEEP)
        System.arraycopy(fwsc, BLOCKS * BLK, out, BLOCKS * KEEP, fwsc.size - BLOCKS * BLK)
        return out
    }

    private fun ufwDec(b: ByteArray, n: Int): ByteArray {
        var k = 0xFFFF
        for (i in 0 until n) { b[i] = (b[i].toInt() xor (k and 0xFF)).toByte(); k = ((k shl 1) xor (if (k and 0x8000 != 0) 0x1021 else 0)) and 0xFFFF }
        return b
    }

    private val CHECKED_TYPES = setOf(0, 2, 100, 255)
    private fun rd16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun rd32(b: ByteArray, o: Int) = rd16(b, o).toLong() or (rd16(b, o + 2).toLong() shl 16)

    /** a damaged or wrong file, caught before anything is sent: throws saying which */
    fun checkUfw(image: ByteArray) {
        if (image.size < 0x40) throw IllegalArgumentException("not an FM-1 package (too short)")
        val h = ufwDec(image.copyOfRange(0, 0x40), 0x40)
        if (Fm1Ota.ufwCrc16(h, 2, 0x40) != rd16(h, 0)) throw IllegalArgumentException("not an FM-1 package (header checksum)")
        val n = rd16(h, 8)
        val end = 0x40 + n * 0x50
        if (n == 0 || end > image.size || Fm1Ota.ufwCrc16(image, 0x40, end) != rd16(h, 2)) throw IllegalArgumentException("the package's list of parts is damaged")
        for (i in 0 until n) {
            val e = ufwDec(image.copyOfRange(0x40 + i * 0x50, 0x90 + i * 0x50), 0x50)
            val off = rd32(e, 8); val len = rd32(e, 12); val type = rd16(e, 0)
            if (off + len > image.size) throw IllegalArgumentException("the package is damaged (part $type outside the file)")
            if (type in CHECKED_TYPES && Fm1Ota.ufwCrc16(image, off.toInt(), (off + len).toInt()) != rd16(e, 4))
                throw IllegalArgumentException("the package is damaged (part $type checksum)")
        }
    }

    /** M-VAVE's stock files whose SHA-256 is pinned (V15, V16): a file that says it is one must be that very file */
    val STOCK_FILES = mapOf(
        "FM-1_015" to (699956L to "db1642b2b6fa5c2cccb11ffd13878068bb28601678d3644049f99dc40e7edb8a"),
        "FM-1_016" to (718196L to "d017fb65e70aca97ee29d944470c3515a74581df5c0576bf70de917c7b2b61d5"))

    /** true when [product] is pinned and the file is it; false when not pinned; throws when pinned and different */
    fun stockChecksum(product: String, size: Long, sha256: String): Boolean {
        val pin = STOCK_FILES[product] ?: return false
        if (size != pin.first || sha256 != pin.second) throw IllegalArgumentException("the file says it is $product but it is not M-VAVE's file (size or SHA-256 differs)")
        return true
    }
}

/** the update, step by step (fm1ota.js Updater): install, resume, m-bootloader, the resource region */
class Fm1Updater(private val ports: Fm1OtaPorts) {
    /** never the fast install */
    var classic = false
    /** ms before each answer of the classic protocol (tests: 0) */
    var pause = 10L

    private fun sleep(ms: Long) = Thread.sleep(ms)

    fun waitFor(ms: Long, filter: (OtaIdentity) -> Boolean): OtaDevice? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            ports.find(filter)?.let { return it }
            sleep(1000)
        }
        return null
    }

    class Served(val served: Int, val finished: Boolean, val lost: Boolean = false)

    /** the fast install's word: true when the loader answers it within 0.5 s */
    private fun fastHello(link: SysexLink): Boolean {
        link.drain()
        if (!link.send(Fm1Ota.FAST_HELLO)) return false
        val end = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < end) {
            val p = link.read(end - System.currentTimeMillis()) ?: break
            val f = Fm1Ota.parseFrame(p)
            if (f != null && f.cmd == Fm1Ota.FAST_CMD && f.body.size == 3 && f.body[0].toInt() == 1 &&
                ((f.body[1].toInt() and 0xFF) or ((f.body[2].toInt() and 0xFF) shl 8)) >= Fm1Ota.FAST_CHUNK) return true
        }
        return false
    }

    /** serve read requests until the device asks for [finishAddr]; stops early when the port disappears */
    fun serve(link: SysexLink, image: ByteArray, finishAddr: Long, idleMs: Long, onProgress: ((Int, Long, Long, Int) -> Unit)? = null,
              loader: Boolean = false, fast: Boolean = false, onCheck: ((Int, Int) -> Unit)? = null): Served {
        var served = 0; var bytes = 0L; var last = System.currentTimeMillis()
        while (true) {
            val pkt = link.read(1000)
            if (pkt == null) {
                if (link.lost) return Served(served, false, lost = true)
                if (System.currentTimeMillis() - last > idleMs) return Served(served, false)
                continue
            }
            val r = Fm1Ota.parseRequest(pkt)
            if (r == null) {
                val f = if (loader && onCheck != null) Fm1Ota.parseFrame(pkt) else null
                if (f != null && f.cmd == Fm1Ota.CHECK_CMD && f.body.size == 4) {
                    last = System.currentTimeMillis()
                    onCheck!!((f.body[0].toInt() and 0xFF) or ((f.body[1].toInt() and 0xFF) shl 8), (f.body[2].toInt() and 0xFF) or ((f.body[3].toInt() and 0xFF) shl 8))
                }
                continue
            }
            last = System.currentTimeMillis()
            if (r.addr == Fm1Ota.FINISH_CHECK || r.addr == Fm1Ota.FINISH_WRITE) {
                link.send(Fm1Ota.response(r.addr, "success\u0000".toByteArray(Charsets.US_ASCII)))
                if (r.addr == finishAddr) return Served(served, true)
                continue
            }
            val data: ByteArray
            if (loader && r.addr >= Fm1Ota.TABLE && r.addr < Fm1Ota.TABLE_END) {
                val off = (r.addr - Fm1Ota.TABLE).toInt(); val n = r.len / 6
                if (r.len == 0 || r.len % 6 != 0 || r.len > Fm1Ota.FAST_CHUNK || off.toLong() + n.toLong() * Fm1Ota.FAST_CHUNK > image.size)
                    throw OtaError("badreq", "bad table request ${off.toString(16)}+${r.len}")
                data = Fm1Ota.sectorTable(image, off, n)
            } else {
                if (r.len > (if (loader) Fm1Ota.FAST_CHUNK else Fm1Ota.MAXDATA) || r.addr + r.len > image.size)
                    throw OtaError("badreq", "bad request ${r.addr.toString(16)}+${r.len}")
                data = image.copyOfRange(r.addr.toInt(), r.addr.toInt() + r.len)
            }
            if (!fast && pause > 0) sleep(pause)
            if (!link.send(Fm1Ota.response(r.addr, data, r.fl))) return Served(served, false, lost = true)
            served++
            bytes += r.len
            onProgress?.invoke(served, bytes, r.addr, r.len)
        }
    }

    /** full install: running firmware -> loader -> new firmware. [image]: the logical image (Fm1Pkg.logicalImage) */
    fun install(image: ByteArray, product: String, step: OtaStep, sha256: String? = null): String {
        val dev = ports.find { !it.isLoader } ?: throw OtaError("notfound", "FM-1 not found (USB cable, and no other app using it?)")
        val model = product.substringBefore('_')
        if (dev.id.model != model) { dev.link.close(); throw OtaError("model", "the device is ${dev.id.text}, the package is for $product") }
        if (dev.id.open) arm(dev, image, product, sha256)
        return stages(dev, image, product, step)
    }

    private fun stages(dev: OtaDevice, image: ByteArray, product: String, step: OtaStep): String {
        step.on("start", 0, 0, null, dev.id.text)
        dev.link.send(Fm1Ota.UPGRADE)
        sleep(2000)
        val s1 = serve(dev.link, image, Fm1Ota.FINISH_CHECK, 8000, { n, bytes, _, _ -> step.on("verify", n.toLong(), bytes, null, null) })
        dev.link.close()
        if (!s1.finished) throw OtaError(if (s1.lost) "lost" else "stopped", "the device ${if (s1.lost) "was disconnected" else "stopped"} after ${s1.served} requests: nothing was written")
        step.on("loader", 0, 0, null, null)
        sleep(3000)
        val ota = waitFor(30000) { it.isLoader } ?: throw OtaError("noloader", "the update loader did not appear. Replug the USB cable and install again: the device stays in update mode until it is finished.")
        val s2 = write(ota, image, step)
        if (!s2.finished) throw OtaError(if (s2.lost) "lost" else "stopped", "the loader ${if (s2.lost) "was disconnected" else "stopped"} after ${s2.served} requests. Replug and install again to resume.")
        return back(product, step)
    }

    /** the device back from the update, running [product] */
    private fun back(product: String, step: OtaStep): String {
        step.on("reboot", 0, 0, null, null)
        sleep(3000)
        val back = waitFor(40000) { !it.isLoader } ?: throw OtaError("noreturn", "the device did not come back: power-cycle it")
        settle(back, product)
        if (!Fm1Ota.sameId(back.id.text, product)) throw OtaError("mismatch", "installed, but the device reports ${back.id.text}", back.id.text)
        step.on("done", 0, 0, null, back.id.text)
        return back.id.text
    }

    /** a device back from an update that answered only NAME_ and three digits: asked again for its whole identity */
    private fun settle(back: OtaDevice, product: String, ms: Long = 10000) {
        val end = System.currentTimeMillis() + ms
        while (back.id.text != product && product.startsWith(back.id.text) && System.currentTimeMillis() < end) {
            sleep(500)
            val link = back.link
            link.drain()
            if (!link.send(Fm1Ota.HS_FULL)) break
            val until = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < until) {
                val p = link.read(until - System.currentTimeMillis()) ?: break
                val full = Fm1Ota.parseIdentity(p) ?: continue
                if (full.model == back.id.model && full.text.startsWith(back.id.text)) back.id = full
                break
            }
        }
        back.link.close()
    }

    /** m-bootloader (github.com/jvitkauskas/m-bootloader, MIT) after an install: the running firmware stages its
     *  installer and resets; it rewrites the boot area only and restarts into the firmware. A firmware that does not
     *  know it refuses it, and nothing is written. */
    fun installBoot(image: ByteArray, product: String, step: OtaStep): String {
        val dev = waitFor(40000) { !it.isLoader && it.text == product } ?: throw OtaError("noreturn", "the device did not come back: power-cycle it")
        step.on("bootstart", 0, 0, null, null)
        dev.link.send(Fm1Ota.UPGRADE)
        sleep(2000)
        val s = serve(dev.link, image, Fm1Ota.FINISH_CHECK, 8000)
        dev.link.close()
        if (!s.finished) throw OtaError(if (s.lost) "lost" else "bootrefused", "m-bootloader: the device stopped after ${s.served} requests: nothing was written")
        step.on("bootwrite", 0, 0, null, null)
        sleep(3000)
        val back = waitFor(40000) { !it.isLoader } ?: throw OtaError("noreturn", "the device did not come back: power-cycle it")
        back.link.close()
        step.on("bootdone", 0, 0, null, back.id.text)
        return back.id.text
    }

    /** the resource region after an install (device/res.c; NAME-res.bin beside the package): once the FM-1 runs
     *  [product], RES_REQ with the payload's length and CRC-32, then its reads answered -> 0 written (or there
     *  already), else the FM-1's code (2 transfer, 7 erase, 8 write, 10 playing, 20 another build's, 21 the store's
     *  move not done, 22 the check) */
    fun installRes(region: ByteArray, product: String, onPct: ((Int) -> Unit)? = null): Int {
        val payload = Fm1Ota.resPayload(region)
        val dev = waitFor(40000) { !it.isLoader && it.text == product } ?: throw OtaError("noreturn", "the device did not come back: power-cycle it")
        val link = dev.link
        try {
            link.drain()
            val crc = Fm1Ota.crc32(payload)
            val head = ByteArray(8)
            for (j in 0 until 4) { head[j] = ((payload.size ushr (8 * j)) and 0xFF).toByte(); head[4 + j] = ((crc ushr (8 * j)) and 0xFF).toByte() }
            link.send(Fm1Ota.frame(Fm1Ota.RES_REQ, head))
            var last = System.currentTimeMillis()
            while (true) {
                val pkt = link.read(500)
                if (pkt == null) {
                    if (link.lost) throw OtaError("lost", "the device was disconnected")
                    if (System.currentTimeMillis() - last > 10000) throw OtaError("stopped", "the device stopped asking for the resources")
                    continue
                }
                val r = Fm1Ota.parseRequest(pkt) ?: continue
                last = System.currentTimeMillis()
                val top = r.addr and 0xFF000000L
                if (top == 0xC0000000L || top == 0xCF000000L) {
                    link.send(Fm1Ota.response(r.addr, "success\u0000".toByteArray(Charsets.US_ASCII), r.fl))
                    return if (top == 0xC0000000L) 0 else ((r.addr and 0xFFFF).toInt()).takeIf { it != 0 } ?: 99
                }
                if (r.len > Fm1Ota.MAXDATA || r.addr + r.len > payload.size) throw OtaError("badreq", "bad request ${r.addr.toString(16)}+${r.len}")
                if (!link.send(Fm1Ota.response(r.addr, payload.copyOfRange(r.addr.toInt(), r.addr.toInt() + r.len), r.fl))) throw OtaError("lost", "the device was disconnected")
                onPct?.invoke(((r.addr + r.len) * 100 / payload.size).toInt())
            }
        } finally { link.close() }
    }

    /** M-VAVE's Open firmware: allow the update of M-VAVE's own firmware only ([sha256]: the package file's, hex) */
    private fun arm(dev: OtaDevice, image: ByteArray, product: String, sha256: String?) {
        if (!Fm1Ota.isStock(product) || sha256 == null || !Regex("^[0-9a-f]{64}$").matches(sha256)) {
            dev.link.close()
            throw OtaError("open", "This FM-1 runs M-VAVE's ${dev.id.text} firmware. Put the stock firmware back first, then install this.", dev.id.text)
        }
        val cap = query(dev.link, 49)
        if (cap == null || cap.size != 47 || String(cap, 0, 4, Charsets.US_ASCII) != "FMC1" || cap[6].toInt() != 0) {
            dev.link.close(); throw OtaError("stopped", "${dev.id.text} did not accept the update: nothing was written")
        }
        val n = image.size
        val tag = ByteArray(32) { sha256.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val ack = query(dev.link, 50, byteArrayOf(1, cap[5], (n and 0xFF).toByte(), ((n ushr 8) and 0xFF).toByte(), ((n ushr 16) and 0xFF).toByte(), ((n ushr 24) and 0xFF).toByte()) + tag)
        if (ack == null || ack.size != 1 || ack[0].toInt() != 0) { dev.link.close(); throw OtaError("stopped", "${dev.id.text} refused the package: nothing was written") }
    }

    /** send a command -> the payload of its reply, or null */
    private fun query(link: SysexLink, cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray? {
        link.drain()
        if (!link.send(Fm1Ota.frame(cmd, payload))) return null
        val end = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < end) {
            val p = link.read(end - System.currentTimeMillis()) ?: break
            if (p.size < 2 || p[0] != 0xF0.toByte() || p[p.size - 1] != 0xF7.toByte()) continue
            val q = Fm1Ota.unpack7(p, 1, p.size - 1)
            if (q.size < 7 || q[0].toInt() != 0 || q[1].toInt() != 0x59 || (q[2].toInt() and 0xFF) != cmd) continue
            var s = 0
            for (i in 6 until q.size) s += q[i].toInt() and 0xFF
            if ((s and 0xFF) == 0xFF) return q.copyOfRange(6, 6 + ((q[3].toInt() and 0xFF) or ((q[4].toInt() and 0xFF) shl 8)))
        }
        return null
    }

    /** step 2 with the loader: the fast install when the loader answers its hello, else the classic one */
    fun write(ota: OtaDevice, image: ByteArray, step: OtaStep): Served {
        val fast = !classic && Fm1Ota.hasLoader(image) && fastHello(ota.link)
        step.on("mode", 0, 0, null, if (fast) "fast" else "classic")
        var pct = 0; var off = -1L; var n = 0
        fun show(p: Int, served: Long, bytes: Long) { if (p > pct) { pct = p; step.on("write", served, bytes, pct, null) } }
        val onRead = { served: Int, bytes: Long, addr: Long, len: Int ->
            if (addr >= Fm1Ota.TABLE && addr < Fm1Ota.TABLE_END) { off = addr - Fm1Ota.TABLE; n = len / 6 }
            else if (off >= 0) { if (n > 0 && addr >= off) show(minOf(99, (((addr - off) / Fm1Ota.FAST_CHUNK + 1) * 100 / n).toInt()), served.toLong(), bytes) }
            else step.on("write", served.toLong(), bytes, null, null)
        }
        val onCheck = { k: Int, total: Int -> if (total > 0) show(minOf(99, k * 100 / total), 0, 0) }
        ota.link.send(Fm1Ota.UPGRADE)
        sleep(if (fast) 50 else 2000)
        val s2 = serve(ota.link, image, Fm1Ota.FINISH_WRITE, 180000, onRead, loader = true, fast = fast, onCheck = onCheck)
        ota.link.close()
        return s2
    }

    /** resume: the device already in update mode (loader) -> true when the write finished. With [product], a loader of
     *  M-VAVE's own firmware is refused unless the product is M-VAVE's too, and the FM-1 must come back running it */
    fun resume(image: ByteArray, step: OtaStep, product: String? = null): Boolean {
        val ota = ports.find { it.isLoader } ?: return false
        if (product != null && !Fm1Ota.isStock(product) && Fm1Ota.isMvaveLoader(ota.id)) {
            ota.link.close()
            throw OtaError("foreign", "the FM-1 is in the update mode of M-VAVE's own firmware (${ota.id.text}) and this package is not for it: nothing was written", ota.id.text)
        }
        if (!write(ota, image, step).finished) return false
        if (product != null) back(product, step)
        return true
    }
}
