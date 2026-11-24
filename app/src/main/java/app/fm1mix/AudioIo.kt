package app.fm1mix

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper

/**
 * Output: one AudioTrack (44.1 kHz stereo) fed by Native.readMixed, sent where [target] says:
 *  - OUT_FM1: to the FM-1 over USB. Android picks it by itself as soon as a USB audio device is plugged in, and
 *    fm1-base (LOOM-8) plays what the phone sends on its speaker and headphones, mixed with its own sound. The FM-1's
 *    own sound is then NOT sent back to it (it would play twice, the second copy late).
 *  - OUT_PHONE: to the phone (Bluetooth headphones if connected, else wired headphones, else the speaker), with the
 *    emulated FM-1 and the captured real one mixed.
 * Input: an AudioRecord on the FM-1's USB audio device: channels 1 and 2 (the master, also with LOOM-8's 10 channels),
 * into Native.pushCapture; opened again when the FM-1 is plugged in after the start. When the phone offers no USB input
 * at all (a Pixel 8a), the FM-1 is read straight from USB instead (UsbAudioIn, native/src/usbin.rs).
 */
class AudioIo(private val ctx: Context) {
    companion object {
        const val OUT_FM1 = 0
        const val OUT_PHONE = 1
    }

    @Volatile var gEmu = 1f
    @Volatile var gExt = 1f
    /** Where the sound goes (OUT_FM1 / OUT_PHONE); set [target] then call [route] to apply it while running. */
    @Volatile var target = OUT_FM1
    @Volatile private var running = false
    private var track: AudioTrack? = null
    @Volatile private var rec: AudioRecord? = null
    private var outThread: Thread? = null
    private var inThread: Thread? = null
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    /** The FM-1 read straight from USB (UsbAudioIn): when Android offers no USB input, or [ownDriver]. */
    private val usbIn = UsbAudioIn(ctx)
    /**
     * While the sound plays on the phone, the FM-1 is read by the app's own driver even when Android offers it as an
     * input: a Pixel 8a offers it since Android 16, and its capture comes out crackling, metallic and muffled (the
     * app's driver, used on that phone before, was clean). Played on the FM-1, Android's input is kept: taking the
     * FM-1's audio from Android's driver could also take away its USB output.
     */
    @Volatile var ownDriver = true

    /** The capture again (the output changed: the driver to use may change with it). */
    fun restartCapture() { if (running) Thread { stopCapture(); startCapture() }.start() }
    private val main = Handler(Looper.getMainLooper())

    private val rate = 44100
    private val frames = 256

