package app.fm1mix

import android.content.Context
import android.media.midi.*
import android.os.Handler

/**
 * Forwards every byte sent by every MIDI device plugged into the phone (the FM-1 over USB, a
 * controller...) to the native engine, which decides what to do with it (see Native.setMidiMode).
 * It only counts the MIDI clock ticks, to show whether a clock arrives.
 */
class MidiIn(ctx: Context) {
    @Volatile var lastClockMs = 0L
    @Volatile var clockCount = 0L
    /** OCT- (-1) or OCT+ (+1) pressed on a plugged FM-1 lending its panel (PANEL MIDI: note 0 / 1 on channel 15) */
    var onPanelOct: ((Int) -> Unit)? = null

    private val mm = ctx.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val devices = mutableListOf<MidiDevice>()
    private var callback: MidiManager.DeviceCallback? = null

    private val receiver = object : MidiReceiver() {
        private val tmp = ByteArray(1024)
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val n = minOf(count, tmp.size)
            System.arraycopy(msg, offset, tmp, 0, n)
            for (i in 0 until n) {
                if (tmp[i] == 0xF8.toByte()) { clockCount++; lastClockMs = System.currentTimeMillis() }
                else if (tmp[i] == 0x9E.toByte() && i + 2 < n && tmp[i + 2] > 0 && (tmp[i + 1] == 0.toByte() || tmp[i + 1] == 1.toByte()))
                    onPanelOct?.invoke(if (tmp[i + 1] == 0.toByte()) -1 else 1)
            }
            Native.midi(tmp, n)
        }
    }

    @Suppress("DEPRECATION")
    fun connect(handler: Handler) {
        mm.devices.forEach { open(it, handler) }
        val cb = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) = open(info, handler)
        }
        callback = cb
        mm.registerDeviceCallback(cb, handler)
    }

    private fun open(info: MidiDeviceInfo, handler: Handler) {
        if (info.outputPortCount == 0) return   // nothing sends to us
        mm.openDevice(info, { dev ->
            if (dev != null) {
                dev.openOutputPort(0)?.connect(receiver)
                synchronized(devices) { devices += dev }
            }
        }, handler)
    }

    fun close() {
        callback?.let { mm.unregisterDeviceCallback(it) }
        synchronized(devices) { devices.forEach { runCatching { it.close() } }; devices.clear() }
    }
}
