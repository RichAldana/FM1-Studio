package app.fm1mix

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The real FM-1's USB-MIDI for the firmware manager (Fm1Ota.kt, Fm1Backup.kt): a [SysexLink] over Android's MIDI, and
 * the ports to look among ([AndroidOtaPorts]). Blocking: off the screen's thread.
 * Android sends at most 1015 bytes a call (MidiPortImpl.MAX_PACKET_DATA_SIZE): a longer SysEx (the fast install's
 * 4 KiB sectors) goes in pieces, which the USB driver joins on the wire. Received bytes are put together into whole
 * SysEx messages; real-time bytes between them (the clock) are left out.
 */
class AndroidSysexLink private constructor(
    private val mm: MidiManager,
    private val info: MidiDeviceInfo,
    private val device: MidiDevice,
    private val toDevice: MidiInputPort,
    private val fromDevice: MidiOutputPort,
    private val handler: Handler
) : SysexLink {
    private val q = LinkedBlockingQueue<ByteArray>()
    @Volatile private var gone = false
    private val frame = java.io.ByteArrayOutputStream()
    private var inFrame = false

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (i in offset until offset + count) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) continue
                if (b == 0xF0) { frame.reset(); frame.write(b); inFrame = true; continue }
                if (!inFrame) continue
                if (b == 0xF7) { frame.write(b); inFrame = false; q.offer(frame.toByteArray()); continue }
                if (b >= 0x80) { inFrame = false; continue }
                if (frame.size() < 70000) frame.write(b) else inFrame = false
            }
        }
    }

    private val watch = object : MidiManager.DeviceCallback() {
        override fun onDeviceRemoved(d: MidiDeviceInfo) { if (d.id == info.id) gone = true }
    }

    init {
        fromDevice.connect(receiver)
        @Suppress("DEPRECATION")
        mm.registerDeviceCallback(watch, handler)
    }

    override fun send(b: ByteArray): Boolean {
        if (gone) return false
        return try {
            var at = 0
            while (at < b.size) {
                val n = minOf(1015, b.size - at)
                toDevice.send(b, at, n)
                at += n
            }
            true
        } catch (e: Exception) { gone = true; false }
    }

    override fun read(ms: Long): ByteArray? = if (ms <= 0) q.poll() else q.poll(ms, TimeUnit.MILLISECONDS)
    override fun drain() { q.clear() }
    override val lost: Boolean get() = gone
    override fun close() {
        runCatching { mm.unregisterDeviceCallback(watch) }
        runCatching { fromDevice.disconnect(receiver) }
        runCatching { fromDevice.close() }
        runCatching { toDevice.close() }
        runCatching { device.close() }
    }

    companion object {
        private val thread by lazy { HandlerThread("fm1-ota-midi").apply { start() } }

        /** open [info]'s first in and out ports, or null (busy, gone) */
        fun open(ctx: Context, info: MidiDeviceInfo): AndroidSysexLink? {
            if (info.inputPortCount == 0 || info.outputPortCount == 0) return null
            val mm = ctx.getSystemService(Context.MIDI_SERVICE) as MidiManager
            val h = Handler(thread.looper)
            val got = CountDownLatch(1)
            var dev: MidiDevice? = null
            mm.openDevice(info, { d -> dev = d; got.countDown() }, h)
            if (!got.await(3, TimeUnit.SECONDS)) return null
            val d = dev ?: return null
            val inPort = d.openInputPort(0)
            val outPort = d.openOutputPort(0)
            if (inPort == null || outPort == null) {
                runCatching { inPort?.close() }; runCatching { outPort?.close() }; runCatching { d.close() }
                return null
            }
            return AndroidSysexLink(mm, info, d, inPort, outPort, h)
        }

        fun nameOf(info: MidiDeviceInfo): String =
            info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)?.takeIf { it.isNotBlank() }
                ?: info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "USB MIDI"
    }
}

/**
 * The USB MIDI devices plugged in, each asked who it is (the update handshake: harmless, every FM-1 firmware and the
 * update loader answer it). Bluetooth MIDI is never used to flash (too slow, and a cut mid-way is likely). A device
 * that answers but does not pass the filter is closed again. While the FM-1 restarts (into the loader, back into its
 * firmware), the updater asks again every second: Android lists it anew each time.
 */
class AndroidOtaPorts(private val ctx: Context) : Fm1OtaPorts {
    @Suppress("DEPRECATION")
    override fun find(filter: (OtaIdentity) -> Boolean): OtaDevice? {
        val mm = ctx.getSystemService(Context.MIDI_SERVICE) as MidiManager
        for (info in mm.devices) {
            if (info.type != MidiDeviceInfo.TYPE_USB) continue
            val link = AndroidSysexLink.open(ctx, info) ?: continue
            val id = runCatching { Fm1Ota.handshake(link) }.getOrNull()
            if (id != null && filter(id)) return OtaDevice(link, id, AndroidSysexLink.nameOf(info))
            link.close()
        }
        return null
    }
}
