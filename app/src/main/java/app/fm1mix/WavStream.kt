package app.fm1mix

import android.content.Context
import android.net.Uri
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A WAV file read in pieces (the loop player: a passage of a long file, or a whole file for the passage screen,
 * without holding it all as floats). The same formats as WavIO: 8/16/24/32-bit PCM, 32/64-bit float, extensible.
 */
class WavStream private constructor(
    private val ctx: Context, private val uri: Uri,
    val rate: Int, val channels: Int, private val bps: Int, private val float: Boolean,
    private val dataOffset: Long, val frames: Long,
) {
    val seconds: Double get() = frames.toDouble() / rate

    /**
     * Frames [from] to [from] + [count] (cut at the end), in pieces of up to [piece] frames: [sink] gets each as floats
     * interleaved by [channels] (full scale 1.0) and how many values it holds.
     */
    fun read(from: Long, count: Long, piece: Int = 16384, sink: (FloatArray, Int) -> Unit) {
        val n = minOf(count, frames - from).coerceAtLeast(0)
        if (n == 0L) return
        val block = bps * channels
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            val input = raw.buffered(1 shl 16)
            skipFully(input, dataOffset + from * block)
            val bytes = ByteArray(piece * block)
            val out = FloatArray(piece * channels)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var left = n
            while (left > 0) {
                val want = minOf(left, piece.toLong()).toInt()
                val got = readAtMost(input, bytes, want * block) / block
                if (got <= 0) break
                val values = got * channels
                var p = 0
                for (k in 0 until values) {
                    out[k] = when {
                        float && bps == 4 -> bb.getFloat(p)
                        float -> bb.getDouble(p).toFloat()
                        bps == 1 -> ((bytes[p].toInt() and 0xFF) - 128) / 128f
                        bps == 2 -> bb.getShort(p) / 32768f
                        bps == 3 -> (((bytes[p].toInt() and 0xFF) shl 8) or ((bytes[p + 1].toInt() and 0xFF) shl 16) or (bytes[p + 2].toInt() shl 24)) / 2147483648f
                        else -> bb.getInt(p) / 2147483648f
                    }
                    p += bps
                }
                sink(out, values)
                left -= got
                if (got < want) break
            }
        } ?: throw IllegalStateException(tr("fichier illisible", "unreadable file"))
    }

    /** All of frames from..from+count as one array (a passage: 30 s at most). */
    fun readAll(from: Long, count: Long): FloatArray {
        val n = minOf(count, frames - from).coerceAtLeast(0).toInt()
        val all = FloatArray(n * channels)
        var at = 0
        read(from, n.toLong()) { a, len -> val m = minOf(len, all.size - at); System.arraycopy(a, 0, all, at, m); at += m }
        return if (at == all.size) all else all.copyOf(at)
    }

    companion object {
        /** The file's format and length (its header only). */
        fun open(ctx: Context, uri: Uri): WavStream {
            val size = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
            ctx.contentResolver.openInputStream(uri)?.use { raw -> try {
                val input = DataInputStream(raw.buffered(1 shl 16))
                val head = ByteArray(12)
                input.readFully(head)
                fun tag(b: ByteArray, o: Int) = String(b, o, 4, Charsets.US_ASCII)
                if (tag(head, 0) != "RIFF" || tag(head, 8) != "WAVE") throw WavIO.Bad(tr("pas un fichier WAV", "not a WAV file"))
                var at = 12L
                var fmt: ByteBuffer? = null
                val ch = ByteArray(8)
                while (true) {
                    input.readFully(ch)
                    at += 8
                    val id = tag(ch, 0)
                    val len = ByteBuffer.wrap(ch, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    if (id == "fmt ") {
                        val f = ByteArray(len.toInt().coerceIn(16, 4096))
                        input.readFully(f)
                        skipFully(input, len - f.size + (len and 1))
                        fmt = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                        at += len + (len and 1)
                    } else if (id == "data") {
                        val b = fmt ?: throw WavIO.Bad(tr("WAV sans bloc fmt", "WAV without a fmt chunk"))
                        var format = b.getShort(0).toInt() and 0xFFFF
                        val chans = b.getShort(2).toInt() and 0xFFFF
                        val rate = b.getInt(4)
                        val bits = b.getShort(14).toInt() and 0xFFFF
                        if (format == 0xFFFE && b.capacity() >= 26) format = b.getShort(24).toInt() and 0xFFFF
                        val bps = bits / 8
                        val isFloat = format == 3
                        if ((format != 1 && format != 3) || bps == 0 || chans == 0 || rate <= 0)
                            throw WavIO.Bad(tr("WAV non pris en charge (format $format, $bits bits)", "unsupported WAV (format $format, $bits bits)"))
                        // (a length of 0 or "unknown" from a recorder stopped short: up to the end of the file)
                        val dataLen = if ((len == 0L || len == 0xFFFFFFFFL) && size > at) size - at
                            else if (size > 0) minOf(len, size - at) else len
                        return WavStream(ctx, uri, rate, chans, bps, isFloat, at, dataLen / (bps * chans))
                    } else {
                        skipFully(input, len + (len and 1))
                        at += len + (len and 1)
                    }
                }
            } catch (e: java.io.EOFException) { throw WavIO.Bad(tr("WAV sans données", "WAV without data")) } }
            throw IllegalStateException(tr("fichier illisible", "unreadable file"))
        }

        private fun skipFully(input: InputStream, n: Long) {
            var left = n
            while (left > 0) {
                val s = input.skip(left)
                if (s > 0) { left -= s; continue }
                if (input.read() < 0) throw WavIO.Bad(tr("WAV coupé", "truncated WAV"))
                left--
            }
        }

        private fun readAtMost(input: InputStream, b: ByteArray, n: Int): Int {
            var got = 0
            while (got < n) {
                val r = input.read(b, got, n - got)
                if (r < 0) break
                got += r
            }
            return got
        }
    }
}
