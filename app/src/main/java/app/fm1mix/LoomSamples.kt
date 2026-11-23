// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Charles Vestal (LOOM-8: app/web/samples/loom_samples.js, tools/loom_samples.py, sampleio.py).
// Kotlin port for FM1 Mix: the same functions, the same order of operations, so the app builds the bank LOOM-8's
// samples page and command line build, byte for byte (checked against loom_samples.js: tools/loom_check).
// No Android in here.
package app.fm1mix

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * LOOM-8's user sample bank: what a sample becomes in it (resampled, pre-emphasised, IMA ADPCM with checkpoints),
 * the bank's layout and header, and the SysEx frames that write it (app/samples.c on the FM-1: F0 7D 46 4C cmd ... F7).
 */
object LoomSamples {
    val RATES = intArrayOf(11025, 16000, 22050, 32000)
    val AUTO = intArrayOf(22050, 16000, 11025)
    const val BLK = 128
    const val BLK_V2 = 64
    val REGIONS = arrayOf(intArrayOf(0xC1000, 0x1B000), intArrayOf(0xE5000, 0x17000), intArrayOf(0x93000, 0x4000))
    private val XDATA = intArrayOf(1, 2)
    private const val REC_MARK = 0xF06
    val BANK_SIZE = REGIONS.sumOf { it[1] }
    const val BANK_HDR = 2048
    val BANK_MAX_DATA = BANK_SIZE - BANK_HDR
    const val BANK_N = 32
    const val BANK_VER = 3
    const val NFENT = 12
    const val NENT = BANK_N + NFENT
    const val E_FACTORY = 0x01
    const val ENT0 = 64
    const val ENT = 40
    const val BANK_MAGIC = 0x504D534CL
    const val NAME_LEN = 12
    const val BARS_MAX = 16
    /** a loop's slice 1: F3, the lowest key */
    const val LOOP_ROOT = 53
    const val HDR_USED = ENT0 + NENT * ENT
    const val PART = 320
    val NPART = (HDR_USED + PART - 1) / PART
    const val READ_MAX = 224

