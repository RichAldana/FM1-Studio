// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Charles Vestal (LOOM-8: app/web/samples/loom_samples.js, Link, readBytes, readBank, sendBank).
// Kotlin port for FM1 Mix over Android's MIDI (MidiManager), blocking: call it off the screen's thread.
package app.fm1mix

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A conversation with LOOM-8's samples handler (app/samples.c: F0 7D 46 4C cmd ... F7, an answer to each request).
 * Only LOOM-8 is ever sent these: [SampleTargets] finds it by name, then it must answer SB_PING.
 */
class LoomLink private constructor(
    private val device: MidiDevice,
    private val toDevice: MidiInputPort,
    private val fromDevice: MidiOutputPort
) {
    private val lock = Object()
    private var waitCmd = -1
    private var answer: IntArray? = null
    private val frame = ArrayList<Int>()
    private var inFrame = false

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (i in offset until offset + count) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) continue                 // the clock and the like, between a frame's bytes
                if (b == 0xF0) { frame.clear(); inFrame = true; continue }
                if (!inFrame) continue
                if (b == 0xF7) { inFrame = false; took(frame.toIntArray()); continue }
                if (b >= 0x80) { inFrame = false; continue }   // a frame cut short
                frame.add(b)
            }
        }
    }

    init {
        fromDevice.connect(receiver)
    }

    /** a whole frame (without F0 and F7): an answer if it is LOOM-8's to the request waited on */
    private fun took(d: IntArray) {
        if (d.size < 4 || d[0] != 0x7D || d[1] != 0x46 || d[2] != 0x4C) return
        synchronized(lock) {
            if (waitCmd < 0 || d[3] != waitCmd) return
            answer = d.copyOfRange(4, d.size)
            waitCmd = -1
            lock.notifyAll()
        }
    }

    class NoAnswer : Exception(tr("Le FM-1 ne répond plus.", "The FM-1 no longer answers."))

    /** Send a request, wait for its answer (what follows the command byte). */
    fun req(cmd: Int, args: IntArray, ms: Long = 3000): IntArray {
        synchronized(lock) {
            answer = null
            waitCmd = cmd
        }
        val b = LoomSamples.sysex(cmd, args)
        toDevice.send(b, 0, b.size)
        val end = System.currentTimeMillis() + ms
        synchronized(lock) {
            while (answer == null) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) { waitCmd = -1; throw NoAnswer() }
                lock.wait(left)
            }
            return answer!!
        }
    }

    /** Send a frame that has no answer (SB_LEDS). */
    fun post(cmd: Int, args: IntArray) {
        val b = LoomSamples.sysex(cmd, args)
        toDevice.send(b, 0, b.size)
    }

    fun retry(cmd: Int, args: IntArray, ms: Long, tries: Int = 3): IntArray {
        var k = 1
        while (true) {
            try { return req(cmd, args, ms) } catch (e: NoAnswer) { if (k >= tries) throw e }
            k++
        }
    }

    /** SMP_READ, 224 bytes a request */
    fun readBytes(off: Int, n: Int, onPart: ((Double) -> Unit)? = null): ByteArray {
        val out = ByteArray(n)
        var at = 0
        while (at < n) {
            val want = minOf(LoomSamples.READ_MAX, n - at)
            val adr = off + at
            val r = retry(17, intArrayOf(0, adr and 0x7F, (adr shr 7) and 0x7F, (adr shr 14) and 0x7F, want and 0x7F, want shr 7), 3000)
            if (r.size > 4 && r[4] != 0)
                throw IllegalStateException(if (r[4] == 4) tr("Le FM-1 est en train d'écrire ses échantillons.", "The FM-1 is writing its samples.") else tr("Le FM-1 n'a pas pu lire ses échantillons.", "The FM-1 could not read its samples."))
            val b = LoomSamples.unpack7(r.copyOfRange(minOf(5, r.size), r.size))
            if (b.size < want) throw IllegalStateException(tr("lecture incomplète", "incomplete read"))
            System.arraycopy(b, 0, out, at, want)
            at += want
            onPart?.invoke(at.toDouble() / n)
        }
        return out
    }

    /** The bank's image as the FM-1 holds it (header + data), or null when it holds none. */
    fun readBank(onPart: ((Double) -> Unit)? = null): ByteArray? {
        val info = req(15, intArrayOf(0))
        if (info.isEmpty() || info[0] != 2) throw IllegalStateException(tr("Ce LOOM-8 est trop ancien : mets-le à jour depuis sa page d'installation.", "This LOOM-8 is too old: update it from its install page."))
        if (info.size < 6 || info[5] == 0) return null
        val hdr = readBytes(0, LoomSamples.HDR_USED)
        val h = LoomSamples.parseHeader(hdr) ?: return null
        if (h.count == 0) return null
        val data = readBytes(LoomSamples.BANK_HDR, h.dataLen, onPart)
        val img = ByteArray(LoomSamples.BANK_HDR + h.dataLen)
        System.arraycopy(hdr, 0, img, 0, hdr.size)
        System.arraycopy(data, 0, img, LoomSamples.BANK_HDR, data.size)
        return img
    }

    /** Write a bank (every frame answered; a page sent again when its answer is lost). */
    fun sendBank(img: ByteArray, onPart: ((Double) -> Unit)? = null) {
        val total = img.size - LoomSamples.BANK_HDR
        for (f in LoomSamples.frames(img)) {
            var r: IntArray
            var k = 1
            while (true) {
                try { r = req(f.cmd, f.args, f.ms.toLong()); break } catch (e: NoAnswer) { if (f.cmd != 12 || k >= 4) throw e }
                k++
            }
            val rc = if (r.isNotEmpty()) r[r.size - 1] else 99
            if (rc != 0) throw IllegalStateException(tr("Le FM-1 n'a pas pris les échantillons (${LoomSamples.RC[rc] ?: rc}", "The FM-1 did not take the samples (${LoomSamples.RC[rc] ?: rc}") +
                (if (f.cmd == 12) tr(" à ${f.what}", " at ${f.what}") else "") + ").")
            if (f.cmd == 12 && onPart != null) onPart((minOf(f.off + 256, img.size) - LoomSamples.BANK_HDR).toDouble() / total)
        }
    }

    fun close() {
        runCatching { fromDevice.disconnect(receiver) }
        runCatching { fromDevice.close() }
        runCatching { toDevice.close() }
        runCatching { device.close() }
    }

    companion object {
        /** Open [info] and check it is LOOM-8 (it answers SB_PING within [wait] ms); null otherwise. */
        fun open(ctx: Context, info: MidiDeviceInfo, wait: Long = 1000): LoomLink? {
            if (info.inputPortCount == 0 || info.outputPortCount == 0) return null
            val mm = ctx.getSystemService(Context.MIDI_SERVICE) as MidiManager
            val got = CountDownLatch(1)
            var dev: MidiDevice? = null
            mm.openDevice(info, { d -> dev = d; got.countDown() }, Handler(Looper.getMainLooper()))
            if (!got.await(3, TimeUnit.SECONDS)) return null
            val d = dev ?: return null
            val inPort = d.openInputPort(0)
            val outPort = d.openOutputPort(0)
            if (inPort == null || outPort == null) {
                runCatching { inPort?.close() }; runCatching { outPort?.close() }; runCatching { d.close() }
                return null
            }
            val link = LoomLink(d, inPort, outPort)
            return try {
                link.retry(25, IntArray(0), wait)
                link
            } catch (e: Exception) {
                link.close()
                null
            }
        }
    }
}
