package app.fm1mix

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat

/**
 * The FM-1's sound read straight from USB, when the phone's sound system does not offer it as an input (a Pixel 8a
 * offers the FM-1 as an output only). Android asks once for access to the FM-1; then its audio streaming interface
 * (the one sending to the phone) is claimed from Android's driver, its streaming setting selected, and the native
 * reader (native/src/usbin.rs) takes the packets. The MIDI interface and the stream from the phone are left alone.
 */
class UsbAudioIn(private val ctx: Context) {
    companion object {
        const val ACTION = "app.fm1mix.USB_PERMISSION"
    }

    private val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private var conn: UsbDeviceConnection? = null
    private var claimed: UsbInterface? = null
    private var idle: UsbInterface? = null
    @Volatile private var asking = false
    @Volatile var active = false
        private set
    /** waiting for the user's answer to Android's access window */
    val waiting get() = asking
    @Volatile private var state = ""
    private val stats = LongArray(5)

    /** A plugged FM-1 (Fm1Id) with an audio stream towards the phone: an isochronous IN endpoint in an audio streaming interface. */
    fun candidate(): UsbDevice? = um.deviceList.values.firstOrNull { d ->
        val midi = (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO && d.getInterface(it).interfaceSubclass == 3 }
        // an FM-1 only: a USB headset's microphone is never claimed
        Fm1Id.looksLikeFm1(d.vendorId, d.productId, runCatching { d.productName ?: "" }.getOrDefault(""), midi) &&
        (0 until d.interfaceCount).any { i ->
            val f = d.getInterface(i)
            f.interfaceClass == UsbConstants.USB_CLASS_AUDIO && f.interfaceSubclass == 2 &&
                (0 until f.endpointCount).any { e ->
                    val ep = f.getEndpoint(e)
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && ep.direction == UsbConstants.USB_DIR_IN
                }
        }
    }

    /** Start reading, asking for access first if needed. Returns what it is doing, for the screen. */
    @Synchronized
    fun start(): String {
        if (active) return status()
        val d = candidate() ?: run { state = tr("pas d'entrée audio USB", "no USB audio input"); return state }
        if (!um.hasPermission(d)) {
            askPermission(d)
            state = tr("accepte l'accès au FM-1 dans la fenêtre d'Android", "allow access to the FM-1 in Android's window")
            return state
        }
        return open(d)
    }

    private fun askPermission(d: UsbDevice) {
        if (asking) return
        asking = true
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { ctx.unregisterReceiver(this) }
                asking = false
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) Thread { start() }.start()
                else state = tr("accès au FM-1 refusé (rebranche-le pour redemander)", "access to the FM-1 refused (plug it again to ask again)")
            }
        }
        ContextCompat.registerReceiver(ctx, rcv, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        // mutable: Android adds the device and the answer to it; explicit (our package): required for a mutable one
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION).setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE)
        um.requestPermission(d, pi)
    }

    private fun fail(c: UsbDeviceConnection?, why: String): String {
        runCatching { c?.close() }
        state = tr("pilote intégré : $why", "built-in driver: $why")
        return state
    }

    private fun open(d: UsbDevice): String {
        val c = um.openDevice(d) ?: return fail(null, tr("le FM-1 ne s'ouvre pas", "the FM-1 does not open"))
        val raw = c.rawDescriptors ?: return fail(c, tr("description USB illisible", "unreadable USB description"))
        val p = Native.usbParse(raw) ?: return fail(c, tr("aucun flux audio vers le téléphone", "no audio stream to the phone"))
        val ifNum = p[0]
        val alt = p[1]
        val ep = p[2]
        val all = (0 until d.interfaceCount).map { d.getInterface(it) }
        val intf = all.firstOrNull { it.id == ifNum && it.alternateSetting == alt }
            ?: return fail(c, tr("interface $ifNum alt $alt introuvable", "interface $ifNum alt $alt not found"))
        // take the interface from Android's driver (force), then select the setting that streams
        if (!c.claimInterface(intf, true)) return fail(c, tr("l'interface audio $ifNum ne se libère pas", "audio interface $ifNum does not free up"))
        if (!c.setInterface(intf)) {
            c.releaseInterface(intf)
            return fail(c, tr("le réglage $alt de l'interface $ifNum est refusé", "setting $alt of interface $ifNum refused"))
        }
        // USB Audio Class 1: the endpoint's sampling rate (the FM-1 has a single one: harmless if refused)
        val rate = p[7]
        if (rate > 0) {
            val b = byteArrayOf(rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte())
            c.controlTransfer(0x22, 0x01, 0x0100, ep, b, 3, 200)
        }
        val err = Native.usbStart(c.fileDescriptor, raw)
        if (err.isNotEmpty()) {
            c.releaseInterface(intf)
            return fail(c, tr("lecture : $err", "read: $err"))
        }
        conn = c
        claimed = intf
        idle = all.firstOrNull { it.id == ifNum && it.alternateSetting == 0 }
        active = true
        state = tr("capture pilote intégré (${p[4]} canaux, ${p[6]} bits, ${p[7]} Hz)", "capture built-in driver (${p[4]} channels, ${p[6]} bits, ${p[7]} Hz)")
        return state
    }

    /** Stereo samples (2 a frame) taken since the start. */
    fun samples(): Long {
        if (!active) return 0
        Native.usbStats(stats)
        return stats[0]
    }

    /** What the reader is doing; a reader that stopped on its own (the FM-1 unplugged) is cleaned up here. */
    fun status(): String {
        if (!active) return state
        Native.usbStats(stats)
        if (stats[4] == 0L) {
            val e = stats[3]
            stop()
            state = if (e == 19L) tr("FM-1 débranché", "FM-1 unplugged") else tr("pilote intégré arrêté (erreur $e)", "built-in driver stopped (error $e)")
            return state
        }
        val bad = stats[2]
        return state + if (bad > 0) tr(" · $bad paquets en erreur", " · $bad packets in error") else ""
    }

    @Synchronized
    fun stop() {
        if (!active && conn == null) return
        Native.usbStop()
        val c = conn
        if (c != null) {
            idle?.let { runCatching { c.setInterface(it) } }   // the stream stopped at the device
            claimed?.let { runCatching { c.releaseInterface(it) } }
            runCatching { c.close() }
        }
        conn = null
        claimed = null
        idle = null
        active = false
    }
}
