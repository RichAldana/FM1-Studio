package app.fm1mix

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV files in and out, for the slicer: whatever the app records (16-bit, 24-bit, 32-bit float; 44.1 or 48 kHz;
 * stereo or mono) and the usual WAVs of other apps (8/16/24/32-bit PCM, 32/64-bit float, WAVE_FORMAT_EXTENSIBLE).
 * No Android in here.
 */
object WavIO {
    /** A sound: [data] interleaved by [channels], full scale = 1.0; [bits] and [float]: how it was stored. */
    class Sound(val rate: Int, val channels: Int, val data: FloatArray, val bits: Int, val float: Boolean) {
        val frames: Int get() = data.size / channels

        /** the channels averaged, as doubles (what the analysis and LOOM-8's bank take) */
        fun mono(): DoubleArray {
            val out = DoubleArray(frames)
            for (f in 0 until frames) {
                var a = 0.0
                for (c in 0 until channels) a += data[f * channels + c]
                out[f] = a / channels
            }
            return out
        }
    }

    class Bad(msg: String) : Exception(msg)

    fun read(bytes: ByteArray): Sound {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(o: Int) = String(bytes, o, 4, Charsets.US_ASCII)
        if (bytes.size < 12 || tag(0) != "RIFF" || tag(8) != "WAVE") throw Bad("pas un fichier WAV")
        var fmt = -1
        var data = -1
        var dataLen = 0
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = tag(i)
            val n = b.getInt(i + 4).toLong() and 0xFFFFFFFFL
            if (id == "fmt " && fmt < 0) fmt = i + 8
            if (id == "data" && data < 0) { data = i + 8; dataLen = minOf(n, (bytes.size - data).toLong()).toInt() }
            i += 8 + n.toInt() + (n.toInt() and 1)
            if (n > bytes.size) break
        }
        if (fmt < 0) throw Bad("WAV sans bloc fmt")
        if (data < 0) throw Bad("WAV sans données")
        var format = b.getShort(fmt).toInt() and 0xFFFF
        val ch = b.getShort(fmt + 2).toInt() and 0xFFFF
        val rate = b.getInt(fmt + 4)
        val bits = b.getShort(fmt + 14).toInt() and 0xFFFF
        if (format == 0xFFFE) format = b.getShort(fmt + 24).toInt() and 0xFFFF
        val bps = bits / 8
        val float = format == 3
        if ((format != 1 && format != 3) || bps == 0 || ch == 0) throw Bad("WAV non pris en charge (format $format, $bits bits)")
        if (float && bps != 4 && bps != 8) throw Bad("WAV flottant de $bits bits")
        val frames = dataLen / (bps * ch)
        val out = FloatArray(frames * ch)
        var p = data
        for (k in out.indices) {
            out[k] = when {
                float && bps == 4 -> b.getFloat(p)
                float -> b.getDouble(p).toFloat()
                bps == 1 -> ((bytes[p].toInt() and 0xFF) - 128) / 128f
                bps == 2 -> b.getShort(p) / 32768f
                bps == 3 -> (((bytes[p].toInt() and 0xFF) shl 8) or ((bytes[p + 1].toInt() and 0xFF) shl 16) or (bytes[p + 2].toInt() shl 24)) / 2147483648f
                else -> b.getInt(p) / 2147483648f
            }
            p += bps
        }
        return Sound(rate, ch, out, bits, float)
    }

    /** A WAV file of [frames] frames of [data] (interleaved by [channels], full scale 1.0) as [bits] (16, 24, 32 float). */
    fun write(data: FloatArray, channels: Int, rate: Int, bits: Int): ByteArray {
        val float = bits == 32
        val bps = bits / 8
        val n = data.size / channels * channels
        val dataBytes = n * bps
        val o = ByteArrayOutputStream(58 + dataBytes)
        val h = ByteBuffer.allocate(if (float) 58 else 44).order(ByteOrder.LITTLE_ENDIAN)
        val fmtLen = if (float) 18 else 16
        h.put("RIFF".toByteArray()).putInt((if (float) 50 else 36) + dataBytes).put("WAVEfmt ".toByteArray()).putInt(fmtLen)
        h.putShort((if (float) 3 else 1).toShort()).putShort(channels.toShort()).putInt(rate)
        h.putInt(rate * channels * bps).putShort((channels * bps).toShort()).putShort(bits.toShort())
        if (float) h.putShort(0).put("fact".toByteArray()).putInt(4).putInt(n / channels)
        h.put("data".toByteArray()).putInt(dataBytes)
        o.write(h.array())
        val d = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (k in 0 until n) {
            val v = data[k]
            when (bits) {
                16 -> d.putShort(Math.round(v * 32768f).coerceIn(-32768, 32767).toShort())
                24 -> { val x = Math.round(v * 8388608.0).coerceIn(-8388608L, 8388607L).toInt(); d.put(x.toByte()).put((x shr 8).toByte()).put((x shr 16).toByte()) }
                else -> d.putFloat(v)
            }
        }
        o.write(d.array())
        return o.toByteArray()
    }
}
