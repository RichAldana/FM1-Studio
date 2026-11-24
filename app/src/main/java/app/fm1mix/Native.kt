package app.fm1mix

/** JNI to libfm1mix.so (native/src/jni_api.rs). */
object Native {
    /** why libfm1mix.so didn't load (then nothing below may be called), or null */
    var loadError: String? = null
        private set

    init {
        try { System.loadLibrary("fm1mix") } catch (e: Throwable) { loadError = e.toString() }
    }

    /** Boots a .fwsc package. [flash] is an image saved by [stop] (or null). Returns "" or an error. */
    external fun start(pkg: ByteArray, flash: ByteArray?, mhz: Int, newChip: Boolean): String

    /** Stops the machine; returns the flash image (patches and settings) to keep, or null. */
    external fun stop(): ByteArray?

    external fun key(id: Int, down: Boolean)
    /** A note key (matrix id 14..40) pressed with a velocity 1..127. */
    external fun keyVel(id: Int, vel: Int)
    external fun enc(enc: Int, clicks: Int)
    external fun pot(value: Int)
    external fun midi(bytes: ByteArray, len: Int)

    /** 0 = clock/transport only, 1 = everything to MIDI IN, 2 = panel (needs a fm1-map JSON profile). "" or error. */
    external fun setMidiMode(mode: Int, profileJson: String?): String
    /** out = [messages received, last message as 0xSSD1D2] (clock and transport not counted). */
    external fun midiInfo(out: IntArray)

    /** Fills [out] (stereo interleaved, 44.1 kHz) with emulator + captured audio. */
    /** [gExt]: the real FM-1 in what plays; [gExtRec]: in what is recorded (both the same unless it plays on the FM-1). */
    external fun readMixed(out: ShortArray, gEmu: Float, gExt: Float, gExtRec: Float): Int
    external fun pushCapture(buf: ShortArray, n: Int)

    external fun lcd(out: ShortArray)        // 240*240 RGB565
    external fun leds(out: FloatArray)       // 41
    external fun meters(out: FloatArray)     // [emulated, real] (0..1), or with 4: [.., what plays, what is recorded] (above 1: it would clip)
    external fun status(): String
    /** Share of the time spent emulating, in percent; -1 stopped, -2 halted on an error (see status()). */
    external fun load(): Int
    /** [low]: small queues; [offsetMs] > 0 delays the real FM-1, < 0 the emulated one */
    external fun setLatency(low: Boolean, offsetMs: Int)
    external fun setLimiter(on: Boolean)
    /** What the firmware saved (its flash sectors), when it changed since the last call, else null. */
    external fun savedIfChanged(): ByteArray?
    /** The mixer: each source's balance (-1 left .. 1 right) and the master volume (1: as is). */
    external fun setMix(balEmu: Float, balExt: Float, master: Float)
    /** the knobs' speed: quarter detents per detent turned (1 = ¼×, 2 = ½×, 4 = 1×, 8 = 2×); [all]: SELECT,
     *  ALGORITHM and PRESETS too, not only KNOB 1-4 */
    external fun setKnobSpeed(quarters: Int, all: Boolean)
    /** the Android performance hint session (ADPF) for the engine thread, from the next start */
    external fun setHint(on: Boolean)
    /** An EQ's bands in dB (-12 .. 12): [source] 0 the emulated FM-1, 1 the real one, 2 the master. */
    external fun setEq(source: Int, low: Float, mid: Float, high: Float)
    /** The pad's effects (native/src/fx.rs): the profiles (assets/fx_profiles.json) loaded and every effect made; "" or the error. */
    external fun fxLoad(profilesJson: String): String
    /** the effects there are, in order: "id\tlabel\ttype" a line each */
    external fun fxList(): String
    /** the effect playing: its index in fxList, -1 none */
    external fun fxSelect(index: Int)
    /** the finger on the pad: x, y 0..1 (y = 0 at the bottom) */
    external fun fxTouch(x: Float, y: Float, down: Boolean)
    /** what it is on: 0 everything, 1 the emulated FM-1, 2 the real one */
    external fun fxTarget(target: Int)
    external fun fxHold(on: Boolean)
    external fun fxSetBpm(bpm: Float)
    /** a tap of TAP: the internal tempo from the taps */
    external fun fxTap(): Float
    /** [tempo, beat, 1 when it is the MIDI clock's] */
    external fun fxInfo(out: FloatArray)
    /** out = [the captured FM-1's queue (ms), its clock against the phone's (ppm), the limiter's reduction (dB)] */
    external fun audioInfo(out: FloatArray)

    // the FM-1's sound read straight from USB (native/src/usbin.rs, UsbAudioIn.kt)
    /** [interface, alt, endpoint, max packet, channels, bytes a sample, bits, rate] of the stream to the phone, or null. */
    external fun usbParse(raw: ByteArray): IntArray?
    /** Read the stream on [fd] (interface claimed, streaming setting selected) into the capture. "" or an error. */
    external fun usbStart(fd: Int, raw: ByteArray): String
    external fun usbStop()
    /** out = [samples, packets, bad packets, errno that stopped it, 1 while reading] */
    external fun usbStats(out: LongArray)

    // recording to WAV (native/src/recorder.rs, Recorder.kt)
    /** Record into [fdA] (the mix, or the emulated FM-1 when [fdB] >= 0) and [fdB] (the real FM-1). "" or an error. */
    external fun recStart(fdA: Int, fdB: Int, bits: Int, rate: Int, channels: Int): String
    /** "" or an error */
    external fun recStop(): String
    /** out = [frames at 44.1 kHz, blocks dropped, 1 while recording] */
    external fun recStats(out: LongArray)
}
