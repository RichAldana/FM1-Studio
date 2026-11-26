// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Charles Vestal (fm1-base tools/fm1_backup.py, from Polly's tools/polly_backup.py; LOOM-8's
//   app/web/backup/extras.js and app/web/samples/loom_samples.js)
// Kotlin port for FM1 Mix, blocking: call it off the screen's thread. This file stays under MPL-2.0 (as LoomLink.kt).
//
// Backing up what an FM-1 firmware saves, and putting it back, over USB-MIDI: any fm1-base firmware built with
// backup = true (device/backup.c). The host asks BU_REQ with the app's magic (4 letters); the FM-1 sends the backup in
// BU_REQ frames and ends with BU_END (length, CRC-32, status, flags). A restore: RS_REQ (length, CRC-32), then the FM-1
// reads the file as an update reads its package (cmd 0x30 requests), checks it whole, asks on its screen ("RESTORE FROM
// USB?" at ASK_ADDR: SEL yes, HOME no) and says OK_ADDR, or FAIL_ADDR | its code.
// The file: the magic, u16 version, u16 count, u32 flags (bit 0: taken while playing), u32 CRC-32 of those 12 bytes;
// each object: u16 id, u16 length, u32 CRC-32, the payload; u32 CRC-32 of everything before. After it, the
// firmware's own sections: a 4-letter tag, u32 length, the bytes, u32 CRC-32 (LOOM-8: "LSMP", its sample bank).
// No Android in here: the link is the real FM-1's MIDI (Fm1OtaAndroid.kt) or the emulated one's USB (EmuUsbLink).
package app.fm1mix

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Fm1Backup {
    const val RS_REQ = 0x41
    const val BU_REQ = 0x42
    const val BU_END = 0x43
    const val OK_ADDR = 0xC0000000L
    const val ASK_ADDR = 0xC1000000L
    const val FAIL_ADDR = 0xCF000000L
    const val HEAD = 16
    const val REC = 8
    const val PAYLOAD_MAX = 3840
    const val PLAYING = 10
    const val DECLINED = 11

    /** a firmware whose backups the app knows: its identity's prefix (fm1.toml id_prefix), its magic, its name, the
     *  files' extension, whether its sample bank goes along (LOOM-8's LSMP section) */
    class Kind(val prefix: String, val magic: String, val name: String, val ext: String, val loomSamples: Boolean = false)

    val KNOWN = listOf(
        Kind("FM-1_10", "LM8B", "LOOM-8", "loom8", loomSamples = true),
        Kind("FM-1_3", "PLYB", "Polly", "polly"))

    /** the firmware an identity is (FM-1_10xxxxxx: LOOM-8), when it is one the app can back up */
    fun kindOf(identity: String): Kind? = KNOWN.firstOrNull { identity.startsWith(it.prefix) }

    class BackupError(msg: String) : Exception(msg)

    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    private fun crc(b: ByteArray, from: Int = 0, to: Int = b.size) = Fm1Ota.crc32(b, from, to)

    class Parsed(val flags: Int, val objs: List<Pair<Int, ByteArray>>, val magic: String, val version: Int)

    /** a backup checked whole (without its sections: [split] first) */
    fun parse(image: ByteArray): Parsed {
        if (image.size < HEAD + 4) throw BackupError(tr("trop court pour une sauvegarde", "too short for a backup"))
        val b = le(image)
        val magic = String(image, 0, 4, Charsets.ISO_8859_1)
        val ver = b.getShort(4).toInt() and 0xFFFF
        val n = b.getShort(6).toInt() and 0xFFFF
        val flags = b.getInt(8)
        if (!magic.all { it.code in 0x21..0x7E } || (b.getInt(12).toLong() and 0xFFFFFFFFL) != crc(image, 0, 12))
            throw BackupError(tr("ce n'est pas une sauvegarde, ou son en-tête est abîmé", "not a backup, or its head is damaged"))
        var pos = HEAD
        val objs = ArrayList<Pair<Int, ByteArray>>()
        repeat(n) {
            if (pos + REC > image.size) throw BackupError(tr("coupée", "cut short"))
            val obj = b.getShort(pos).toInt() and 0xFFFF
            val ln = b.getShort(pos + 2).toInt() and 0xFFFF
            val c = b.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            pos += REC
            if (ln > PAYLOAD_MAX || pos + ln + 4 > image.size) throw BackupError(tr("partie $obj : en-tête faux", "part $obj: a wrong header"))
            if (crc(image, pos, pos + ln) != c) throw BackupError(tr("partie $obj abîmée", "part $obj damaged"))
            objs += obj to image.copyOfRange(pos, pos + ln)
            pos += ln
        }
        if (pos + 4 != image.size || (b.getInt(pos).toLong() and 0xFFFFFFFFL) != crc(image, 0, pos))
            throw BackupError(tr("abîmée (le contrôle du fichier)", "damaged (the whole file's CRC-32)"))
        return Parsed(flags, objs, magic, ver)
    }

    /** a file -> the backup itself, and its sections (tag, bytes) */
    fun split(data: ByteArray): Pair<ByteArray, List<Pair<String, ByteArray>>> {
        var pos = HEAD
        if (data.size >= HEAD) {
            val n = le(data).getShort(6).toInt() and 0xFFFF
            repeat(n) {
                if (pos + REC > data.size) return@repeat
                pos += REC + (le(data).getShort(pos + 2).toInt() and 0xFFFF)
            }
        }
        pos += 4
        if (pos >= data.size) return data to emptyList()
        val out = ArrayList<Pair<String, ByteArray>>()
        var at = pos
        while (at < data.size) {
            if (at + 8 > data.size) throw BackupError(tr("une section coupée", "a section cut short"))
            val tag = String(data, at, 4, Charsets.ISO_8859_1)
            val ln = le(data).getInt(at + 4)
            if (ln < 0 || at + 8 + ln + 4 > data.size) throw BackupError(tr("la section $tag est coupée", "section $tag is cut short"))
            val body = data.copyOfRange(at + 8, at + 8 + ln)
            if ((le(data).getInt(at + 8 + ln).toLong() and 0xFFFFFFFFL) != crc(body)) throw BackupError(tr("la section $tag est abîmée", "section $tag is damaged"))
            out += tag to body
            at += 8 + ln + 4
        }
        return data.copyOfRange(0, pos) to out
    }

    /** the backup and its sections, as the web page writes them */
    fun join(image: ByteArray, sections: List<Pair<String, ByteArray>>): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        o.write(image)
        for ((tag, body) in sections) {
            o.write(tag.toByteArray(Charsets.ISO_8859_1))
            o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array())
            o.write(body)
            o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc(body).toInt()).array())
        }
        return o.toByteArray()
    }

    /** "taken while playing": what was not saved yet is not in it */
    fun playing(image: ByteArray) = image.size >= 12 && (le(image).getInt(8) and 1) != 0

    /** The FM-1's backup ([magic]: its app's four letters), checked; [onBytes]: how much came */
    fun backup(link: SysexLink, magic: String, onBytes: ((Int) -> Unit)? = null): ByteArray {
        link.drain()
        if (!link.send(Fm1Ota.frame(BU_REQ, magic.toByteArray(Charsets.US_ASCII)))) throw OtaError("lost", tr("le FM-1 ne répond plus", "the FM-1 does not answer"))
        val got = java.io.ByteArrayOutputStream()
        var last = System.currentTimeMillis()
        var first = true
        while (true) {
            val pkt = link.read(500)
            if (pkt == null) {
                if (link.lost) throw OtaError("lost", tr("le FM-1 a été débranché", "the FM-1 was disconnected"))
                if (System.currentTimeMillis() - last > if (first) 3000 else 10000)
                    throw OtaError("stopped", if (first) tr("le FM-1 ne répond pas (un firmware sans sauvegarde ?)", "the FM-1 does not answer (a firmware without backup?)")
                    else tr("le FM-1 a cessé d'envoyer", "the FM-1 stopped sending"))
                continue
            }
            val f = Fm1Ota.parseFrame(pkt) ?: continue
            last = System.currentTimeMillis()
            if (f.cmd == BU_REQ && f.body.size >= 4) {
                first = false
                val off = le(f.body).getInt(0)
                if (off != got.size()) throw OtaError("lost", tr("un morceau de la sauvegarde s'est perdu", "a piece of the backup went missing"))
                got.write(f.body, 4, f.body.size - 4)
                onBytes?.invoke(got.size())
            } else if (f.cmd == BU_END && f.body.size >= 10) {
                val bb = le(f.body)
                val n = bb.getInt(0); val c = bb.getInt(4).toLong() and 0xFFFFFFFFL; val status = f.body[8].toInt() and 0xFF
                if (status != 0) throw OtaError("badreq", tr("le FM-1 n'a pas pu lire sa mémoire ($status)", "the FM-1 could not read its flash ($status)"))
                val img = got.toByteArray()
                if (n != img.size || c != crc(img)) throw OtaError("lost", tr("la sauvegarde est arrivée abîmée", "the backup arrived damaged"))
                parse(img)
                if (String(img, 0, 4, Charsets.ISO_8859_1) != magic) throw OtaError("lost", tr("ce n'est pas la sauvegarde demandée", "not the backup asked for"))
                return img
            }
        }
    }

    /** the FM-1's restore codes (backup.c RS_E_*), in words */
    fun error(code: Int): String = when (code) {
        1 -> tr("la taille du fichier", "the file's size")
        2 -> tr("le transfert", "the transfer")
        3 -> tr("une sauvegarde d'un autre firmware ou d'une autre version", "a backup of another firmware or version")
        4 -> tr("l'en-tête d'une partie", "a part's header")
        5, 6 -> tr("un octet changé en route", "a byte changed on the way")
        7, 8, 9 -> tr("l'écriture de la mémoire", "writing the flash")
        PLAYING -> tr("le FM-1 joue : arrête-le d'abord", "the FM-1 is playing: stop it first")
        DECLINED -> tr("refusé sur le FM-1", "answered no on the FM-1")
        12 -> tr("les en-têtes : le FM-1 finira à son prochain allumage", "the headers: the FM-1 finishes when switched on again")
        else -> tr("code $code", "code $code")
    }

    /** Put [image] (the backup alone: [split] it first) back: 0 when restored, else the FM-1's code ([error]).
     *  [onAsk]: the FM-1 asks on its screen now (SEL yes, HOME no); [onPct]: how much was sent */
    fun restore(link: SysexLink, image: ByteArray, onAsk: () -> Unit = {}, onPct: ((Int) -> Unit)? = null): Int {
        parse(image)
        link.drain()
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(image.size).putInt(crc(image).toInt()).array()
        if (!link.send(Fm1Ota.frame(RS_REQ, head))) throw OtaError("lost", tr("le FM-1 ne répond plus", "the FM-1 does not answer"))
        var last = System.currentTimeMillis(); var idle = 3000L; var asked = 0
        while (true) {
            val pkt = link.read(500)
            if (pkt == null) {
                if (link.lost) throw OtaError("lost", tr("le FM-1 a été débranché : rien n'a changé, sauf s'il l'a dit", "the FM-1 was disconnected: nothing changed unless it said so"))
                if (System.currentTimeMillis() - last > idle)
                    throw OtaError("stopped", if (asked > 0) tr("le FM-1 a cessé de demander : rien n'a changé", "the FM-1 stopped asking: nothing changed")
                    else tr("le FM-1 ne répond pas (un firmware sans restauration ?)", "the FM-1 does not answer (a firmware without restore?)"))
                continue
            }
            val r = Fm1Ota.parseRequest(pkt) ?: continue
            last = System.currentTimeMillis(); idle = 10000; asked++
            val top = r.addr and 0xFF000000L
            if (top == OK_ADDR || top == FAIL_ADDR || top == ASK_ADDR) {
                link.send(Fm1Ota.response(r.addr, "success\u0000".toByteArray(Charsets.US_ASCII), r.fl))
                if (top == ASK_ADDR) { idle = 75000; onAsk(); continue }
                return if (top == OK_ADDR) 0 else ((r.addr and 0xFFFF).toInt()).takeIf { it != 0 } ?: 99
            }
            if (r.len > Fm1Ota.MAXDATA || r.addr >= image.size) throw OtaError("badreq", tr("le FM-1 a demandé hors de la sauvegarde", "the FM-1 asked outside the backup"))
            val data = ByteArray(r.len)
            val n = minOf(r.len, image.size - r.addr.toInt())
            System.arraycopy(image, r.addr.toInt(), data, 0, n)
            if (!link.send(Fm1Ota.response(r.addr, data, r.fl))) throw OtaError("lost", tr("le FM-1 a été débranché", "the FM-1 was disconnected"))
            onPct?.invoke(minOf(100, ((r.addr + r.len) * 100 / image.size).toInt()))
        }
    }
}