    // IMA ADPCM's step sizes and index changes (the IMA standard's tables, 1992)
    private val IMA_STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767)
    private val IMA_IDX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
    private val IMA_BITS = arrayOf(intArrayOf(4, 0), intArrayOf(2, 1), intArrayOf(1, 2))

    /** the decoder's state after one code: (pred, idx) packed as pred and idx in an IntArray */
    private fun imaNext(pred0: Int, idx: Int, code: Int, out: IntArray) {
        val step = IMA_STEP[idx]
        var d = step shr 3
        for (b in IMA_BITS) if (code and b[0] != 0) d += step shr b[1]
        out[0] = max(-32768, min(32767, if (code and 8 != 0) pred0 - d else pred0 + d))
        out[1] = max(0, min(88, idx + IMA_IDX[code and 7]))
    }

    // ---- signal processing (sampleio's numpy path)

    /** numpy.convolve(a, h, mode="same") */
    private fun convolveSame(a: DoubleArray, h: DoubleArray): DoubleArray {
        val n = a.size
        val m = h.size
        val l = max(n, m)
        val off = (min(n, m) - 1) shr 1
        val out = DoubleArray(l)
        for (i in 0 until l) {
            val k = i + off
            var acc = 0.0
            val j0 = max(0, k - n + 1)
            val j1 = min(m - 1, k)
            var j = j0
            while (j <= j1) { acc += a[k - j] * h[j]; j++ }
            out[i] = acc
        }
        return out
    }

    private fun sinc(x: Double): Double { if (x == 0.0) return 1.0; val y = Math.PI * x; return StrictMath.sin(y) / y }

    private fun blackman(m: Int): DoubleArray {
        val w = DoubleArray(m)
        if (m == 1) { w[0] = 1.0; return w }
        for (i in 0 until m) {
            val n = 1 - m + 2 * i
            w[i] = 0.42 + 0.5 * StrictMath.cos(Math.PI * n / (m - 1)) + 0.08 * StrictMath.cos(2.0 * Math.PI * n / (m - 1))
        }
        return w
    }

    /** sampleio.resample: down, a windowed-sinc low-pass at 90 % of the new half rate; then linear interpolation */
    fun resample(x: DoubleArray, sr: Int, to: Int): DoubleArray {
        if (sr == to) return x.copyOf()
        var a = x.copyOf()
        if (a.size <= 1) return DoubleArray(0)
        if (to < sr) {
            val fc = 0.45 * to / sr
            val half = ceil(8.0 / fc).toInt()
            val m = 2 * half + 1
            val w = blackman(m)
            val h = DoubleArray(m)
            var sum = 0.0
            for (i in 0 until m) { val t = (i - half).toDouble(); h[i] = 2.0 * fc * sinc(2.0 * fc * t) * w[i] }
            for (i in 0 until m) sum += h[i]
            for (i in 0 until m) h[i] /= sum
            a = convolveSame(a, h)
        }
        val n = ceil((a.size - 1).toDouble() * to / sr).toInt()
        val step = sr.toDouble() / to
        val out = DoubleArray(n)
        val last = a.size - 1
        for (i in 0 until n) {
            val p = i * step
            val j = floor(p).toInt()
            out[i] = if (j >= last) a[last] else (a[j + 1] - a[j]) * (p - j) + a[j]
        }
        return out
    }

    private val EMPHASIS = doubleArrayOf(0.0517, -0.21, 1.3362, -0.21, 0.0517).map { it / 1.0196 }.toDoubleArray()
    fun emphasis(x: DoubleArray): DoubleArray = convolveSame(x.copyOf(), EMPHASIS)

    /** Python's round() */
    private fun roundHalfEven(v: Double): Double {
        val f = floor(v)
        val d = v - f
        if (d > 0.5) return f + 1
        if (d < 0.5) return f
        return if (f % 2.0 == 0.0) f else f + 1
    }

    /** sampleio.to_int16: the peak normalised to [peakTo] */
    fun toInt16(x: DoubleArray, peakTo: Double = 30000.0): ShortArray {
        var pk = 1e-9
        for (v in x) { val a = abs(v); if (a > pk) pk = a }
        val out = ShortArray(x.size)
        for (i in x.indices) out[i] = max(-32768.0, min(32767.0, roundHalfEven(x[i] / pk * peakTo))).toInt().toShort()
        return out
    }

    /** sampleio.clean_name: lower case, printable, at most 11 characters */
    fun cleanName(name: String): String {
        val s = name.lowercase().replace("_", " ").replace(Regex("[^a-z0-9 _.-]"), "").trim()
        return (if (s.isEmpty()) "sample" else s).take(NAME_LEN - 1)
    }

    class Encoded(val adpcm: ByteArray, val ck: LongArray)

    /** sampleio.ima_encode: the ADPCM, and the decoder's state before every BLK-th sample */
    fun imaEncode(s: ShortArray): Encoded {
        var pred = 0
        var idx = 0
        val adpcm = ByteArray((s.size + 1) shr 1)
        val ck = LongArray((s.size + BLK - 1) / BLK)
        val st = IntArray(2)
        for (n in s.indices) {
            val x = s[n].toInt()
            if (n % BLK == 0) ck[n / BLK] = ((pred and 0xFFFF) or (idx shl 16)).toLong() and 0xFFFFFFFFL
            val step = IMA_STEP[idx]
            var left = abs(x - pred)
            var code = if (x < pred) 8 else 0
            for (b in IMA_BITS) if (left >= (step shr b[1])) { code = code or b[0]; left -= step shr b[1] }
            adpcm[n shr 1] = (adpcm[n shr 1].toInt() or (code shl (4 * (n and 1)))).toByte()
            imaNext(pred, idx, code, st)
            pred = st[0]; idx = st[1]
        }
        return Encoded(adpcm, ck)
    }

    /** the firmware's decoder (dsp/sample.c) */
    fun imaDecode(b: ByteArray, n: Int): ShortArray {
        val out = ShortArray(n)
        val st = IntArray(2)
        var pred = 0
        var idx = 0
        for (i in 0 until n) {
            imaNext(pred, idx, (b[i shr 1].toInt() and 0xFF) shr (4 * (i and 1)) and 15, st)
            pred = st[0]; idx = st[1]
            out[i] = pred.toShort()
        }
        return out
    }

    fun sampleBytes(n: Int): Int = (n + BLK - 1) / BLK * 4 + ((n + 1) / 2 + 3) / 4 * 4

    class AtRate(val s: ShortArray, val loop: IntArray?)

    /** a file's samples at a rate, ready for the bank (loom_samples.at_rate) */
    fun atRate(x: DoubleArray, sr: Int, loop: IntArray?, rate: Int): AtRate {
        val s = toInt16(emphasis(resample(x, sr, rate)))
        val lp = if (loop != null) intArrayOf((loop[0].toLong() * rate / sr).toInt(), ((loop[1] + 1).toLong() * rate / sr).toInt()) else null
        return AtRate(s, lp)
    }

    fun lengthAt(nx: Int, sr: Int, rate: Int): Int = (nx.toLong() * rate / sr).toInt()

    // ---- the bank's layout (sampleio.user_bank)

    private val STARTS: IntArray = IntArray(REGIONS.size).also { var v = 0; for (r in REGIONS.indices) { it[r] = v; v += REGIONS[r][1] } }

    private fun regionOf(v: Int): Int {
        for (r in REGIONS.indices) if (STARTS[r] <= v && v < STARTS[r] + REGIONS[r][1]) return r
        return REGIONS.size
    }

    class TooLong(msg: String) : Exception(msg)

    private class Placed(val where: Array<IntArray>, val end: Int)

    private fun place(blobs: List<Pair<ByteArray, ByteArray>>, pad: IntArray): Placed {
        val starts = STARTS + BANK_SIZE
        val out = ArrayList<IntArray>()
        var v = BANK_HDR
        for (k in blobs.indices) {
            val ckb = blobs[k].first
            val adpb = blobs[k].second
            v = (v + pad[k] + 3) and 3.inv()
            var a: Int
            while (true) {
                val r = regionOf(v)
                if (r >= REGIONS.size) throw TooLong("too long")
                if (v + ckb.size > starts[r + 1]) { v = starts[r + 1]; continue }
                a = v + ckb.size
                val ra = if (a < BANK_SIZE) regionOf(a) else REGIONS.size
                if (ra >= REGIONS.size || a + adpb.size > starts[min(ra + 2, REGIONS.size)]) {
                    if (ra + 1 >= REGIONS.size) throw TooLong("too long")
                    v = starts[ra + 1]
                    continue
                }
                break
            }
            out.add(intArrayOf(v, a))
            v = a + adpb.size
        }
        if (v > BANK_SIZE) throw TooLong("too long")
        return Placed(out.toTypedArray(), v)
    }

    private fun marks(img: ByteArray, end: Int): List<Int> {
        val out = ArrayList<Int>()
        for (r in XDATA) {
            var sec = 0
            while (sec < REGIONS[r][1]) {
                val v = STARTS[r] + sec + REC_MARK
                if (v + 2 <= end && img[v] == 0x41.toByte() && img[v + 1] == 0x54.toByte()) out.add(v)
                sec += 0x1000
            }
        }
        return out
    }

    private val CRC_T: LongArray by lazy {
        LongArray(256) { n ->
            var c = n.toLong()
            for (k in 0 until 8) c = if (c and 1L != 0L) 0xEDB88320L xor (c ushr 1) else c ushr 1
            c
        }
    }

    fun crc32(b: ByteArray, from: Int = 0, to: Int = b.size): Long {
        var c = 0xFFFFFFFFL
        for (i in from until to) c = CRC_T[((c xor (b[i].toLong() and 0xFF)) and 0xFF).toInt()] xor (c ushr 8)
        return (c xor 0xFFFFFFFFL) and 0xFFFFFFFFL
    }

    /**
     * A sample for the bank. Either new: [s] (16-bit, at [rate], encoded here); or kept: [ckb] + [adpcm] as stored
     * (read back), [n] samples. [x] + [sr] instead of [s]: a file's sound, put at a rate by [build].
     */
    class Item(
        val name: String,
        val root: Int,
        val loop: IntArray?,
        val bars: Int,
        val rate: Int?,
        val flags: Int = 0,
        val s: ShortArray? = null,
        val n: Int = 0,
        val ckb: ByteArray? = null,
        val adpcm: ByteArray? = null,
        val x: DoubleArray? = null,
        val sr: Int = 0
    ) {
        val isFactory: Boolean get() = flags and E_FACTORY != 0
        val length: Int get() = if (adpcm != null) n else s?.size ?: 0
    }

    private fun blobOf(it: Item): Pair<ByteArray, ByteArray> {
        if (it.adpcm != null) return Pair(it.ckb!!, it.adpcm)
        val e = imaEncode(it.s!!)
        val ckb = ByteArray(e.ck.size * 4)
        for (i in e.ck.indices) putU32(ckb, i * 4, e.ck[i])
        return Pair(ckb, e.adpcm)
    }

    private fun putU32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte() }
    private fun putU16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    private fun u32(b: ByteArray, o: Int): Long = (0 until 4).fold(0L) { a, i -> a or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }
    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /** sampleio.user_bank: the bank's image (header + data, not padded to BANK_SIZE); throws TooLong */
    fun userBank(samples: List<Item>): ByteArray {
        val own = samples.count { !it.isFactory }
        if (samples.isEmpty() || own > BANK_N || samples.size - own > NFENT)
            throw IllegalArgumentException("1..$BANK_N samples in the bank (and the factory set's $NFENT)")
        val blobs = ArrayList<Pair<ByteArray, ByteArray>>()
        class Meta(val nm: String, val n: Int, val ls: Int, val le: Int, val root: Int, val flags: Int, val bars: Int, val rate: Int)
        val meta = ArrayList<Meta>()
        for (smp in samples) {
            val n = smp.length
            if (smp.bars < 0 || smp.bars > BARS_MAX) throw IllegalArgumentException("${smp.name}: ${smp.bars} bars (0..$BARS_MAX)")
            val rate = smp.rate ?: 0
            if (rate < 4000 || rate > 48000) throw IllegalArgumentException("${smp.name}: $rate Hz (4000..48000)")
            if (n == 0) throw IllegalArgumentException("${smp.name}: no audio")
            blobs.add(blobOf(smp))
            var ls = smp.loop?.get(0) ?: 0
            var le = smp.loop?.get(1) ?: 0
            le = min(le, n)
            if (le <= ls) { ls = 0; le = 0 }
            meta.add(Meta(cleanName(smp.name), n, ls, le, max(0, min(127, smp.root)), smp.flags, smp.bars, rate))
        }
        val pad = IntArray(blobs.size)
        var placed: Placed
        var img: ByteArray
        var t = 0
        while (true) {
            if (t >= 4096) throw IllegalStateException("could not keep the update record's mark out of the bank")
            placed = try { place(blobs, pad) } catch (e: TooLong) {
                val used = blobs.sumOf { it.first.size + it.second.size }
                throw TooLong("too long: $used B, the bank holds $BANK_MAX_DATA B")
            }
            img = ByteArray(placed.end) { 0xFF.toByte() }
            for (k in blobs.indices) {
                System.arraycopy(blobs[k].first, 0, img, placed.where[k][0], blobs[k].first.size)
                System.arraycopy(blobs[k].second, 0, img, placed.where[k][1], blobs[k].second.size)
            }
            val mk = marks(img, placed.end)
            if (mk.isEmpty()) break
            val w = placed.where
            val k = w.indices.indexOfFirst { i -> w[i][0] <= mk[0] && mk[0] < w[i][1] + blobs[i].second.size }
            if (k < 0) throw IllegalStateException("an update record's mark outside the samples")
            pad[k] += 4
            t++
        }
        val dataLen = img.size - BANK_HDR
        val out = ByteArray(BANK_HDR + dataLen)
        putU32(out, 0, BANK_MAGIC)
        putU16(out, 4, BANK_VER)
        out[6] = meta.size.toByte()
        out[7] = 0
        putU32(out, 8, dataLen.toLong())
        putU32(out, 12, crc32(img, BANK_HDR, img.size))
        putU32(out, 16, BANK_SIZE.toLong())
        for (k in meta.indices) {
            val m = meta[k]
            val o = ENT0 + k * ENT
            for (i in m.nm.indices) out[o + i] = m.nm[i].code.toByte()
            putU32(out, o + 12, placed.where[k][1].toLong())
            putU32(out, o + 16, placed.where[k][0].toLong())
            putU32(out, o + 20, m.n.toLong())
            putU32(out, o + 24, m.ls.toLong())
            putU32(out, o + 28, m.le.toLong())
            out[o + 32] = m.root.toByte()
            out[o + 33] = m.flags.toByte()
            out[o + 34] = m.bars.toByte()
            putU16(out, o + 36, m.rate)
        }
        System.arraycopy(img, BANK_HDR, out, BANK_HDR, dataLen)
        return out
    }

    class Entry(val name: String, val adpOff: Int, val ckOff: Int, val n: Int, val ls: Int, val le: Int, val root: Int,
                val flags: Int, val bars: Int, val rate: Int)
    class Header(val count: Int, val ver: Int, val dataLen: Int, val crc: Long, val entries: List<Entry>)

    /** a bank's header -> its entries (null: not a bank) */
    fun parseHeader(h: ByteArray): Header? {
        val ver = if (h.size >= ENT0) u16(h, 4) else 0
        if (h.size < ENT0 || u32(h, 0) != BANK_MAGIC || (ver != 2 && ver != 3)) return null
        val count = h[6].toInt() and 0xFF
        if (count > NENT || h.size < ENT0 + count * ENT) return null
        val out = ArrayList<Entry>()
        for (k in 0 until count) {
            val o = ENT0 + k * ENT
            val sb = StringBuilder()
            var i = 0
            while (i < NAME_LEN && h[o + i] != 0.toByte()) { sb.append((h[o + i].toInt() and 0xFF).toChar()); i++ }
            out.add(Entry(sb.toString(), u32(h, o + 12).toInt(), u32(h, o + 16).toInt(), u32(h, o + 20).toInt(),
                u32(h, o + 24).toInt(), u32(h, o + 28).toInt(), h[o + 32].toInt() and 0xFF, h[o + 33].toInt() and 0xFF,
                h[o + 34].toInt() and 0xFF, u16(h, o + 36)))
        }
        return Header(count, ver, u32(h, 8).toInt(), u32(h, 12), out)
    }

    /** loom_samples.pack7: groups of up to 7 bytes, each preceded by their top bits */
    fun pack7(b: ByteArray, from: Int = 0, to: Int = b.size): IntArray {
        val out = ArrayList<Int>()
        var i = from
        while (i < to) {
            val e = min(i + 7, to)
            var m = 0
            for (j in i until e) m = m or (((b[j].toInt() shr 7) and 1) shl (j - i))
            out.add(m)
            for (j in i until e) out.add(b[j].toInt() and 0x7F)
            i += 7
        }
        return out.toIntArray()
    }

    fun unpack7(a: IntArray): ByteArray {
        val out = ArrayList<Byte>()
        var i = 0
        while (i < a.size) {
            val m = a[i++]
            var j = 0
            while (j < 7 && i < a.size) { out.add((a[i++] or (((m shr j) and 1) shl 7)).toByte()); j++ }
        }
        return out.toByteArray()
    }

    /** One request of an upload: the command, its arguments, how long its answer may take, what it is. */
    class Frame(val cmd: Int, val args: IntArray, val ms: Int, val what: String, val off: Int = -1)

    /** loom_samples.frames: the upload's requests */
    fun frames(image: ByteArray): List<Frame> {
        val out = ArrayList<Frame>()
        out.add(Frame(11, intArrayOf(0), 10000, "begin"))
        var off = BANK_HDR
        while (off < image.size) {
            val p = pack7(image, off, min(off + 256, image.size))
            out.add(Frame(12, intArrayOf(0, off and 0x7F, (off shr 7) and 0x7F, (off shr 14) and 0x7F) + p, 5000, "$off", off))
            off += 256
        }
        for (part in 0 until NPART) {
            val p = pack7(image, part * PART, min((part + 1) * PART, BANK_HDR))
            out.add(Frame(16, intArrayOf(0, part) + p, 3000, "header part $part"))
        }
        out.add(Frame(13, intArrayOf(0), 20000, "end"))
        return out
    }

    /** A request as the bytes sent: F0 7D 46 4C cmd args F7 */
    fun sysex(cmd: Int, args: IntArray): ByteArray {
        val b = ByteArray(args.size + 6)
        b[0] = 0xF0.toByte(); b[1] = 0x7D; b[2] = 0x46; b[3] = 0x4C; b[4] = cmd.toByte()
        for (i in args.indices) b[5 + i] = args[i].toByte()
        b[b.size - 1] = 0xF7.toByte()
        return b
    }

    class Built(val img: ByteArray, val chose: Int)

    /**
     * loom_samples.build: the bank from items in order (kept or new); [rate] for the new ones that did not choose
     * (null: the highest of AUTO at which all fits). Throws TooLong.
     */
    fun build(items: List<Item>, rate: Int? = null): Built {
        val own = items.count { !it.isFactory }
        if (items.isEmpty() || own > BANK_N) throw IllegalArgumentException("1..$BANK_N samples in the bank")
        val tries = if (rate != null) intArrayOf(rate) else AUTO
        val cache = HashMap<Item, HashMap<Int, AtRate>>()
        fun est(it: Item, r: Int): Int =
            sampleBytes(if (it.adpcm != null) it.n else it.s?.size ?: lengthAt(it.x!!.size, it.sr, it.rate ?: r))
        for (r in tries) {
            if (items.sumOf { est(it, r) } > BANK_MAX_DATA) continue
            val smps = items.map { it ->
                if (it.adpcm != null || it.s != null) it
                else {
                    val rr = it.rate ?: r
                    val c = cache.getOrPut(it) { HashMap() }
                    val a = c.getOrPut(rr) { atRate(it.x!!, it.sr, it.loop, rr) }
                    Item(it.name, it.root, a.loop, it.bars, rr, it.flags, s = a.s)
                }
            }
            try { return Built(userBank(smps), r) } catch (e: TooLong) { }
        }
        val last = tries[tries.size - 1]
        val need = items.sumOf { est(it, last) }
        throw TooLong("too long: %.1f KB at %d Hz, the bank holds %.1f KB".format(java.util.Locale.ROOT, need / 1024.0, last, BANK_MAX_DATA / 1024.0))
    }

    /** the samples of a bank image as kept items (each sample's checkpoints and ADPCM as stored) */
    fun keptOf(img: ByteArray): List<Item> {
        val h = parseHeader(img.copyOfRange(0, min(BANK_HDR, img.size))) ?: return emptyList()
        val blk = if (h.ver == 2) BLK_V2 else BLK
        return h.entries.map { e ->
            var ckb = img.copyOfRange(e.ckOff, e.ckOff + (e.n + blk - 1) / blk * 4)
            if (blk != BLK) {
                val step = BLK / blk
                val o = ByteArray((e.n + BLK - 1) / BLK * 4)
                for (i in 0 until o.size / 4) System.arraycopy(ckb, i * step * 4, o, i * 4, 4)
                ckb = o
            }
            Item(e.name, e.root, if (e.le > e.ls) intArrayOf(e.ls, e.le) else null, e.bars, e.rate, e.flags,
                n = e.n, ckb = ckb, adpcm = img.copyOfRange(e.adpOff, e.adpOff + ((e.n + 1) shr 1)))
        }
    }

    /** What the FM-1 answered a request with, as an error to show (0: none). */
    val RC = mapOf(1 to "a size or offset", 2 to "erasing", 3 to "writing", 4 to "the bank not begun", 5 to "the header",
        6 to "the data's check", 7 to "no samples", 8 to "a reserved mark")
}
