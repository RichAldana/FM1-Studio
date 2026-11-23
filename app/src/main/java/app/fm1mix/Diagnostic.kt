package app.fm1mix

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Everything needed to tell why the FM-1's sound does not reach the app, as plain text to copy and send:
 *  1. the phone (model, Android version) and the microphone permission;
 *  2. what the FM-1 itself declares over USB (read from its descriptors, no permission needed): an audio stream
 *     towards the phone (an isochronous IN endpoint) and one from it (OUT), and their packet sizes;
 *  3. every audio device Android's sound system offers, inputs and outputs, USB or not.
 * If the FM-1 declares an IN stream (2) but Android offers no USB input (3), the phone's sound system turned it down.
 */
object Diagnostic {
    private fun typeName(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "micro intégré"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "haut-parleur"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "écouteur"
        AudioDeviceInfo.TYPE_TELEPHONY -> "téléphonie"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB (appareil)"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB (casque)"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB (accessoire)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "casque filaire"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "écouteurs filaires"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote submix"
        else -> "type $t"
    }

    private fun subclass(s: Int): String = when (s) { 1 -> "contrôle"; 2 -> "flux audio"; 3 -> "MIDI"; else -> "sous-classe $s" }

    fun report(ctx: Context): String {
        val out = StringBuilder()
        out.append("Téléphone : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        val mic = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        out.append("Autorisation micro : ${if (mic) "accordée" else "REFUSÉE (la capture USB en a besoin)"}\n")

        // 2. the FM-1's own declaration
        out.append("\n— Ce que le FM-1 déclare en USB —\n")
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usb.deviceList.isEmpty()) out.append("Aucun appareil USB branché.\n")
        for (d in usb.deviceList.values) {
            val name = runCatching { "${d.manufacturerName ?: ""} ${d.productName ?: ""}".trim() }.getOrDefault("")
            out.append("« $name » %04x:%04x, %d interfaces\n".format(d.vendorId, d.productId, d.interfaceCount))
            var audioIn = false
            var audioOut = false
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                if (f.interfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
                val eps = (0 until f.endpointCount).map { f.getEndpoint(it) }
                val desc = eps.joinToString(", ") { e ->
                    val dir = if (e.direction == UsbConstants.USB_DIR_IN) "vers le téléphone" else "depuis le téléphone"
                    val kind = when (e.type) { UsbConstants.USB_ENDPOINT_XFER_ISOC -> "iso"; UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"; UsbConstants.USB_ENDPOINT_XFER_INT -> "int"; else -> "ctrl" }
                    "$kind $dir ${e.maxPacketSize} o"
                }
                if (f.interfaceSubclass == 2) {
                    if (eps.any { it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && it.direction == UsbConstants.USB_DIR_IN }) audioIn = true
                    if (eps.any { it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && it.direction == UsbConstants.USB_DIR_OUT }) audioOut = true
                }
                out.append("  interface ${f.id} alt ${f.alternateSetting} : ${subclass(f.interfaceSubclass)}${if (desc.isEmpty()) "" else " ($desc)"}\n")
            }
            out.append("  ⇒ son du FM-1 vers le téléphone : ${if (audioIn) "déclaré" else "NON déclaré"} ; son du téléphone vers le FM-1 : ${if (audioOut) "déclaré" else "non déclaré"}\n")
        }

        // 3. what Android's sound system offers
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for ((flag, title) in listOf(AudioManager.GET_DEVICES_INPUTS to "entrées", AudioManager.GET_DEVICES_OUTPUTS to "sorties")) {
            out.append("\n— Android, $title audio —\n")
            for (a in am.getDevices(flag)) {
                val ch = a.channelCounts.joinToString(",").ifEmpty { "?" }
                val sr = a.sampleRates.joinToString(",").ifEmpty { "?" }
                out.append("${typeName(a.type)} « ${a.productName} » canaux $ch, Hz $sr\n")
            }
        }
        out.append("\nPropriété UNPROCESSED : ${am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)}\n")
        return out.toString()
    }
}