/**
 * LOOM-8's sample bank over any SysexLink (as LoomLink.kt does over Android's MIDI: app/samples.c, F0 7D 46 4C cmd ...
 * F7, an answer to each request): the backup's LSMP section, read back and written back.
 */
class LoomBank(private val link: SysexLink) {
    class NoAnswer : Exception(tr("LOOM-8 ne répond plus.", "LOOM-8 no longer answers."))

    private fun req(cmd: Int, args: IntArray, ms: Long = 3000): IntArray {
        val b = LoomSamples.sysex(cmd, args)
        link.send(b)
        val end = System.currentTimeMillis() + ms
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0 || link.lost) throw NoAnswer()
            val p = link.read(left) ?: continue
            if (p.size > 5 && p[1].toInt() == 0x7D && p[2].toInt() == 0x46 && p[3].toInt() == 0x4C && (p[4].toInt() and 0xFF) == cmd)
                return IntArray(p.size - 6) { p[5 + it].toInt() and 0xFF }
        }
    }

    private fun retry(cmd: Int, args: IntArray, ms: Long, tries: Int = 3): IntArray {
        var k = 1
        while (true) {
            try { return req(cmd, args, ms) } catch (e: NoAnswer) { if (k >= tries) throw e }
            k++
        }
    }

    private fun readBytes(off: Int, n: Int, onPart: ((Double) -> Unit)?): ByteArray {
        val out = ByteArray(n)
        var at = 0
        while (at < n) {
            val want = minOf(LoomSamples.READ_MAX, n - at)
            val adr = off + at
            val r = retry(17, intArrayOf(0, adr and 0x7F, (adr shr 7) and 0x7F, (adr shr 14) and 0x7F, want and 0x7F, want shr 7), 3000)
            if (r.size > 4 && r[4] != 0) throw IllegalStateException(tr("LOOM-8 n'a pas pu lire ses échantillons.", "LOOM-8 could not read its samples."))
            val b = LoomSamples.unpack7(r.copyOfRange(minOf(5, r.size), r.size))
            if (b.size < want) throw IllegalStateException(tr("lecture incomplète", "incomplete read"))
            System.arraycopy(b, 0, out, at, want)
            at += want
            onPart?.invoke(at.toDouble() / n)
        }
        return out
    }

    /** the bank's image (the header, then the data), or null when LOOM-8 holds none */
    fun read(onPart: ((Double) -> Unit)? = null): ByteArray? {
        val info = req(15, intArrayOf(0))
        if (info.isEmpty() || info[0] != 2) throw IllegalStateException(tr("Ce LOOM-8 est trop ancien pour sauvegarder ses sons.", "This LOOM-8 is too old to back up its sounds."))
        if (info.size < 6 || info[5] == 0) return null
        val hdr = readBytes(0, LoomSamples.HDR_USED, null)
        val h = LoomSamples.parseHeader(hdr) ?: return null
        if (h.count == 0) return null
        val data = readBytes(LoomSamples.BANK_HDR, h.dataLen, onPart)
        val img = ByteArray(LoomSamples.BANK_HDR + h.dataLen)
        System.arraycopy(hdr, 0, img, 0, hdr.size)
        System.arraycopy(data, 0, img, LoomSamples.BANK_HDR, data.size)
        return img
    }

    /** write [img] back (every frame answered; a page sent again when its answer is lost) */
    fun write(img: ByteArray, onPart: ((Double) -> Unit)? = null) {
        val total = img.size - LoomSamples.BANK_HDR
        for (f in LoomSamples.frames(img)) {
            var r: IntArray
            var k = 1
            while (true) {
                try { r = req(f.cmd, f.args, f.ms.toLong()); break } catch (e: NoAnswer) { if (f.cmd != 12 || k >= 4) throw e }
                k++
            }
            val rc = if (r.isNotEmpty()) r[r.size - 1] else 99
            if (rc != 0) throw IllegalStateException(tr("LOOM-8 n'a pas pris les échantillons (${LoomSamples.RC[rc] ?: rc}).", "LOOM-8 did not take the samples (${LoomSamples.RC[rc] ?: rc})."))
            if (f.cmd == 12 && onPart != null && total > 0) onPart((minOf(f.off + 256, img.size) - LoomSamples.BANK_HDR).toDouble() / total)
        }
    }
}

/** the emulated FM-1's USB-MIDI (Native.emuUsb*: the emulator started with its cable in) as a SysexLink */
class EmuUsbLink : SysexLink {
    private val pending = ArrayDeque<ByteArray>()
    override fun send(b: ByteArray): Boolean { Native.emuUsbSend(b); return true }
    override fun read(ms: Long): ByteArray? {
        val end = System.currentTimeMillis() + ms
        while (true) {
            Native.emuUsbTake()?.let { if (it.isNotEmpty() && it[0] == 0xF0.toByte()) return it }
            if (System.currentTimeMillis() >= end) return null
            Thread.sleep(2)
        }
    }
    override fun drain() { while (Native.emuUsbTake() != null) { } }
    override val lost get() = Native.emuUsbProduct().isEmpty()
    override fun close() {}
}
