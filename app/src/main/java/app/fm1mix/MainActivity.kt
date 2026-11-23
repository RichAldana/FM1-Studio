package app.fm1mix

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.security.MessageDigest

/**
 * One screen: a top bar (firmware ▾, slicer, record, power, MIDI light, CPU, menu) over two zones that each show
 * one page at a time, a swipe from their edge to change (Zones.kt): the middle one the FM-1's screen, encoders and
 * buttons, the recorder (RecorderView) or the effects (FxPadView: two thirds pad, a third to choose); the bottom
 * one the keyboard or the mixer (MixerView). The first time, no firmware: the user picks one in the catalogue; then the machine powers
 * on by itself with the last one chosen. Menus are bottom sheets (Sheet.kt).
 */
class MainActivity : ComponentActivity() {
    private lateinit var panel: PanelView
    private lateinit var fwName: TextView
    private lateinit var powerBtn: PowerButton
    private lateinit var keysPanel: PanelView
    private lateinit var recView: RecorderView
    private lateinit var mixView: MixerView
    private val m4 = FloatArray(4)
    private val statPeak = FloatArray(2)
    private lateinit var recBtn: TextView
    private val recorder by lazy { Recorder(this) }
    private lateinit var midiDot: View
    private lateinit var midiBox: FrameLayout
    private lateinit var midiLabel: TextView
    /** the plugged FM-1's panel lent to the emulated one (LOOM-8's PANEL MIDI) */
    private val panelRemote by lazy { PanelRemote(this) { onPanel(it) } }
    /** the MIDI mode and profile before the panel came, put back when it goes */
    private var beforePanel: Pair<Int, String>? = null
    private lateinit var cpu: TextView
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val audio by lazy { AudioIo(this) }
    private val midi by lazy { MidiIn(this) }
    private val prefs by lazy { getSharedPreferences("fm1mix", MODE_PRIVATE) }

    /** "cat:<id>" for a firmware of the catalogue (downloaded into the app), "file:<name>" for one imported. */
    private var firmware = DEFAULT_FW
    private val store by lazy { FirmwareStore(File(filesDir, "firmware")) { assets.open("firmwares-fm1emu.json").use { it.readBytes().toString(Charsets.UTF_8) } } }
    @Volatile private var catalog: FirmwareCatalog.Catalog? = null
    @Volatile private var downloading: String? = null
    private var downloadPct = -1
    private var sheet: Sheet? = null
    private var running = false
    private var booting = false
    private var flashFile: File? = null
    private var mhz = 240
    private var newChip = false
    private var audioMsg = ""
    private var midiMode = 0                       // 0 clock only, 1 thru, 2 panel
    private var profileName = "remotefm1-panel.json"
    private var modeMsg = ""
    private var gEmuPct = 100
    private var gExtPct = 100
    private var balEmuPct = 0                       // -100 left .. 100 right
    private var balExtPct = 0
    private var masterPct = 100
    /** the EQs' bands in dB: [source * 3 + band], sources EQ_EMU, EQ_EXT, EQ_MASTER, bands low, mid, high */
    private val eqDb = IntArray(9)
    private var outTarget = AudioIo.OUT_FM1
    private var lowLatency = false
    private var offsetMs = 0                        // > 0: the real FM-1 later; < 0: the emulated one later
    private var limiter = true
    private val ai = FloatArray(3)
    private var lastMidiCount = 0
    private var lastCaptured = 0L
    private var lastCaptureMs = 0L
    private var lastClock = 0L
    private val px = ShortArray(240 * 240)
    private val leds = FloatArray(41)
    private val mt = FloatArray(2)
    private val mi = IntArray(2)

    companion object {
        /** none: the user picks the first firmware (then the last one chosen comes back) */
        const val DEFAULT_FW = ""
        /** the emulated clock follows the firmware's measured minimum (with a margin) */
        const val MHZ_AUTO = 0
        const val PANEL_PROFILE = "remotefm1-panel.json"
        val AMBER = 0xFFF29B38.toInt()
        val INK = 0xFFD9D6CE.toInt()
        val DIM = 0xFF8E8C87.toInt()
    }