    /** Re-route when a device comes or goes (the FM-1 plugged in, Bluetooth headphones connected...). */
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            route()
            if (added.any { isUsb(it) }) Thread { startCapture() }.start()
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            route()
            if (removed.any { isUsb(it) }) {
                // the FM-1 really gone; claiming its audio interface must not stop what reads it
                if (usbIn.candidate() == null) { stopCapture(); captureState = tr("FM-1 débranché", "FM-1 unplugged") }
                else if (rec != null && removed.any { isUsb(it) && it.isSource }) { stopCapture(); captureState = tr("entrée USB retirée par Android", "USB input removed by Android") }
            }
        }
    }

    private fun isUsb(d: AudioDeviceInfo) =
        d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET

    /** the names of the plugged USB devices that are FM-1s (Fm1Id: identity, name, a MIDI interface) */
    private fun fm1Names(): List<String> {
        val um = ctx.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        return um.deviceList.values.filter { d ->
            val midi = (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == 1 && d.getInterface(it).interfaceSubclass == 3 }
            val name = runCatching { d.productName ?: "" }.getOrDefault("")
            Fm1Id.looksLikeFm1(d.vendorId, d.productId, name, midi)
        }.mapNotNull { d -> runCatching { d.productName }.getOrNull() }
    }

    /** a USB audio device that is the FM-1 (not a headset or a USB-C headphone output) */
    private fun isFm1(d: AudioDeviceInfo): Boolean = isUsb(d) && Fm1Id.isOneOf(d.productName?.toString() ?: "", fm1Names())

    /** The FM-1's USB audio input, if one is plugged in (a USB headset's microphone is never taken). */
    fun usbInput(): AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { isFm1(it) }

    /** The output for [t]: the FM-1's USB device, or the phone's best (Bluetooth, wired, then the speaker). */
    private fun outputFor(t: Int): AudioDeviceInfo? {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (t == OUT_FM1) return outs.firstOrNull { isFm1(it) }
        // on the phone: what is plugged in first (a USB headphone output, then wired: the least delay), then Bluetooth
        // (LE Audio before classic), then the speaker
        outs.firstOrNull { isUsb(it) && !isFm1(it) }?.let { return it }
        val order = intArrayOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )
        for (type in order) outs.firstOrNull { it.type == type }?.let { return it }
        return null
    }

    /** Apply [target] to the running output. */
    fun route() {
        val t = track ?: return
        t.setPreferredDevice(outputFor(target))
    }

    /** What Android sees of the FM-1 over USB, for a diagnosis: each USB audio device, its direction, channels, rates. */
    fun usbReport(): String {
        val lines = ArrayList<String>()
        for ((flag, dir) in listOf(AudioManager.GET_DEVICES_INPUTS to tr("entrée", "input"), AudioManager.GET_DEVICES_OUTPUTS to tr("sortie", "output"))) {
            for (d in am.getDevices(flag)) {
                if (!isUsb(d)) continue
                val ch = d.channelCounts.joinToString(",").ifEmpty { "?" }
                val sr = d.sampleRates.joinToString(",").ifEmpty { "?" }
                lines += tr("$dir USB « ${d.productName} » : canaux $ch, fréquences $sr", "$dir USB \"${d.productName}\": channels $ch, rates $sr")
            }
        }
        if (lines.isEmpty()) lines += tr("Android ne voit aucun appareil audio USB.", "Android sees no USB audio device.")
        if (lines.none { it.startsWith(tr("sortie", "output")) }) lines += tr("Pas de sortie USB : le FM-1 ne peut pas jouer le son du téléphone.", "No USB output: the FM-1 cannot play the phone's sound.")
        if (lines.none { it.startsWith(tr("entrée", "input")) }) lines += tr("Android ne propose pas d'entrée USB : l'appli lit le FM-1 avec son pilote intégré.", "Android offers no USB input: the app reads the FM-1 with its built-in driver.")
        lines += tr("Sortie actuelle : ${outputName()}", "Current output: ${outputName()}")
        return lines.joinToString("\n")
    }

    /** What the sound plays on now, for the screen. */
    fun outputName(): String {
        val d = track?.routedDevice ?: return "–"
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> tr("haut-parleur du téléphone", "phone speaker")
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth ${d.productName}"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE ${d.productName}"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> tr("casque filaire", "wired headphones")
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET ->
                if (isFm1(d)) "FM-1 (USB)" else tr("casque USB ${Fm1Id.bare(d.productName?.toString() ?: "")}", "USB headset ${Fm1Id.bare(d.productName?.toString() ?: "")}")
            else -> d.productName.toString()
        }
    }

    /** Raw audio when the phone offers it (no gain control, no noise reduction on the FM-1's sound), else
     *  VOICE_RECOGNITION: Android requires that one flat, without gain control or noise reduction. (MIC, before 0.28,
     *  let some phones treat the FM-1 as a voice: crackling, metallic, muffled.) */
    private fun captureSource(): Int =
        if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION

    private fun sourceName(s: Int) = if (s == MediaRecorder.AudioSource.UNPROCESSED) "UNPROCESSED" else "VOICE_RECOGNITION"

    /** what the phone may still put on the capture (noise suppression, gain control, echo cancelling): switched off,
     *  kept until the capture stops */
    private val plainFx = ArrayList<android.media.audiofx.AudioEffect>()
    private fun plainCapture(session: Int) {
        plainFx.forEach { runCatching { it.release() } }      // (a format tried before this one)
        plainFx.clear()
        runCatching { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(session)?.let { it.enabled = false; plainFx += it } }
        runCatching { if (android.media.audiofx.AutomaticGainControl.isAvailable()) android.media.audiofx.AutomaticGainControl.create(session)?.let { it.enabled = false; plainFx += it } }
        runCatching { if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(session)?.let { it.enabled = false; plainFx += it } }
    }

    fun start(allowCapture: Boolean): String {
        if (running) return "already running"
        val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(minOut, frames * 4 * 2))
            .build()
        track = t
        route()
        running = true
        t.play()
        am.registerAudioDeviceCallback(devices, main)
        outThread = Thread({
            val buf = ShortArray(frames * 2)
            while (running) {
                // to the FM-1: only the emulated one (the FM-1 already plays its own sound)
                val gx = if (target == OUT_FM1) 0f else gExt
                Native.readMixed(buf, gEmu, gx, gExt)   // (a recording keeps the real FM-1)
                t.write(buf, 0, buf.size)   // blocking: paces the loop
            }
        }, "fm1-out").also { it.priority = Thread.MAX_PRIORITY; it.start() }

        captureAllowed = allowCapture
        captureState = tr("capture : recherche du FM-1…", "capture: looking for the FM-1…")
        Thread { startCapture() }.start()            // (it may wait on Android's routing: not on the screen's thread)
        return captureState
    }

    @Volatile private var captureAllowed = false
    /** Samples (stereo, so 2 a frame) taken from the FM-1 since the start: the screen turns it into a rate. */
    @Volatile private var androidSamples = 0L
    val capturedSamples: Long get() = androidSamples + usbIn.samples()
    /** What the capture is doing, for the screen (the built-in driver's own state while it is the one reading). */
    fun captureStatus(): String = if (usbIn.active) usbIn.status() else captureState

    /** What the capture was last told (see captureStatus). */
    @Volatile var captureState = ""
        private set

    /** Open the FM-1's USB input with the first format that works (CaptureFormat.tries), and read it. */
    @Synchronized
    fun startCapture(): String {
        if (!running || rec != null || usbIn.active) return captureStatus()
        if (!captureAllowed) { captureState = tr("capture non autorisée", "capture not allowed"); return captureState }
        val own = ownDriver && target == OUT_PHONE && usbIn.candidate() != null
        val dev = usbInput()
        if (dev == null || own) {                     // read the FM-1 ourselves (Android offers no USB input, or [ownDriver])
            captureState = usbIn.start()
            // (ours failed, and Android has an input: Android's, below)
            if (usbIn.active || usbIn.waiting || dev == null) return captureState
        }
        val errors = ArrayList<String>()
        for (t in CaptureFormat.tries(dev.channelCounts)) {
            val fmt = AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            if (t.indexMask) fmt.setChannelIndexMask(CaptureFormat.indexMask(t.channels))
            else fmt.setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            val r = try {
                AudioRecord.Builder()
                    .setAudioSource(captureSource())
                    .setAudioFormat(fmt.build())
                    .setBufferSizeInBytes(frames * 2 * t.channels * 8)
                    .build()
            } catch (e: Exception) {
                errors += tr("$t : ${e.message}", "$t: ${e.message}")
                continue
            }
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); errors += tr("$t : refusé", "$t: refused"); continue }
            r.setPreferredDevice(dev)
            plainCapture(r.audioSessionId)
            try { r.startRecording() } catch (e: Exception) { r.release(); errors += tr("$t : ${e.message}", "$t: ${e.message}"); continue }
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); errors += tr("$t : ne démarre pas", "$t: does not start"); continue }
            // Android may fall back on the phone's microphone when the USB driver refuses a format: then try the next
            var routed = r.routedDevice
            var waited = 0
            while (routed == null && waited < 400) { Thread.sleep(20); waited += 20; routed = r.routedDevice }
            if (routed != null && !isUsb(routed)) {
                runCatching { r.stop() }; r.release()
                errors += tr("$t : pris sur ${routed.productName}", "$t: taken from ${routed.productName}")
                continue
            }
            rec = r
            val n = t.channels
            inThread = Thread({
                val raw = ShortArray(frames * n)
                val st = ShortArray(frames * 2)
                while (running && rec === r) {
                    val got = r.read(raw, 0, raw.size)   // blocking
                    if (got < 0) { captureState = tr("capture : erreur de lecture $got", "capture: read error $got"); break }
                    val m = CaptureFormat.firstTwo(raw, got, n, st)
                    if (m > 0) { Native.pushCapture(st, m); androidSamples += m }
                }
            }, "fm1-in").also { it.priority = Thread.MAX_PRIORITY; it.start() }
            captureState = "capture $t · " + sourceName(captureSource())
            return captureState
        }
        captureState = tr("capture impossible (", "capture failed (") + errors.joinToString(" ; ") + ")"
        return captureState
    }

    @Synchronized
    private fun stopCapture() {
        usbIn.stop()
        val r = rec ?: return
        rec = null                                   // the reading thread ends on its next turn
        runCatching { r.stop() }                     // (and a blocked read returns)
        runCatching { inThread?.join(500) }
        runCatching { r.release() }
        plainFx.forEach { runCatching { it.release() } }
        plainFx.clear()
        inThread = null
    }

    fun stop() {
        running = false
        runCatching { am.unregisterAudioDeviceCallback(devices) }
        runCatching { outThread?.join(500) }
        stopCapture()
        runCatching { track?.stop(); track?.release() }
        track = null; outThread = null
    }
}
