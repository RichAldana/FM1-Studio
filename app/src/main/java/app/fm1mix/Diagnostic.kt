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
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> tr("micro intégré", "built-in mic")
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> tr("haut-parleur", "speaker")
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> tr("écouteur", "earpiece")
        AudioDeviceInfo.TYPE_TELEPHONY -> tr("téléphonie", "telephony")
        AudioDeviceInfo.TYPE_USB_DEVICE -> tr("USB (appareil)", "USB (device)")
        AudioDeviceInfo.TYPE_USB_HEADSET -> tr("USB (casque)", "USB (headset)")
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> tr("USB (accessoire)", "USB (accessory)")
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> tr("casque filaire", "wired headphones")
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> tr("écouteurs filaires", "wired earphones")
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote submix"
        else -> "type $t"
    }

    private fun subclass(s: Int): String = when (s) { 1 -> tr("contrôle", "control"); 2 -> tr("flux audio", "audio stream"); 3 -> "MIDI"; else -> tr("sous-classe $s", "subclass $s") }

    fun report(ctx: Context): String {
        val out = StringBuilder()
        out.append(tr("Téléphone : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n", "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n"))
        val mic = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        out.append(tr("Autorisation micro : ${if (mic) "accordée" else "REFUSÉE (la capture USB en a besoin)"}\n", "Microphone permission: ${if (mic) "granted" else "REFUSED (USB capture needs it)"}\n"))

        // 2. the FM-1's own declaration
        out.append(tr("\n— Ce que le FM-1 déclare en USB —\n", "\n— What the FM-1 declares over USB —\n"))
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usb.deviceList.isEmpty()) out.append(tr("Aucun appareil USB branché.\n", "No USB device plugged in.\n"))
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
                    val dir = if (e.direction == UsbConstants.USB_DIR_IN) tr("vers le téléphone", "to the phone") else tr("depuis le téléphone", "from the phone")
                    val kind = when (e.type) { UsbConstants.USB_ENDPOINT_XFER_ISOC -> "iso"; UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"; UsbConstants.USB_ENDPOINT_XFER_INT -> "int"; else -> "ctrl" }
                    "$kind $dir ${e.maxPacketSize} o"
                }
                if (f.interfaceSubclass == 2) {
                    if (eps.any { it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && it.direction == UsbConstants.USB_DIR_IN }) audioIn = true
                    if (eps.any { it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && it.direction == UsbConstants.USB_DIR_OUT }) audioOut = true
                }
                out.append(tr("  interface ${f.id} alt ${f.alternateSetting} : ${subclass(f.interfaceSubclass)}${if (desc.isEmpty()) "" else " ($desc)"}\n", "  interface ${f.id} alt ${f.alternateSetting}: ${subclass(f.interfaceSubclass)}${if (desc.isEmpty()) "" else " ($desc)"}\n"))
            }
            out.append(tr("  ⇒ son du FM-1 vers le téléphone : ${if (audioIn) "déclaré" else "NON déclaré"} ; son du téléphone vers le FM-1 : ${if (audioOut) "déclaré" else "non déclaré"}\n", "  ⇒ FM-1 sound to the phone: ${if (audioIn) "declared" else "NOT declared"}; phone sound to the FM-1: ${if (audioOut) "declared" else "not declared"}\n"))
        }

        // 3. what Android's sound system offers
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for ((flag, title) in listOf(AudioManager.GET_DEVICES_INPUTS to tr("entrées", "inputs"), AudioManager.GET_DEVICES_OUTPUTS to tr("sorties", "outputs"))) {
            out.append(tr("\n— Android, $title audio —\n", "\n— Android, audio $title —\n"))
            for (a in am.getDevices(flag)) {
                val ch = a.channelCounts.joinToString(",").ifEmpty { "?" }
                val sr = a.sampleRates.joinToString(",").ifEmpty { "?" }
                out.append(tr("${typeName(a.type)} « ${a.productName} » canaux $ch, Hz $sr\n", "${typeName(a.type)} \"${a.productName}\" channels $ch, Hz $sr\n"))
            }
        }
        out.append(tr("\nPropriété UNPROCESSED : ${am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)}\n", "\nUNPROCESSED property: ${am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)}\n"))
        return out.toString()
    }
}