    private val importFw = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@registerForActivityResult
            val name = (uri.lastPathSegment ?: "firmware.fwsc").substringAfterLast('/').substringAfterLast(':')
            val dir = File(filesDir, "firmware").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
            selectFirmware("file:$name")
        }
    }
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (running) { audio.stop(); audioMsg = audio.start(ok) }
    }
    private val pickProfile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val txt = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@registerForActivityResult
            val dir = File(filesDir, "profiles").apply { mkdirs() }
            val name = (uri.lastPathSegment ?: "profil.json").substringAfterLast('/').let { if (it.endsWith(".json")) it else "$it.json" }
            File(dir, name).writeText(txt)
            profileName = name
            fwPutStr("profile", name)
            applyMidiMode()
        }
    }

    // ------------------------------------------------------------------ screen

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        firmware = prefs.getString("firmware", DEFAULT_FW) ?: DEFAULT_FW
        if (firmware.startsWith("asset:")) firmware = "cat:chordism"      // (0.9 and before: Chordism built in)
        loadFirmwarePrefs()
        newChip = prefs.getBoolean("newChip", false)
        gExtPct = prefs.getInt("gExt", 100)
        balExtPct = prefs.getInt("balExt", 0)
        masterPct = prefs.getInt("master", 100)
        for (i in 3 until 9) eqDb[i] = prefs.getInt("eq$i", 0)
        outTarget = prefs.getInt("out", AudioIo.OUT_FM1)
        lowLatency = prefs.getBoolean("lowLatency", false)
        offsetMs = prefs.getInt("offsetMs", 0)
        limiter = prefs.getBoolean("limiter", true)
        recorder.mode = prefs.getInt("recMode", Recorder.MODE_MIX)
        recorder.bits = prefs.getInt("recBits", 16)
        recorder.rate = prefs.getInt("recRate", 44100)
        recorder.channels = prefs.getInt("recCh", 2)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF3A3C41.toInt()) }
        root.addView(topBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            setMargins(dp(10), dp(8), dp(10), 0)
        })
        status = TextView(this).apply {
            textSize = 11f; setTextColor(DIM); maxLines = 2; setPadding(dp(18), dp(4), dp(18), 0)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(status)
        panel = PanelView(this, PanelView.PART_TOP)
        panel.potValue = fwInt("pot", 1023)
        panel.onPot = { fwPut("pot", it) }
        keysPanel = PanelView(this, PanelView.PART_KEYS)
        recView = RecorderView(this).apply {
            onRec = { toggleRecord() }
            onSlice = { openSlicer(recorder.lastKept.firstOrNull()) }
        }
        mixView = MixerView(this).apply {
            onVol = { s, v -> setVolume(s, v) }
            onEq = { s, b, db -> eqDb[3 * s + b] = db; putEq(3 * s + b, db); applyMix() }
        }
        loadEffects()
        val mid = SwipeZone(this, listOf(panel, recView, FxPadView(this)), listOf("FM-1", "Enregistreur", "Effets"),
            prefs.getInt("pageMid", 0)) { prefs.edit().putInt("pageMid", it).apply() }
        val bottom = SwipeZone(this, listOf(keysPanel, mixView), listOf("Clavier", "Mixeur et EQ"),
            prefs.getInt("pageBottom", 0).coerceIn(0, 1)) { prefs.edit().putInt("pageBottom", it).apply() }
        root.addView(Zones(this, mid, bottom, PanelLayout.TOP_H / PanelLayout.REF_W + 0.02f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        syncMixer()
        recView.info = recInfo()
        // Android 15 draws apps under the status and navigation bars: keep the bar and the panel clear of them
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(root)
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(4), 0)
            background = pill(0xFF1E1F22.toInt(), 0, dp(18))
        }
        fwName = TextView(this).apply {
            textSize = 19f; setTextColor(Color.WHITE); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { openCatalog() }
        }
        bar.addView(fwName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "✂"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = pill(0, 0xFF4A4B50.toInt(), dp(21))
            setOnClickListener { openSlicer(null) }
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(dp(6), 0, 0, 0) })
        recBtn = TextView(this).apply {
            text = "●"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(0xFFE5534B.toInt())
            background = pill(0, 0xFF4A4B50.toInt(), dp(21))
            setOnClickListener { toggleRecord() }
        }
        bar.addView(recBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { setMargins(dp(6), 0, 0, 0) })
        recBtn.minWidth = dp(42)
        recBtn.setPadding(dp(8), 0, dp(8), 0)
        powerBtn = PowerButton(this).apply { setOnClickListener { togglePower() } }
        bar.addView(powerBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(dp(6), 0, dp(6), 0) })
        midiDot = View(this).apply { background = pill(0xFF4A4B50.toInt(), 0, dp(6)) }
        midiLabel = TextView(this).apply { text = "MIDI"; textSize = 10f; setTextColor(DIM); gravity = Gravity.CENTER }
        midiBox = FrameLayout(this).apply {
            background = pill(0, 0xFF4A4B50.toInt(), dp(14))
            addView(midiLabel, FrameLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.START; leftMargin = dp(4) })
            addView(midiDot, FrameLayout.LayoutParams(dp(10), dp(10)).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; rightMargin = dp(10) })
            setOnClickListener { openSettings(midiOnly = true) }
            // the shortcut: the plugged FM-1's controls to the emulated one, or back
            setOnLongClickListener { togglePanel(); true }
        }
        bar.addView(midiBox, LinearLayout.LayoutParams(dp(72), dp(34)))
        cpu = TextView(this).apply {
            textSize = 15f; setTextColor(0xFFFF8F8F.toInt()); gravity = Gravity.CENTER
            background = pill(0, 0xFF4A4B50.toInt(), dp(14))
            text = "–"
        }
        bar.addView(cpu, LinearLayout.LayoutParams(dp(62), dp(34)).apply { setMargins(dp(6), 0, 0, 0) })
        bar.addView(TextView(this).apply {
            text = "⋮"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            contentDescription = "Réglages"
            setOnClickListener { openSettings() }
        }, LinearLayout.LayoutParams(dp(40), dp(48)))
        return bar
    }

    // ------------------------------------------------------------------ power

    override fun onStart() {
        super.onStart()
        midi.connect(ui)
        panelRemote.start()
        // the machine is switched on with the app (and back on when the app comes back), unless the user turned it off
        if (prefs.getBoolean("power", true)) powerOn() else showPower()
    }

    override fun onStop() {
        powerOff()
        panelRemote.stop()
        midi.close()
        super.onStop()
    }

    private fun togglePower() {
        val on = !(running || booting)
        prefs.edit().putBoolean("power", on).apply()
        if (on) powerOn() else powerOff()
    }

    private fun cat(): FirmwareCatalog.Catalog = catalog ?: store.catalog().also { catalog = it }
    private fun entryOf(fw: String): FirmwareCatalog.Entry? =
        if (fw.startsWith("cat:")) cat().entries.firstOrNull { it.id == fw.removePrefix("cat:") } else null

    private fun firmwareBytes(fw: String): ByteArray? = runCatching {
        if (fw.startsWith("cat:")) store.installed(fw.removePrefix("cat:"))!!.file.readBytes()
        else File(File(filesDir, "firmware"), fw.removePrefix("file:")).readBytes()
    }.getOrNull()

    private fun firmwareLabel(fw: String): String =
        if (fw.isEmpty()) "CHOISIR UN FIRMWARE"
        else if (fw.startsWith("cat:")) (entryOf(fw)?.name ?: fw.removePrefix("cat:")).uppercase()
        else fw.substringAfter(':').removeSuffix(".fwsc").replace('-', ' ').uppercase()

    /** the clock the firmware runs at: the one chosen, or its measured minimum with a margin (less work for the phone) */
    private fun clockFor(fw: String): Int {
        if (mhz != MHZ_AUTO) return mhz
        val m = entryOf(fw)?.minMhz ?: return 240
        return minOf(240, maxOf(80, (m * 5 / 4 + 9) / 10 * 10))
    }

    /** Download [e] off the screen's thread, then [then] on it with an error or null. */
    private fun fetch(e: FirmwareCatalog.Entry, then: (String?) -> Unit) {
        if (downloading != null) return then("un téléchargement est déjà en cours")
        downloading = e.id
        downloadPct = 0
        onDownload()
        Thread {
            val r = runCatching {
                store.download(e, { k, total ->
                    val size = e.size
                    val pct = if (total > 0) (k * 100 / total).toInt() else if (size != null && size > 0) (k * 100 / size).toInt() else -1
                    if (pct != downloadPct) { downloadPct = pct; ui.post { onDownload() } }
                })
            }
            ui.post {
                downloading = null
                downloadPct = -1
                onDownload()
                then(r.exceptionOrNull()?.let { it.message ?: "échec du téléchargement" })
            }
        }.start()
    }

    /** a download moved on: the status line, and the catalogue if it is open */
    private fun onDownload() {
        val d = downloading
        if (d != null) status.text = "Téléchargement de ${entryOf("cat:$d")?.name ?: d}… " + if (downloadPct >= 0) "$downloadPct %" else ""
        if (catalogList != null) renderCatalogList()
    }

    private fun powerOn() {
        if (running || booting) return
        if (firmware.isEmpty()) {                     // none chosen yet: the catalogue, to pick the first one
            showPower()
            status.text = "Choisis un firmware : il démarrera, puis reviendra à chaque ouverture."
            ui.post { if (sheet == null) openCatalog() }
            return
        }
        val bytes = firmwareBytes(firmware)
        if (bytes == null) {
            val e = entryOf(firmware)
            if (e != null && e.pkg != null) {           // a catalogue firmware not on the phone yet: fetch it, then start
                showPower()
                fetch(e) { err -> if (err == null) powerOn() else status.text = "Téléchargement impossible : $err (réseau ?). Choisis un firmware : touche son nom en haut." }
            } else {
                status.text = "Firmware introuvable : choisis-en un dans le catalogue"
                openCatalog()
            }
            return
        }
        val state = stateFile()
        flashFile = state
        // (before 0.15: the whole flash image, kept by the package's hash: the same package only)
        val key = MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
        val flash = if (state.exists()) state else File(filesDir, "flash-$key.bin")
        booting = true
        showPower()
        status.text = "Démarrage de ${firmwareLabel(firmware)}…"
        val chosenMhz = clockFor(firmware)
        val chip = newChip
        Thread {
            val err = Native.start(bytes, flash.takeIf { it.exists() }?.readBytes(), chosenMhz, chip)
            ui.post {
                booting = false
                if (err.isNotEmpty()) {
                    status.text = "Échec du démarrage : $err"
                    showPower()
                    return@post
                }
                running = true
                Native.setLatency(lowLatency, offsetMs)
                Native.setLimiter(limiter)
                applyMix()
                FxState.send()
                Native.pot(panel.potValue)
                applyMidiMode()
                val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                audio.gEmu = gEmuPct / 100f
                audio.gExt = gExtPct / 100f
                audio.target = outTarget
                audioMsg = audio.start(micOk)
                if (!micOk) askMic.launch(Manifest.permission.RECORD_AUDIO)
                showPower()
                ui.removeCallbacks(tick)
                ui.post(tick)
                refreshCatalog(false)
            }
        }.start()
    }

    /** read the lists again (at most once a day unless [force]); [done] with what happened */
    private fun refreshCatalog(force: Boolean, done: ((String) -> Unit)? = null) {
        Thread {
            val msg = runCatching { store.refresh(force) }.getOrElse { it.message ?: "erreur" }
            val c = runCatching { store.catalog() }.getOrNull()
            ui.post {
                if (c != null) catalog = c
                showPower()
                done?.invoke(msg)
            }
        }.start()
    }

    private fun powerOff() {
        ui.removeCallbacks(tick)
        if (recorder.recording) {                    // (the sound stops with the machine: keep what was recorded)
            recorder.stop()
            val where = recorder.keep(recorder.defaultName)
            Toast.makeText(this, "Enregistré : $where", Toast.LENGTH_LONG).show()
            recView.hasTake = true
            showRec()
        }
        if (running) {
            panel.releaseAll()
            keysPanel.releaseAll()
            Native.fxTouch(0.5f, 0.5f, false)
            running = false
            audio.stop()
            Native.stop()?.let { f -> flashFile?.let { writeState(it, f) } }
        }
        panel.clearScreen()
        keysPanel.clearScreen()
        cpu.text = "–"
        status.text = if (prefs.getBoolean("power", true)) "" else "Éteint"
        showPower()
    }

    private fun showPower() {
        val on = running || booting
        fwName.text = firmwareLabel(firmware) + " ▾"
        panel.powered = running
        keysPanel.powered = running
        powerBtn.on = on
    }

    private fun selectFirmware(fw: String) {
        sheet?.close()
        powerOff()
        firmware = fw
        prefs.edit().putString("firmware", fw).putBoolean("power", true).apply()
        loadFirmwarePrefs()
        audio.gEmu = gEmuPct / 100f
        syncMixer()
        powerOn()
    }

    // ------------------------------------------------------------------ each firmware as it was left

    /*
     * Each firmware comes back as it was left, after a restart of the app or another firmware in between:
     *  - what the firmware saved itself (its patches, presets, settings, projects: on the emulated flash, as on a
     *    real FM-1): its own file in state/, written a second after the firmware writes its flash (Native.savedIfChanged:
     *    a crash or a killed app loses a second of it at most) and when it stops. A newer version of the same
     *    firmware boots with it, as a real FM-1 keeps its data through an update;
     *  - the app's settings that depend on the firmware: its clock, what MIDI does to it and the profile, its volume,
     *    balance and EQ in the mixer, the screen's MASTER: prefs "name@firmware" (the global ones before 0.15 as the
     *    starting point).
     * What the firmware has not saved (an edit not yet saved: Chordism's "*") lives in the emulated RAM, which is not
     * kept: as on a real FM-1 switched off.
     */
    private fun fwKey(name: String, fw: String = firmware) = "$name@$fw"
    private fun fwInt(name: String, def: Int) = prefs.getInt(fwKey(name), prefs.getInt(name, def))
    private fun fwPut(name: String, v: Int) = prefs.edit().putInt(fwKey(name), v).apply()
    private fun fwPutStr(name: String, v: String) = prefs.edit().putString(fwKey(name), v).apply()

    private fun loadFirmwarePrefs() {
        midiMode = fwInt("midiMode", 0)
        profileName = prefs.getString(fwKey("profile"), prefs.getString("profile", PANEL_PROFILE)) ?: PANEL_PROFILE
        mhz = fwInt("mhz", 240)
        gEmuPct = fwInt("gEmu", 100)
        balEmuPct = fwInt("balEmu", 0)
        for (i in 0 until 3) eqDb[i] = fwInt("eq$i", 0)
        if (::panel.isInitialized) panel.potValue = fwInt("pot", 1023)
        beforePanel = null
    }

    /** the firmware's own saved state (state.rs), one file per firmware */
    private fun stateFile(fw: String = firmware) =
        File(File(filesDir, "state").apply { mkdirs() }, fw.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".fm1s")

    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** written whole or not at all (a temporary file, then renamed) */
    private fun writeState(f: File, data: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        runCatching { tmp.writeBytes(data); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) } }
    }

    /** the tick's: what the firmware saved since, to its file (off the screen's thread) */
    private fun saveStateIfChanged() {
        val f = stateFile()
        saver.execute { Native.savedIfChanged()?.let { writeState(f, it) } }
    }

    /** Forget what this firmware saved (it starts as new), after a question. */
    private fun forgetState(fw: String) {
        val f = stateFile(fw)
        android.app.AlertDialog.Builder(this)
            .setTitle("Effacer la mémoire de ${firmwareLabel(fw)} ?")
            .setMessage("Ses sons, réglages et projets enregistrés sur le FM-1 émulé sont effacés : il repart comme neuf. Les réglages de l'appli (mixeur, MIDI, horloge) restent.")
            .setPositiveButton("Effacer") { _, _ ->
                val was = running && firmware == fw
                if (was) { ui.removeCallbacks(tick); running = false; audio.stop(); Native.stop() }   // (not saved again)
                f.delete()
                saver.execute { f.delete() }   // (after a save still on its way)
                sheet?.close()
                Toast.makeText(this, "Mémoire effacée", Toast.LENGTH_SHORT).show()
                if (was) { showPower(); powerOn() }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ------------------------------------------------------------------ refresh

    private var n = 0
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            Native.lcd(px)
            Native.leds(leds)
            panel.update(px, leds)
            keysPanel.update(px, leds)
            // the peaks of this frame: the mixer's meters, the recorder's wave, the status line's
            Native.meters(m4)
            mixView.levels(m4[0], m4[1], m4[2])
            if (recorder.recording) recView.addPeak(m4[3])
            statPeak[0] = maxOf(statPeak[0], m4[0]); statPeak[1] = maxOf(statPeak[1], m4[1])
            panelRemote.lights(leds)
            Native.midiInfo(mi)
            if (recorder.recording && n % 15 == 0) showRec()
            val now = System.currentTimeMillis()
            val clockOn = midi.clockCount > 0 && now - midi.lastClockMs < 500
            val active = mi[0] != lastMidiCount || clockOn
            lastMidiCount = mi[0]
            midiDot.background = pill(if (active) AMBER else 0xFF4A4B50.toInt(), 0, dp(6))
            if (n % 30 == 0) saveStateIfChanged()
            if (n++ % 15 == 0) {
                val load = Native.load()
                if (load == -2) {
                    cpu.text = "ERR"
                    status.text = Native.status()
                } else {
                    cpu.text = if (load >= 0) "$load%" else "–"
                    cpu.setTextColor(if (load > 85) 0xFFFF6B6B.toInt() else 0xFFFFB3A8.toInt())
                    mt[0] = minOf(1f, statPeak[0]); mt[1] = minOf(1f, statPeak[1]); statPeak.fill(0f)
                    val clk = if (clockOn) "horloge MIDI reçue" else "pas d'horloge"
                    val got = audio.capturedSamples
                    val rateNow = ((got - lastCaptured) * 1000L / 2L / maxOf(1L, now - lastCaptureMs)).toInt()
                    lastCaptured = got
                    lastCaptureMs = now
                    val cs = audio.captureStatus()
                    val cap = cs + if (cs.startsWith("capture ") && !cs.startsWith("capture impossible"))
                        " · %d Hz reçus".format(rateNow) else ""
                    Native.audioInfo(ai)
                    mixView.limiterDb = if (limiter) ai[2] else 0f
                    val fm1q = if (cs.startsWith("capture ") && !cs.startsWith("capture impossible")) " · FM-1 %.0f ms, %+.0f ppm".format(ai[0], ai[1]) else ""
                    val lim = if (limiter && ai[2] < -0.5f) " · limiteur %.0f dB".format(ai[2]) else ""
                    status.text = "son → ${audio.outputName()} · $cap$fm1q · $clk · émulé %.2f / FM-1 %.2f$lim $modeMsg".format(mt[0], mt[1])
                }
            }
            ui.postDelayed(this, 33)
        }
    }

    // ------------------------------------------------------------------ recording

    /** under the recorder's wave */
    private fun recInfo() = (if (recorder.mode == Recorder.MODE_MIX) "Le mix" else "Deux pistes") + " · " + recFormat()

    /** a fader of the mixer page: the emulated FM-1 (its firmware's), the real one, the master */
    private fun setVolume(s: Int, v: Int) {
        when (s) {
            0 -> { gEmuPct = v; audio.gEmu = v / 100f; fwPut("gEmu", v) }
            1 -> { gExtPct = v; audio.gExt = v / 100f; prefs.edit().putInt("gExt", v).apply() }
            else -> { masterPct = v; prefs.edit().putInt("master", v).apply(); applyMix() }
        }
    }

    /** the mixer page as the settings are (they change from the menu too) */
    private fun syncMixer() {
        if (!::mixView.isInitialized) return
        mixView.set(intArrayOf(gEmuPct, gExtPct, masterPct), eqDb)
    }

    private fun showRec() {
        if (::recView.isInitialized) {
            recView.setRecording(recorder.recording)
            recView.info = recInfo()
        }
        if (recorder.recording) {
            val t = recorder.seconds()
            val lost = recorder.dropped()
            recBtn.text = "● %d:%02d".format(t / 60, t % 60) + if (lost > 0) " !" else ""
            recBtn.setTextColor(Color.WHITE)
            recBtn.background = pill(0xFFE5534B.toInt(), 0, dp(21))
        } else {
            recBtn.text = "●"
            recBtn.setTextColor(0xFFE5534B.toInt())
            recBtn.background = pill(0, 0xFF4A4B50.toInt(), dp(21))
        }
    }

    private fun toggleRecord() {
        if (!recorder.recording) {
            if (!running) { Toast.makeText(this, "Allume d'abord la machine (⏻)", Toast.LENGTH_SHORT).show(); return }
            val err = recorder.start()
            if (err.isNotEmpty()) Toast.makeText(this, "Enregistrement impossible : $err", Toast.LENGTH_LONG).show()
            showRec()
            return
        }
        val err = recorder.stop()
        showRec()
        val field = EditText(this).apply {
            setText(recorder.defaultName); setSelectAllOnFocus(true); isSingleLine = true
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) }
        AlertDialog.Builder(this)
            .setTitle(if (err.isEmpty()) "Nom de l'enregistrement" else "Enregistrement interrompu ($err)")
            .setMessage(if (recorder.mode == Recorder.MODE_SPLIT) "Deux fichiers : « nom - émulé » et « nom - FM-1 »" else null)
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                Toast.makeText(this, "Enregistré : " + recorder.keep(field.text.toString()), Toast.LENGTH_LONG).show()
                recView.hasTake = true
            }
            .setNeutralButton("Enregistrer et découper") { _, _ ->
                recorder.keep(field.text.toString())
                recView.hasTake = true
                openSlicer(recorder.lastKept.firstOrNull())
            }
            .setNegativeButton("Supprimer") { _, _ -> recorder.discard() }
            .setOnCancelListener { recorder.keep(recorder.defaultName); recView.hasTake = true }
            .show()
        field.requestFocus()
    }

    /** The slicer, on [uri] (a recording) or with its list of recordings. */
    private fun openSlicer(uri: android.net.Uri?) {
        val i = android.content.Intent(this, SlicerActivity::class.java)
        if (uri != null) i.putExtra(SlicerActivity.EXTRA_URI, uri.toString())
        startActivity(i)
    }

    // ------------------------------------------------------------------ menus (bottom sheets: Sheet.kt)

    private fun open(url: String) {
        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    private fun kb(n: Long) = if (n >= 1 shl 20) "%.1f Mo".format(n / 1048576.0) else "${(n + 1023) / 1024} Ko"

    private val KIND_FR = mapOf("synth" to "Synthés", "drums" to "Batteries", "groovebox" to "Grooveboxes", "sampler" to "Échantillonneurs",
        "game" to "Jeux", "tool" to "Outils", "sequencer" to "Séquenceurs", "fx" to "Effets", "chords" to "Accords")
    private fun kindName(k: String) = KIND_FR[k] ?: k.replaceFirstChar { it.uppercase() }

    // the catalogue's list, rebuilt without rebuilding its page (the search field keeps the keyboard)
    private var catalogList: LinearLayout? = null
    private var catalogQuery = ""
    private var catalogFilter = ""            // "": all; "~installed": on the phone; else a kind

    /** The firmware catalogue: search, filters, one row each (what it is, and what touching its button does). */
    private fun openCatalog() {
        val sh = Sheet(this).onClose { catalogList = null; sheet = null }
        sheet = sh
        sh.show("Firmwares") { catalogPage(sh) }
        if (System.currentTimeMillis() - store.updatedAt() > FirmwareStore.DAY_MS) refreshCatalog(false) { if (catalogList != null) renderCatalogList() }
    }

    private fun catalogPage(sh: Sheet) {
        val p = sh.page!!
        // the search field
        val search = EditText(this).apply {
            setText(catalogQuery); hint = "Rechercher : nom, auteur, style, licence"; isSingleLine = true
            setTextColor(Color.WHITE); setHintTextColor(Sheet.DIM); textSize = 15f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = pill(Sheet.CARD, 0, dp(14))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { catalogQuery = e?.toString() ?: ""; renderCatalogList() }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        }
        p.raw(search)
        // the filters: all, on the phone, then the kinds (when the Hub's list was read)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(4)) }
        val filters = listOf("" to "Tous", "~installed" to "Sur le téléphone") + FirmwareCatalog.kinds(cat()).map { it to kindName(it) }
        for ((key, label) in filters) {
            val on = key == catalogFilter
            chips.addView(TextView(this).apply {
                text = label; textSize = 14f; setTextColor(if (on) 0xFF1E1F22.toInt() else Color.WHITE)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = pill(if (on) Sheet.AMBER else 0, if (on) 0 else 0xFF4A4B50.toInt(), dp(16))
                setOnClickListener { catalogFilter = key; sh.refresh() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
        p.raw(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        // the list
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        catalogList = list
        p.raw(list)
        renderCatalogList()
        // the rest
        p.group("Autres")
        val imported = File(filesDir, "firmware").listFiles()?.filter { it.isFile && it.name.endsWith(".fwsc", true) }?.sortedBy { it.name } ?: emptyList()
        for (f in imported) p.choice(f.name.removeSuffix(".fwsc"), "importé", firmware == "file:${f.name}") { selectFirmware("file:${f.name}") }
        p.action("Importer un fichier .fwsc…") { importFw.launch(arrayOf("*/*")) }
        p.action("Mettre à jour la liste", "lue " + if (store.updatedAt() == 0L) "dans l'appli (copie livrée)" else
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(store.updatedAt()))) {
            Toast.makeText(this, "Mise à jour…", Toast.LENGTH_SHORT).show()
            refreshCatalog(true) { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); if (sh.isShowing) sh.refresh() }
        }
        p.note("Liste de fm1-emu (Joakim Eriksson) d'après The Firmware Hub (thefirmwarehub.com, zvenson) : les firmwares " +
            "que l'émulateur fait tourner. Chacun reste sous la licence de son auteur ; les paquets viennent du Hub ou de " +
            "l'auteur, téléchargés seulement quand tu en choisis un. ${kb(store.used())} sur le téléphone.")
    }

    /** the rows matching the search and the filter */
    private fun renderCatalogList() {
        val list = catalogList ?: return
        list.removeAllViews()
        val inst = store.installed()
        var rows = FirmwareCatalog.search(cat(), catalogQuery, catalogFilter.takeIf { it.isNotEmpty() && !it.startsWith("~") })
        if (catalogFilter == "~installed") rows = rows.filter { it.id in inst }
        list.addView(TextView(this).apply {
            text = if (rows.isEmpty()) "Aucun firmware ne correspond." else "${rows.size} firmware" + (if (rows.size > 1) "s" else "")
            textSize = 12f; setTextColor(Sheet.DIM); setPadding(dp(8), dp(8), dp(8), dp(6))
        })
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = pill(Sheet.CARD, 0, dp(16)); clipToOutline = true }
        for ((k, e) in rows.withIndex()) {
            if (k > 0) card.addView(View(this).apply { setBackgroundColor(Sheet.LINE) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = dp(16) })
            card.addView(firmwareRow(e, inst[e.id]))
        }
        if (rows.isNotEmpty()) list.addView(card)
    }

    /** a firmware: name and version, author, licence and kind, what it does; its button: run, install, update, bring */
    private fun firmwareRow(e: FirmwareCatalog.Entry, inst: FirmwareStore.Installed?): View {
        val current = firmware == "cat:${e.id}"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(12), dp(12))
            isClickable = true
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null,
                android.graphics.drawable.ColorDrawable(Color.WHITE))
            setOnClickListener { sheet?.push(e.name) { firmwarePage(e) } }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = android.text.SpannableStringBuilder(e.name).apply {
                val st = length
                append("  ${e.version}")
                setSpan(android.text.style.ForegroundColorSpan(Sheet.DIM), st, length, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.8f), st, length, 0)
            }
            textSize = 16f; setTextColor(if (current) Sheet.AMBER else Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        texts.addView(TextView(this).apply {
            text = listOfNotNull(e.author.takeIf { it.isNotEmpty() }, e.license.substringBefore(" ("), e.kind?.let { kindName(it) }).joinToString(" · ")
            textSize = 12f; setTextColor(Sheet.DIM); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (e.about.isNotEmpty()) texts.addView(TextView(this).apply {
            text = e.about; textSize = 13f; setTextColor(0xFFC8C6C0.toInt()); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(firmwareButton(e, inst, current), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { leftMargin = dp(10) })
        return row
    }

    /** the one thing to do with a firmware, as a pill */
    private fun firmwareButton(e: FirmwareCatalog.Entry, inst: FirmwareStore.Installed?, current: Boolean): TextView {
        val busy = downloading == e.id
        val (label, filled) = when {
            busy -> (if (downloadPct >= 0) "$downloadPct %" else "…") to false
            current && running -> "En cours" to false
            e.byo -> "Ton fichier" to false
            inst != null && inst.version != e.version -> "Mettre à jour" to true
            inst != null -> "Lancer" to true
            else -> ("Installer" + (e.size?.let { " · ${kb(it)}" } ?: "")) to false
        }
        return TextView(this).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
            setTextColor(if (filled) 0xFF1E1F22.toInt() else if (current && running) Sheet.GREEN else Color.WHITE)
            background = pill(if (filled) Sheet.AMBER else 0, if (filled) 0 else if (current && running) Sheet.GREEN else 0xFF55565C.toInt(), dp(18))
            setOnClickListener { runFirmware(e) }
        }
    }

    /** install it if needed (or update it), then run it */
    private fun runFirmware(e: FirmwareCatalog.Entry) {
        if (e.byo) { sheet?.push(e.name) { firmwarePage(e) }; return }
        if (downloading != null) return
        if (firmware == "cat:${e.id}" && (running || booting) && store.isCurrent(e)) { sheet?.close(); return }   // (already the one)
        if (store.isCurrent(e)) { selectFirmware("cat:${e.id}"); return }
        fetch(e) { err ->
            if (err != null) Toast.makeText(this, "${e.name} : $err", Toast.LENGTH_LONG).show() else selectFirmware("cat:${e.id}")
        }
    }

    /** a firmware's page: what it is, where it comes from, what can be done with it */
    private fun firmwarePage(e: FirmwareCatalog.Entry) {
        val p = sheet!!.page!!
        val inst = store.installed(e.id)
        if (e.about.isNotEmpty()) { p.group(); p.text(e.about) }
        p.group()
        when {
            e.byo -> {
                p.action("Télécharger le fichier chez ${e.author}", "puis l'importer ici") { e.download?.let { open(it) } }
                p.action("Importer le fichier .fwsc…") { importFw.launch(arrayOf("*/*")) }
            }
            firmware == "cat:${e.id}" && running -> p.info("État", "en cours")
            inst != null && inst.version == e.version -> p.action("Lancer") { selectFirmware("cat:${e.id}") }
            inst != null -> p.action("Mettre à jour vers ${e.version} et lancer", "sur le téléphone : ${inst.version}") { runFirmware(e) }
            else -> p.action("Installer et lancer", e.size?.let { kb(it) }) { runFirmware(e) }
        }
        p.group("Informations")
        p.info("Version", e.version.ifEmpty { "—" })
        p.info("Auteur", e.author.ifEmpty { "—" })
        p.info("Licence", e.license)
        e.kind?.let { p.info("Genre", kindName(it)) }
        e.minMhz?.let { p.info("Horloge minimale", "$it MHz (mesurée par fm1-emu)") }
        e.size?.let { p.info("Taille", kb(it)) }
        p.info("Vérification", if (e.sha256 != null) "SHA-256 du Hub" else "aucune empreinte publiée")
        if (e.repo != null || e.hubPage != null || e.installer != null) {
            p.group("Liens")
            e.repo?.let { r -> p.nav("Code source", r.removePrefix("https://").removePrefix("github.com/")) { open(r) } }
            e.hubPage?.let { h -> p.nav("Sur The Firmware Hub") { open(h) } }
            e.installer?.let { i -> p.nav("L'installer sur le FM-1", "page de l'auteur") { open(i) } }
        }
        if (stateFile("cat:${e.id}").exists()) {
            p.group("Mémoire")
            p.info("Sauvegardé", kb(stateFile("cat:${e.id}").length()) + " · revient à chaque lancement")
            p.action("Effacer sa mémoire", "Ses sons et réglages enregistrés : il repart comme neuf", danger = true) { forgetState("cat:${e.id}") }
        }
        if (inst != null) {
            p.group()
            p.action("Supprimer du téléphone", kb(inst.file.length()), danger = true) {
                if (firmware == "cat:${e.id}" && (running || booting)) {
                    Toast.makeText(this, "Il tourne : choisis-en d'abord un autre", Toast.LENGTH_SHORT).show()
                } else { store.delete(e.id); sheet?.refresh() }
            }
        }
    }

    // ---- settings

    private fun outName() = if (outTarget == AudioIo.OUT_FM1) "Sur le FM-1" else "Sur le téléphone"
    private val MIDI_MODES = listOf(
        Triple("Horloge seulement", "Start, stop et horloge : le FM-1 (LOOM-8) mène le tempo", 0),
        Triple("Tout vers l'entrée MIDI", "Chaque message va à l'entrée MIDI du firmware émulé", 1),
        Triple("Piloter le panneau", "Un profil change notes et CC en touches, boutons et encodeurs", 2))
    private fun recFormat() = "${if (recorder.bits == 32) "32 bits flottant" else "${recorder.bits} bits"} · " +
        "${if (recorder.rate == 44100) "44,1" else "48"} kHz · ${if (recorder.channels == 2) "stéréo" else "mono"}"
    private fun clockName() = if (mhz == MHZ_AUTO) "Auto · ${clockFor(firmware)} MHz" else "$mhz MHz"

    /** The settings, grouped; [midiOnly]: straight to the MIDI page (the MIDI light in the bar) */
    private fun openSettings(midiOnly: Boolean = false) {
        val sh = Sheet(this).onClose { sheet = null; catalogList = null }
        sheet = sh
        if (midiOnly) { sh.show("MIDI") { midiPage() }; return }
        sh.show("Réglages") {
            group("Firmware")
            nav("Firmware", firmwareLabel(firmware).lowercase().replaceFirstChar { it.uppercase() }) { sh.push("Firmwares") { catalogPage(sh) } }
            group("Son")
            nav("Sortie", outName()) { sh.push("Sortie du son") { outputPage() } }
            nav("Mixeur et EQ", mixSummary()) { sh.push("Mixeur et EQ") { mixerPage() } }
            toggle("Latence basse", "Réserves de 20-25 ms au lieu de 50-60 : moins de retard, un peu plus de risque de craquements", lowLatency) {
                lowLatency = it; prefs.edit().putBoolean("lowLatency", it).apply(); Native.setLatency(lowLatency, offsetMs)
            }
            slider("Alignement des deux FM-1", offsetMs, -100, 100, { v ->
                when { v > 0 -> "FM-1 branché +$v ms"; v < 0 -> "émulé +${-v} ms"; else -> "0 ms" } }) {
                offsetMs = it; prefs.edit().putInt("offsetMs", it).apply(); Native.setLatency(lowLatency, offsetMs)
            }
            toggle("Limiteur", "Le mix ne sature pas quand les deux sons sont forts (-1 dBFS) ; enregistrements en 16 et 24 bits compris", limiter) {
                limiter = it; prefs.edit().putBoolean("limiter", it).apply(); Native.setLimiter(it)
            }
            note("Alignement : si l'un des deux sons arrive après l'autre, retarde celui qui est en avance. En Bluetooth, " +
                "l'enceinte ajoute 150 à 300 ms à tout : un casque filaire (adaptateur ou hub USB-C) ou une enceinte LE Audio en ajoutent beaucoup moins.")
            group("MIDI")
            nav("MIDI reçu", if (panelRemote.state == true) "Contrôles du FM-1 (PANEL)" else MIDI_MODES[midiMode].first) { sh.push("MIDI") { midiPage() } }
            group("Enregistrement")
            nav("Contenu", if (recorder.mode == Recorder.MODE_MIX) "Le mix" else "Deux pistes") { sh.push("Contenu") { recContentPage() } }
            nav("Format", recFormat()) { sh.push("Format") { recFormatPage() } }
            note("Bouton ● en haut ; fichiers dans Musique/${Recorder.FOLDER}.")
            group("Machine")
            nav("Horloge", clockName()) { sh.push("Horloge") { clockPage() } }
            toggle("Puce flash récente", "UC25HQ80, des FM-1 récents", newChip) {
                newChip = it; prefs.edit().putBoolean("newChip", it).apply(); restart()
            }
            group("Aide")
            nav("Diagnostic audio USB") { sh.push("Diagnostic") { diagnosticPage() } }
            nav("À propos et licences") { sh.push("À propos") { aboutPage() } }
        }
    }

    private fun balName(v: Int) = when { v < 0 -> "G ${-v}"; v > 0 -> "D $v"; else -> "centre" }
    private fun mixSummary() = "émulé $gEmuPct % · FM-1 $gExtPct % · master $masterPct %"

    /** a balance near the centre is the centre (a finger can hardly land on 0) */
    private fun snap(v: Int) = if (kotlin.math.abs(v) < 5) 0 else v

    private fun applyMix() {
        Native.setMix(balEmuPct / 100f, balExtPct / 100f, masterPct / 100f)
        for (s in 0 until 3) Native.setEq(s, eqDb[3 * s].toFloat(), eqDb[3 * s + 1].toFloat(), eqDb[3 * s + 2].toFloat())
        syncMixer()
    }

    /** the emulated FM-1's EQ (0..2) is the firmware's; the real FM-1's and the master's (3..8) the app's */
    private fun putEq(i: Int, v: Int) = if (i < 3) fwPut("eq$i", v) else prefs.edit().putInt("eq$i", v).apply()

    private fun dbName(v: Int) = if (v > 0) "+$v dB" else if (v < 0) "$v dB" else "0 dB"
    private fun eqSummary(s: Int) = if ((0 until 3).all { eqDb[3 * s + it] == 0 }) "plat"
        else (0 until 3).joinToString(" · ") { b -> listOf("G", "M", "A")[b] + " " + eqDb[3 * s + b].let { if (it > 0) "+$it" else "$it" } }

    /** One EQ: three bands, ±12 dB. */
    private fun Sheet.Page.eqPage(s: Int) {
        group()
        val names = listOf("Grave (150 Hz, plateau)", "Médium (1 kHz, cloche)", "Aigu (6 kHz, plateau)")
        for (b in 0 until 3) slider(names[b], eqDb[3 * s + b], -12, 12, { dbName(it) }) {
            eqDb[3 * s + b] = it; putEq(3 * s + b, it); applyMix()
        }
        action("Remettre à plat") {
            for (b in 0 until 3) { eqDb[3 * s + b] = 0; putEq(3 * s + b, 0) }
            applyMix(); sheet?.refresh()
        }
    }

    /** Volume and balance of each source, and the master: what plays, and the recording « Le mix ». */
    private fun Sheet.Page.mixerPage() {
        group("FM-1 émulé")
        slider("Volume", gEmuPct, 0, 150, { "$it %" }) { gEmuPct = it; audio.gEmu = it / 100f; fwPut("gEmu", it) }
        slider("Balance", balEmuPct, -100, 100, { balName(it) }) { balEmuPct = snap(it); fwPut("balEmu", balEmuPct); applyMix() }
        nav("EQ", eqSummary(0)) { sheet?.push("EQ · FM-1 émulé") { eqPage(0) } }
        group("FM-1 branché (USB)")
        slider("Volume", gExtPct, 0, 150, { "$it %" }) { gExtPct = it; audio.gExt = it / 100f; prefs.edit().putInt("gExt", it).apply() }
        slider("Balance", balExtPct, -100, 100, { balName(it) }) { balExtPct = snap(it); prefs.edit().putInt("balExt", it).apply(); applyMix() }
        nav("EQ", eqSummary(1)) { sheet?.push("EQ · FM-1 branché") { eqPage(1) } }
        group("Sortie")
        slider("Master", masterPct, 0, 150, { "$it %" }) { masterPct = it; prefs.edit().putInt("master", it).apply(); applyMix() }
        nav("EQ master", eqSummary(2)) { sheet?.push("EQ · master") { eqPage(2) } }
        action("Tout remettre à zéro") {
            gEmuPct = 100; gExtPct = 100; balEmuPct = 0; balExtPct = 0; masterPct = 100
            audio.gEmu = 1f; audio.gExt = 1f
            prefs.edit().putInt("gExt", 100).putInt("balExt", 0).putInt("master", 100).apply()
            fwPut("gEmu", 100); fwPut("balEmu", 0)
            for (i in 0 until 9) { eqDb[i] = 0; putEq(i, 0) }
            applyMix(); sheet?.refresh()
        }
        note("Ordre : l'EQ de chaque FM-1, son volume et sa balance, puis le master et son EQ, puis le limiteur. " +
            "Vaut pour ce qui sort et pour l'enregistrement « Le mix ». « Deux pistes » " +
            "garde les deux sons bruts, pour les retravailler. Balance : vers un côté, l'autre canal baisse ; " +
            "l'image stéréo de chaque FM-1 est gardée.")
    }

    private fun Sheet.Page.outputPage() {
        group()
        choice("Sur le FM-1", "Le téléphone lui envoie l'émulé ; le FM-1 mixe avec son propre son (si son firmware a une sortie USB)",
            outTarget == AudioIo.OUT_FM1) { setOut(AudioIo.OUT_FM1) }
        choice("Sur le téléphone", "Le mix complet : un casque branché (USB-C ou adaptateur) d'abord, puis le Bluetooth, sinon le haut-parleur",
            outTarget == AudioIo.OUT_PHONE) { setOut(AudioIo.OUT_PHONE) }
        note("Sur le téléphone, le FM-1 joue aussi son propre son : baisse son MASTER ; avec LOOM-8, Setup > USB audio sur « stereo » pour qu'il envoie quand même à plein niveau.")
    }

    private fun setOut(t: Int) {
        outTarget = t
        prefs.edit().putInt("out", t).apply()
        audio.target = t
        audio.route()
        fxOutput()
    }

    /** played on the FM-1, the real FM-1 does not go through the phone: the pad cannot take it (its send FM-1 off) */
    private fun fxOutput() {
        FxState.fm1Out = outTarget == AudioIo.OUT_FM1
        if (FxState.fm1Out && FxState.target == 2) FxState.target = 0
        FxState.send()
    }

    /** The pad's effects (native/src/fx.rs, assets/fx_profiles.json), made once; the last choices back. */
    private fun loadEffects() {
        if (FxState.catalog == null) {
            val json = runCatching { assets.open("fx_profiles.json").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrDefault("[]")
            val err = Native.fxLoad(json)
            if (err.isNotEmpty()) { status.text = "Effets : $err"; return }
            FxState.catalog = FxCatalog(json, Native.fxList())
        }
        val cat = FxState.catalog ?: return
        val sel = cat.all.firstOrNull { it.id == prefs.getString("fxId", "") }
        FxState.selected = sel?.index ?: -1
        FxState.type = sel?.type ?: prefs.getString("fxType", null) ?: cat.types.firstOrNull() ?: ""
        FxState.target = prefs.getInt("fxTarget", 0)
        FxState.hold = false
        FxState.onChange = {
            prefs.edit().putString("fxId", cat.all.getOrNull(FxState.selected)?.id ?: "")
                .putString("fxType", FxState.type).putInt("fxTarget", FxState.target).apply()
        }
        fxOutput()
    }

    private fun panelSub(): String {
        val nm = panelRemote.name?.substringBefore(" / ") ?: "LOOM-8"
        return "$nm : ses touches, boutons, encodeurs et MASTER pilotent l'émulé, ses voyants montrent ceux de l'émulé. " +
            "Raccourcis : appui long sur MIDI ici, GLO + FX une seconde sur le FM-1."
    }

    private fun Sheet.Page.midiPage() {
        group("Contrôles du FM-1 branché")
        when (panelRemote.state) {
            null -> note("Aucun LOOM-8 avec PANEL MIDI branché en USB : ses contrôles restent à lui. (LOOM-8 de la version " +
                "forkée, avec PANEL MIDI dans Setup > USB and MIDI.)")
            else -> toggle("Piloter l'émulé", panelSub(), panelRemote.state == true) { panelRemote.set(it) }
        }
        group("Ce que fait le MIDI reçu")
        for ((t, sub, k) in MIDI_MODES) choice(t, sub, midiMode == k) { midiMode = k; fwPut("midiMode", k); applyMidiMode() }
        group("Profil du contrôleur (« Piloter le panneau »)")
        val names = (assets.list("profiles")?.toList() ?: emptyList()) + (File(filesDir, "profiles").list()?.toList() ?: emptyList())
        for (nm in names) choice(nm.removeSuffix(".json"), null, nm == profileName) { profileName = nm; fwPutStr("profile", nm); applyMidiMode() }
        action("Charger un profil JSON…") { pickProfile.launch(arrayOf("*/*")) }
        note("remotefm1-panel : choisi tout seul quand LOOM-8 prête ses contrôles, l'ancien réglage revient après.")
    }

    private fun Sheet.Page.recContentPage() {
        group()
        choice("Le mix", "Ce que tu entends, curseurs compris", recorder.mode == Recorder.MODE_MIX) { setRec("recMode", Recorder.MODE_MIX) { recorder.mode = it } }
        choice("Deux pistes", "« nom - émulé » et « nom - FM-1 », alignées, avant les curseurs", recorder.mode == Recorder.MODE_SPLIT) { setRec("recMode", Recorder.MODE_SPLIT) { recorder.mode = it } }
    }

    private fun Sheet.Page.recFormatPage() {
        group("Résolution")
        choice("16 bits", "Le format des sources", recorder.bits == 16) { setRec("recBits", 16) { recorder.bits = it } }
        choice("24 bits", "La somme du mix sans saturation", recorder.bits == 24) { setRec("recBits", 24) { recorder.bits = it } }
        choice("32 bits flottant", "Aucune saturation, pour retravailler", recorder.bits == 32) { setRec("recBits", 32) { recorder.bits = it } }
        group("Fréquence")
        choice("44,1 kHz", "Celle des sources", recorder.rate == 44100) { setRec("recRate", 44100) { recorder.rate = it } }
        choice("48 kHz", "Rééchantillonné : vidéo, projets à 48 kHz", recorder.rate == 48000) { setRec("recRate", 48000) { recorder.rate = it } }
        group("Canaux")
        choice("Stéréo", null, recorder.channels == 2) { setRec("recCh", 2) { recorder.channels = it } }
        choice("Mono", null, recorder.channels == 1) { setRec("recCh", 1) { recorder.channels = it } }
        note("Vaut pour le prochain enregistrement.")
    }

    private fun setRec(key: String, v: Int, set: (Int) -> Unit) { set(v); prefs.edit().putInt(key, v).apply() }

    private fun Sheet.Page.clockPage() {
        val m = entryOf(firmware)?.minMhz
        group()
        choice("Auto", if (m != null) "Le minimum mesuré pour ce firmware ($m MHz) avec une marge : ${clockFor(firmware).takeIf { mhz == MHZ_AUTO } ?: (minOf(240, maxOf(80, (m * 5 / 4 + 9) / 10 * 10)))} MHz, moins de travail pour le téléphone"
            else "Pas de mesure pour ce firmware : 240 MHz", mhz == MHZ_AUTO) { setClock(MHZ_AUTO) }
        for (v in intArrayOf(240, 200, 160, 120)) choice("$v MHz", if (v == 240) "Celle du vrai FM-1" else null, mhz == v) { setClock(v) }
        note("Redémarre le firmware émulé. Si le son craque ou que la charge dépasse 100 %, baisse l'horloge.")
    }

    private fun setClock(v: Int) { if (v != mhz) { mhz = v; fwPut("mhz", v); restart() } }

    private fun Sheet.Page.diagnosticPage() {
        val diag = Diagnostic.report(this@MainActivity) + "\nCapture : " + audio.captureStatus() + "\n" + audio.usbReport()
        group()
        action("Copier le diagnostic") {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("FM1 Mix diagnostic", diag))
            Toast.makeText(this@MainActivity, "Diagnostic copié", Toast.LENGTH_SHORT).show()
        }
        group()
        text(diag, 11f, selectable = true)
    }

    private fun Sheet.Page.aboutPage() {
        group()
        text("FM1 Mix 0.17, bêta. Un FM-1 émulé sur le téléphone, mixé avec un vrai FM-1 en USB, enregistré, découpé.")
        group("Licences")
        info("FM1 Mix", "GPL-3.0")
        info("Émulateur fm1-emu", "MIT · Joakim Eriksson")
        info("Découpe (CHOP de SLOOP)", "GPL-3.0")
        info("Envoi vers LOOM-8", "MPL-2.0 · Charles Vestal")
        info("Liste des firmwares", "fm1-emu, The Firmware Hub")
        info("Effets du pad : code généré par Faust", "GRAME · bibliothèques Faust")
        info("Filtre, Hall, Phaser, Flanger, Overdrive, Pompe", "MIT (STK-4.3) · Julius O. Smith III")
        info("Écho, Pitch, lissages", "LGPL avec exception Faust · GRAME")
        info("Nuage diffus", "GPL-2.0+ · Julian Parker, Till Bovermann")
        info("Tape stop, Beat repeat, moteur du pad", "GPL-3.0 · FM1 Mix")
        nav("Code de fm1-emu") { open("https://github.com/joakimeriksson/fm1-emu") }
        nav("SLOOP") { open("https://github.com/isod89/sloop-fm1") }
        nav("The Firmware Hub") { open(FirmwareCatalog.HUB) }
        nav("Faust") { open("https://faust.grame.fr") }
        nav("Bibliothèques Faust") { open("https://github.com/grame-cncm/faustlibraries") }
        note("Chaque firmware reste sous la licence de son auteur (sa page dans le catalogue).")
    }

    private fun restart() {
        if (running) { powerOff(); powerOn() }
    }

    private fun profileJson(name: String): String? {
        val user = File(File(filesDir, "profiles"), name)
        return if (user.exists()) user.readText()
        else runCatching { assets.open("profiles/$name").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    }

    private fun togglePanel() {
        val s = panelRemote.state
        if (s == null) {
            Toast.makeText(this, "Pas de LOOM-8 avec PANEL MIDI branché en USB", Toast.LENGTH_SHORT).show()
            return
        }
        panelRemote.set(!s)
    }

    /** LOOM-8 lent its panel (true), took it back (false) or is gone (null). */
    private fun onPanel(s: Boolean?) {
        val on = s == true
        midiLabel.text = if (on) "PANEL" else "MIDI"
        midiLabel.setTextColor(if (on) AMBER else DIM)
        midiBox.background = pill(0, if (on) AMBER else 0xFF4A4B50.toInt(), dp(14))
        if (on) {
            // the panel's MIDI is read by the profile made for it
            if (midiMode != 2 || profileName != PANEL_PROFILE) {
                if (beforePanel == null) beforePanel = Pair(midiMode, profileName)
                midiMode = 2
                profileName = PANEL_PROFILE
                fwPut("midiMode", 2); fwPutStr("profile", PANEL_PROFILE)
                applyMidiMode()
            }
            Toast.makeText(this, "Contrôles du FM-1 → émulé", Toast.LENGTH_SHORT).show()
        } else {
            beforePanel?.let { (m, p) ->
                midiMode = m
                profileName = p
                fwPut("midiMode", m); fwPutStr("profile", p)
                applyMidiMode()
            }
            beforePanel = null
            if (s == false) Toast.makeText(this, "Contrôles rendus à LOOM-8", Toast.LENGTH_SHORT).show()
        }
        sheet?.refresh()
    }

    private fun applyMidiMode() {
        if (!running) return
        val json = if (midiMode == 2) profileJson(profileName) else null
        val err = Native.setMidiMode(midiMode, json)
        modeMsg = if (err.isEmpty()) "" else "· profil : $err"
    }

    // ------------------------------------------------------------------ helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(fillColor: Int, strokeColor: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(fillColor)
        if (strokeColor != 0) setStroke(dp(2), strokeColor)
    }
}
