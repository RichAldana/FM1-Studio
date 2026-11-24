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
/** shown in About and in error reports; keep in step with versionName (app/build.gradle.kts) */
const val APP_VERSION = "0.20"

class MainActivity : ComponentActivity() {
    private lateinit var panel: PanelView
    private lateinit var fwBtn: BarButton
    private lateinit var ctlSwitch: ModeSwitch
    private lateinit var outSwitch: ModeSwitch
    private lateinit var powerBtn: PowerButton
    private lateinit var keysPanel: PanelView
    private lateinit var recView: RecorderView
    private lateinit var mixView: MixerView
    private val m4 = FloatArray(4)
    private val statPeak = FloatArray(2)
    private lateinit var recBtn: TextView
    private var recSize = 17f
    private val recorder by lazy { Recorder(this) }
    private lateinit var midiLamp: MidiLamp
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
    /** the mixer's on/off: the emulated FM-1 (its firmware's), the real one, the master */
    private val muted = BooleanArray(3)
    /** the emulated keyboard's octave as the app follows it (OCT- / OCT+ pressed on the screen or the FM-1's panel) */
    private var octave = 0
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
        val KNOB_SPEEDS = listOf(1 to "¼×", 2 to "½×", 4 to "1×", 8 to "2×")
        val NOTE_NAMES get() = arrayOf(tr("Aucune", "None"), tr("Les do", "The Cs"), tr("Toutes", "All"))
        val NOTE_SUBS get() = arrayOf(tr("Des touches nues", "Bare keys"), tr("C4, C5… : repères légers, avec l'octave", "C4, C5…: light marks, with the octave"), tr("Chaque note, les do avec leur octave", "Every note, the Cs with their octave"))
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
        catchCrashes()
        Native.loadError?.let { showFatal(tr("Le moteur de l'app (libfm1mix.so) ne se charge pas sur ce téléphone.", "The app's engine (libfm1mix.so) does not load on this phone."), it); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        firmware = prefs.getString("firmware", DEFAULT_FW) ?: DEFAULT_FW
        if (firmware.startsWith("asset:")) firmware = "cat:chordism"      // (0.9 and before: Chordism built in)
        loadFirmwarePrefs()
        newChip = prefs.getBoolean("newChip", false)
        gExtPct = prefs.getInt("gExt", 100)
        balExtPct = prefs.getInt("balExt", 0)
        masterPct = prefs.getInt("master", 100)
        muted[1] = prefs.getBoolean("muteExt", false)
        muted[2] = prefs.getBoolean("muteMaster", false)
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
        panel.onOct = { moveOctave(it) }
        keysPanel.noteNames = noteNames()
        showOctave()
        recView = RecorderView(this).apply {
            onRec = { toggleRecord() }
            onSlice = { openSlicer(recorder.lastKept.firstOrNull()) }
        }
        mixView = MixerView(this).apply {
            onVol = { s, v -> setVolume(s, v) }
            onEq = { s, b, db -> eqDb[3 * s + b] = db; putEq(3 * s + b, db); applyMix() }
            onMute = { s, off -> setMuted(s, off) }
        }
        loadEffects()
        val mid = SwipeZone(this, listOf(panel, recView, FxPadView(this)), listOf("FM-1", tr("Enregistreur", "Recorder"), tr("Effets", "Effects")),
            prefs.getInt("pageMid", 0)) { prefs.edit().putInt("pageMid", it).apply() }
        val bottom = SwipeZone(this, listOf(keysPanel, mixView), listOf(tr("Clavier", "Keyboard"), tr("Mixeur et EQ", "Mixer and EQ")),
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
        lastCrash()?.let { showCrash(it) }
    }

    private val crashFile get() = File(filesDir, "crash.txt")

    /** an uncaught error is written to crash.txt before Android closes the app; the next opening shows it */
    private fun catchCrashes() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        if (before is CrashKeeper) return
        Thread.setDefaultUncaughtExceptionHandler(CrashKeeper(crashFile, before))
    }

    private class CrashKeeper(val file: File, val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                file.writeText("FM1 Mix ${APP_VERSION}, Android ${android.os.Build.VERSION.RELEASE} " +
                    tr("(${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}), fil ${t.name}\n$sw", "(${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}), thread ${t.name}\n$sw"))
            }
            next?.uncaughtException(t, e)
        }
    }

    private fun lastCrash(): String? = runCatching {
        crashFile.takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }
    }.getOrNull()

    private fun copyText(label: String, text: String) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(this, tr("Copié", "Copied"), Toast.LENGTH_SHORT).show()
    }

    /** the last opening crashed: what happened, to copy and send */
    private fun showCrash(report: String) {
        AlertDialog.Builder(this)
            .setTitle(tr("L'app s'est fermée la dernière fois", "The app closed last time"))
            .setMessage(tr("Voici l'erreur. « Copier » la met dans le presse-papiers, pour l'envoyer.\n\n", "Here is the error. \"Copy\" puts it on the clipboard, to send it.\n\n") + report.take(3000))
            .setPositiveButton(tr("Copier", "Copy")) { _, _ -> copyText(tr("FM1 Mix erreur", "FM1 Mix error"), report) }
            .setNegativeButton(tr("Fermer", "Close"), null)
            .show()
    }

    /** the app can't run at all: say why on screen instead of closing */
    private fun showFatal(what: String, detail: String) {
        val text = "$what\n\nFM1 Mix ${APP_VERSION}, Android ${android.os.Build.VERSION.RELEASE} " +
            "(${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, ${android.os.Build.SUPPORTED_ABIS.joinToString()})\n\n$detail"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF1E1F22.toInt()); setPadding(dp(20), dp(48), dp(20), dp(20))
        }
        box.addView(TextView(this).apply { this.text = text; textSize = 14f; setTextColor(Color.WHITE); setTextIsSelectable(true) })
        box.addView(TextView(this).apply {
            this.text = tr("Copier l'erreur", "Copy the error"); textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = pill(0xFF4A4B50.toInt(), 0, dp(20)); setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { copyText(tr("FM1 Mix erreur", "FM1 Mix error"), text) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        setContentView(android.widget.ScrollView(this).apply { addView(box) })
    }

    /**
     * The top bar, left to right: the firmware (a fork: the catalogue), the slicer, REC, power, who plays the emulated
     * FM-1 (the phone or the FM-1's panel), where the sound goes (the phone or the FM-1), the MIDI lamp, the load, the
     * settings. Sized to fit the screen's width: everything shrinks together on a narrow phone.
     */
    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(0xFF1E1F22.toInt(), 0, dp(18))
        }
        // what it all takes (dp): 4 round buttons, 2 switches, MIDI, load, settings, 8 gaps, the bar's padding
        val need = 4 * 40 + 2 * 52 + 36 + 46 + 26 + 8 * 3 + 12
        val have = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 20f
        val k = minOf(1f, have / need)
        fun sz(v: Int) = (v * k * resources.displayMetrics.density).toInt()
        bar.setPadding(sz(8), 0, sz(4), 0)
        fun lp(w: Int, h: Int, first: Boolean = false) = LinearLayout.LayoutParams(sz(w), sz(h)).apply { if (!first) leftMargin = sz(3) }

        fwBtn = BarButton(this, BarIcons.FORK, "Firmware").apply { setOnClickListener { openCatalog() } }
        bar.addView(fwBtn, lp(40, 40, true))
        bar.addView(TextView(this).apply {
            text = "✂"; textSize = 17f * k; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = pill(0, 0xFF4A4B50.toInt(), sz(20))
            contentDescription = tr("Découpeur", "Slicer")
            setOnClickListener { openSlicer(null) }
        }, lp(40, 40))
        recBtn = TextView(this).apply {
            textSize = 17f * k; gravity = Gravity.CENTER; maxLines = 1
            contentDescription = tr("Enregistrer", "Record")
            setOnClickListener { toggleRecord() }
        }
        recSize = 17f * k
        bar.addView(recBtn, lp(40, 40))
        powerBtn = PowerButton(this).apply { setOnClickListener { togglePower() } }
        bar.addView(powerBtn, lp(40, 40))
        ctlSwitch = ModeSwitch(this, BarIcons.CONTROL, tr("Qui joue l'émulé : le téléphone ou le FM-1", "Who plays the emulated one: the phone or the FM-1")).apply {
            setOnClickListener { togglePanel() }
        }
        bar.addView(ctlSwitch, lp(52, 40))
        outSwitch = ModeSwitch(this, BarIcons.SPEAKER, tr("Sortie du son : le téléphone ou le FM-1", "Sound output: the phone or the FM-1")).apply {
            setOnClickListener {
                setOut(if (outTarget == AudioIo.OUT_FM1) AudioIo.OUT_PHONE else AudioIo.OUT_FM1)
                Toast.makeText(this@MainActivity, tr("Son : ", "Sound: ") + outName().lowercase(), Toast.LENGTH_SHORT).show()
            }
        }
        bar.addView(outSwitch, lp(52, 40))
        midiLamp = MidiLamp(this).apply {
            setOnClickListener { openSettings(midiOnly = true) }
            setOnLongClickListener { togglePanel(); true }
        }
        bar.addView(midiLamp, lp(36, 34))
        cpu = TextView(this).apply {
            textSize = 13f * k; setTextColor(0xFFFF8F8F.toInt()); gravity = Gravity.CENTER; maxLines = 1
            background = pill(0, 0xFF4A4B50.toInt(), sz(14))
            text = "–"
        }
        bar.addView(cpu, lp(46, 34))
        bar.addView(TextView(this).apply {
            text = "⋮"; textSize = 22f * k; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            contentDescription = tr("Réglages", "Settings")
            setOnClickListener { openSettings() }
        }, lp(26, 48))
        showSwitches()
        showRec()
        return bar
    }

    /** the top bar's two switches as things are */
    private fun showSwitches() {
        if (!::ctlSwitch.isInitialized) return
        ctlSwitch.fm1 = panelRemote.state == true
        ctlSwitch.usable = panelRemote.state != null
        outSwitch.fm1 = outTarget == AudioIo.OUT_FM1
        midiLamp.panel = panelRemote.state == true
    }

    // ------------------------------------------------------------------ power

    override fun onStart() {
        super.onStart()
        midi.onPanelOct = { d -> ui.post { moveOctave(d) } }
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

    /** the firmware's name for the status line, or what to do without one */
    private fun firmwareName(): String =
        if (firmware.isEmpty()) tr("pas de firmware (⑂)", "no firmware (⑂)") else firmwareLabel(firmware).lowercase().replaceFirstChar { it.uppercase() }

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
        if (downloading != null) return then(tr("un téléchargement est déjà en cours", "a download is already running"))
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
                then(r.exceptionOrNull()?.let { it.message ?: tr("échec du téléchargement", "download failed") })
            }
        }.start()
    }

    /** a download moved on: the status line, and the catalogue if it is open */
    private fun onDownload() {
        val d = downloading
        if (d != null) status.text = tr("Téléchargement de ${entryOf("cat:$d")?.name ?: d}… ", "Downloading ${entryOf("cat:$d")?.name ?: d}… ") + if (downloadPct >= 0) "$downloadPct %" else ""
        if (catalogList != null) renderCatalogList()
    }

    private fun powerOn() {
        if (running || booting) return
        if (firmware.isEmpty()) {                     // none chosen yet: the catalogue, to pick the first one
            showPower()
            status.text = tr("Choisis un firmware (le bouton ⑂, à gauche) : il démarrera, puis reviendra à chaque ouverture.", "Pick a firmware (the ⑂ button, on the left): it will start, then come back each time you open the app.")
            ui.post { if (sheet == null) openCatalog() }
            return
        }
        val bytes = firmwareBytes(firmware)
        if (bytes == null) {
            val e = entryOf(firmware)
            if (e != null && e.pkg != null) {           // a catalogue firmware not on the phone yet: fetch it, then start
                showPower()
                fetch(e) { err -> if (err == null) powerOn() else status.text = tr("Téléchargement impossible : $err (réseau ?). Choisis un firmware : touche son nom en haut.", "Download failed: $err (network?). Pick a firmware: tap ⑂ at the top.") }
            } else {
                status.text = tr("Firmware introuvable : choisis-en un dans le catalogue", "Firmware not found: pick one in the catalogue")
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
        status.text = tr("Démarrage de ${firmwareLabel(firmware)}…", "Starting ${firmwareLabel(firmware)}…")
        val chosenMhz = clockFor(firmware)
        val chip = newChip
        Thread {
            val err = Native.start(bytes, flash.takeIf { it.exists() }?.readBytes(), chosenMhz, chip)
            ui.post {
                booting = false
                if (err.isNotEmpty()) {
                    status.text = tr("Échec du démarrage : $err", "Start failed: $err")
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
                applyGains()
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
            val msg = runCatching { store.refresh(force) }.getOrElse { it.message ?: tr("erreur", "error") }
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
            Toast.makeText(this, tr("Enregistré : $where", "Saved: $where"), Toast.LENGTH_LONG).show()
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
        status.text = if (prefs.getBoolean("power", true)) firmwareName() else tr("${firmwareName()} · éteint", "${firmwareName()} · off")
        showPower()
    }

    private fun showPower() {
        val on = running || booting
        fwBtn.calling = firmware.isEmpty()
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
        applyGains()
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
        muted[0] = fwInt("muteEmu", 0) == 1
        octave = fwInt("oct", 0)
        applyKnobSpeed()
        if (::keysPanel.isInitialized) showOctave()
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
            .setTitle(tr("Effacer la mémoire de ${firmwareLabel(fw)} ?", "Erase ${firmwareLabel(fw)}'s memory?"))
            .setMessage(tr("Ses sons, réglages et projets enregistrés sur le FM-1 émulé sont effacés : il repart comme neuf. Les réglages de l'appli (mixeur, MIDI, horloge) restent.", "Its sounds, settings and projects saved on the emulated FM-1 are erased: it starts again as new. The app's settings (mixer, MIDI, clock) stay."))
            .setPositiveButton(tr("Effacer", "Erase")) { _, _ ->
                val was = running && firmware == fw
                if (was) { ui.removeCallbacks(tick); running = false; audio.stop(); Native.stop() }   // (not saved again)
                f.delete()
                saver.execute { f.delete() }   // (after a save still on its way)
                prefs.edit().remove(fwKey("oct", fw)).apply()
                if (fw == firmware) { octave = 0; showOctave() }
                sheet?.close()
                Toast.makeText(this, tr("Mémoire effacée", "Memory erased"), Toast.LENGTH_SHORT).show()
                if (was) { showPower(); powerOn() }
            }
            .setNegativeButton(tr("Annuler", "Cancel"), null)
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
            midiLamp.active = active
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
                    val clk = if (clockOn) tr("horloge MIDI reçue", "MIDI clock received") else tr("pas d'horloge", "no clock")
                    val got = audio.capturedSamples
                    val rateNow = ((got - lastCaptured) * 1000L / 2L / maxOf(1L, now - lastCaptureMs)).toInt()
                    lastCaptured = got
                    lastCaptureMs = now
                    val cs = audio.captureStatus()
                    val cap = cs + if (cs.startsWith("capture ") && !cs.startsWith(tr("capture impossible", "capture failed")))
                        tr(" · %d Hz reçus", " · %d Hz received").format(rateNow) else ""
                    Native.audioInfo(ai)
                    mixView.limiterDb = if (limiter) ai[2] else 0f
                    val fm1q = if (cs.startsWith("capture ") && !cs.startsWith(tr("capture impossible", "capture failed"))) " · FM-1 %.0f ms, %+.0f ppm".format(ai[0], ai[1]) else ""
                    val lim = if (limiter && ai[2] < -0.5f) tr(" · limiteur %.0f dB", " · limiter %.0f dB").format(ai[2]) else ""
                    status.text = tr("${firmwareName()} · son → ${audio.outputName()} · $cap$fm1q · $clk · émulé %.2f / FM-1 %.2f$lim $modeMsg", "${firmwareName()} · sound → ${audio.outputName()} · $cap$fm1q · $clk · emulated %.2f / FM-1 %.2f$lim $modeMsg").format(mt[0], mt[1])
                }
            }
            ui.postDelayed(this, 33)
        }
    }

    // ------------------------------------------------------------------ recording

    /** under the recorder's wave */
    private fun recInfo() = (if (recorder.mode == Recorder.MODE_MIX) tr("Le mix", "The mix") else tr("Deux pistes", "Two tracks")) + " · " + recFormat()

    /** a fader of the mixer page: the emulated FM-1 (its firmware's), the real one, the master */
    private fun setVolume(s: Int, v: Int) {
        when (s) {
            0 -> { gEmuPct = v; applyGains(); fwPut("gEmu", v) }
            1 -> { gExtPct = v; applyGains(); prefs.edit().putInt("gExt", v).apply() }
            else -> { masterPct = v; prefs.edit().putInt("master", v).apply(); applyMix() }
        }
    }

    private fun noteNames() = prefs.getInt("noteNames", PanelView.NOTES_C)

    /** the knobs' speed, this firmware's: quarter detents per detent turned (engine.rs KNOB_SPEED_Q) */
    private fun knobQ() = fwInt("knobQ", 4)
    private fun knobAll() = fwInt("knobAll", 0) == 1
    private fun applyKnobSpeed() = Native.setKnobSpeed(knobQ(), knobAll())
    private fun knobSpeedName() = (KNOB_SPEEDS.firstOrNull { it.first == knobQ() }?.second ?: "1×") +
        if (knobAll()) tr(" · tous les encodeurs", " · every encoder") else ""

    /** settings: how far a knob goes for a detent turned, on the screen and on the FM-1's panel */
    private fun Sheet.Page.knobSpeedPage() {
        group(tr("KNOB 1 à 4", "KNOB 1 to 4"))
        for ((q, name) in KNOB_SPEEDS) choice(name, when (q) {
            1 -> tr("Un cran tous les quatre : réglages fins", "One detent every four: fine settings")
            2 -> tr("Un cran tous les deux : contre les sauts", "One detent every two: against jumps")
            4 -> tr("Comme tourné", "As turned")
            else -> tr("Deux crans par cran : grands balayages", "Two detents per detent: wide sweeps")
        }, knobQ() == q) { fwPut("knobQ", q); applyKnobSpeed(); sheet?.refresh() }
        group(tr("Les autres", "The others"))
        toggle(tr("Aussi SELECT, ALGO et PRESET", "SELECT, ALGO and PRESET too"),
            tr("Sinon ils restent comme tournés (les menus ne sautent pas de ligne)", "Otherwise they stay as turned (menus skip no line)"),
            knobAll()) { fwPut("knobAll", if (it) 1 else 0); applyKnobSpeed() }
        note(tr("Vaut pour les potards de l'écran et pour ceux du FM-1 en PANEL MIDI. Gardé pour chaque firmware : " +
                "certains accélèrent quand on tourne vite, d'autres non.",
            "Applies to the screen's knobs and to the FM-1's in PANEL MIDI. Kept for each firmware: some speed up when " +
                "turned fast, others don't."))
    }

    /** OCT- or OCT+ went to the emulated FM-1: the keys' names follow (its firmware's range is unknown: ±4 kept) */
    private fun moveOctave(d: Int) {
        octave = (octave + d).coerceIn(-4, 4)
        fwPut("oct", octave)
        showOctave()
    }

    private fun showOctave() {
        keysPanel.octave = octave
        panel.octave = octave
    }

    /** settings: what the keys show, and the octave the app thinks the emulated keyboard is at */
    private fun Sheet.Page.keyboardPage() {
        group(tr("Notes sur les touches", "Notes on the keys"))
        for (i in NOTE_NAMES.indices) choice(NOTE_NAMES[i], NOTE_SUBS[i], noteNames() == i) {
            prefs.edit().putInt("noteNames", i).apply()
            keysPanel.noteNames = i
            sheet?.refresh()
        }
        group(tr("Octave", "Octave"))
        slider(tr("Octave du clavier", "Keyboard octave"), octave, -4, 4, { tr("do grave : ", "low C: ") + PanelView.noteName(60 + 12 * it) }) {
            octave = it; fwPut("oct", it); showOctave()
        }
        note(tr("Les noms suivent OCT - et OCT +, touchés à l'écran ou sur le FM-1 (PANEL MIDI). Si le firmware change ", "The names follow OCT - and OCT +, pressed on the screen or on the FM-1 (PANEL MIDI). If the firmware changes ") +
            tr("d'octave autrement (un menu, un preset), recale-la ici. Gardée pour chaque firmware.", "octave another way (a menu, a preset), set it again here. Kept for each firmware."))
    }

    /** the volumes as they sound: a strip turned off is silent, its fader kept */
    private fun applyGains() {
        audio.gEmu = if (muted[0]) 0f else gEmuPct / 100f
        audio.gExt = if (muted[1]) 0f else gExtPct / 100f
    }

    /** a strip of the mixer turned on or off */
    private fun setMuted(s: Int, off: Boolean) {
        muted[s] = off
        when (s) {
            0 -> fwPut("muteEmu", if (off) 1 else 0)
            1 -> prefs.edit().putBoolean("muteExt", off).apply()
            else -> prefs.edit().putBoolean("muteMaster", off).apply()
        }
        applyGains(); applyMix()
    }

    /** the mixer page as the settings are (they change from the menu too) */
    private fun syncMixer() {
        if (!::mixView.isInitialized) return
        mixView.set(intArrayOf(gEmuPct, gExtPct, masterPct), eqDb, muted)
    }

    private fun showRec() {
        if (::recView.isInitialized) {
            recView.setRecording(recorder.recording)
            recView.info = recInfo()
        }
        if (recorder.recording) {
            val t = recorder.seconds()
            val lost = recorder.dropped()
            recBtn.text = if (t < 600) "%d:%02d".format(t / 60, t % 60) else "${t / 60}'"
            recBtn.textSize = recSize * 0.62f
            recBtn.typeface = Typeface.DEFAULT_BOLD
            recBtn.setTextColor(Color.WHITE)
            recBtn.background = pill(if (lost > 0) 0xFFB8860B.toInt() else 0xFFE5534B.toInt(), 0, dp(21))
        } else {
            recBtn.text = "●"
            recBtn.textSize = recSize
            recBtn.typeface = Typeface.DEFAULT
            recBtn.setTextColor(0xFFE5534B.toInt())
            recBtn.background = pill(0, 0xFF4A4B50.toInt(), dp(21))
        }
    }

    private fun toggleRecord() {
        if (!recorder.recording) {
            if (!running) { Toast.makeText(this, tr("Allume d'abord la machine (⏻)", "Turn the machine on first (⏻)"), Toast.LENGTH_SHORT).show(); return }
            val err = recorder.start()
            if (err.isNotEmpty()) Toast.makeText(this, tr("Enregistrement impossible : $err", "Cannot record: $err"), Toast.LENGTH_LONG).show()
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
            .setTitle(if (err.isEmpty()) tr("Nom de l'enregistrement", "Recording name") else tr("Enregistrement interrompu ($err)", "Recording interrupted ($err)"))
            .setMessage(if (recorder.mode == Recorder.MODE_SPLIT) tr("Deux fichiers : « nom - émulé » et « nom - FM-1 »", "Two files: \"name - emulated\" and \"name - FM-1\"") else null)
            .setView(box)
            .setPositiveButton(tr("Enregistrer", "Record")) { _, _ ->
                Toast.makeText(this, tr("Enregistré : ", "Saved: ") + recorder.keep(field.text.toString()), Toast.LENGTH_LONG).show()
                recView.hasTake = true
            }
            .setNeutralButton(tr("Enregistrer et découper", "Save and slice")) { _, _ ->
                recorder.keep(field.text.toString())
                recView.hasTake = true
                openSlicer(recorder.lastKept.firstOrNull())
            }
            .setNegativeButton(tr("Supprimer", "Remove")) { _, _ -> recorder.discard() }
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

    private val KIND_FR = mapOf("synth" to tr("Synthés", "Synths"), "drums" to tr("Batteries", "Drums"), "groovebox" to "Grooveboxes", "sampler" to tr("Échantillonneurs", "Samplers"),
        "game" to tr("Jeux", "Games"), "tool" to tr("Outils", "Tools"), "sequencer" to tr("Séquenceurs", "Sequencers"), "fx" to tr("Effets", "Effects"), "chords" to tr("Accords", "Chords"))
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
            setText(catalogQuery); hint = tr("Rechercher : nom, auteur, style, licence", "Search: name, author, style, licence"); isSingleLine = true
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
        val filters = listOf("" to tr("Tous", "All"), "~installed" to tr("Sur le téléphone", "On the phone")) + FirmwareCatalog.kinds(cat()).map { it to kindName(it) }
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
        p.group(tr("Autres", "Others"))
        val imported = File(filesDir, "firmware").listFiles()?.filter { it.isFile && it.name.endsWith(".fwsc", true) }?.sortedBy { it.name } ?: emptyList()
        for (f in imported) p.choice(f.name.removeSuffix(".fwsc"), tr("importé", "imported"), firmware == "file:${f.name}") { selectFirmware("file:${f.name}") }
        p.action(tr("Importer un fichier .fwsc…", "Import a .fwsc file…")) { importFw.launch(arrayOf("*/*")) }
        p.action(tr("Mettre à jour la liste", "Update the list"), tr("lue ", "read ") + if (store.updatedAt() == 0L) tr("dans l'appli (copie livrée)", "in the app (copy shipped)") else
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(store.updatedAt()))) {
            Toast.makeText(this, tr("Mise à jour…", "Updating…"), Toast.LENGTH_SHORT).show()
            refreshCatalog(true) { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); if (sh.isShowing) sh.refresh() }
        }
        p.note(tr("Liste de fm1-emu (Joakim Eriksson) d'après The Firmware Hub (thefirmwarehub.com, zvenson) : les firmwares ", "fm1-emu's list (Joakim Eriksson) after The Firmware Hub (thefirmwarehub.com, zvenson): the firmwares ") +
            tr("que l'émulateur fait tourner. Chacun reste sous la licence de son auteur ; les paquets viennent du Hub ou de ", "the emulator runs. Each stays under its author's licence; packages come from the Hub or the ") +
            tr("l'auteur, téléchargés seulement quand tu en choisis un. ${kb(store.used())} sur le téléphone.", "author, downloaded only when you pick one. ${kb(store.used())} on the phone."))
    }

    /** the rows matching the search and the filter */
    private fun renderCatalogList() {
        val list = catalogList ?: return
        list.removeAllViews()
        val inst = store.installed()
        var rows = FirmwareCatalog.search(cat(), catalogQuery, catalogFilter.takeIf { it.isNotEmpty() && !it.startsWith("~") })
        if (catalogFilter == "~installed") rows = rows.filter { it.id in inst }
        list.addView(TextView(this).apply {
            text = if (rows.isEmpty()) tr("Aucun firmware ne correspond.", "No firmware matches.") else "${rows.size} firmware" + (if (rows.size > 1) "s" else "")
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
            current && running -> tr("En cours", "Running") to false
            e.byo -> tr("Ton fichier", "Your file") to false
            inst != null && inst.version != e.version -> tr("Mettre à jour", "Update") to true
            inst != null -> tr("Lancer", "Start") to true
            else -> (tr("Installer", "Install") + (e.size?.let { " · ${kb(it)}" } ?: "")) to false
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
                p.action(tr("Télécharger le fichier chez ${e.author}", "Download the file from ${e.author}"), tr("puis l'importer ici", "then import it here")) { e.download?.let { open(it) } }
                p.action(tr("Importer le fichier .fwsc…", "Import the .fwsc file…")) { importFw.launch(arrayOf("*/*")) }
            }
            firmware == "cat:${e.id}" && running -> p.info(tr("État", "Status"), tr("en cours", "running"))
            inst != null && inst.version == e.version -> p.action(tr("Lancer", "Start")) { selectFirmware("cat:${e.id}") }
            inst != null -> p.action(tr("Mettre à jour vers ${e.version} et lancer", "Update to ${e.version} and start"), tr("sur le téléphone : ${inst.version}", "on the phone: ${inst.version}")) { runFirmware(e) }
            else -> p.action(tr("Installer et lancer", "Install and start"), e.size?.let { kb(it) }) { runFirmware(e) }
        }
        p.group(tr("Informations", "Information"))
        p.info("Version", e.version.ifEmpty { "—" })
        p.info(tr("Auteur", "Author"), e.author.ifEmpty { "—" })
        p.info("Licence", e.license)
        e.kind?.let { p.info(tr("Genre", "Kind"), kindName(it)) }
        e.minMhz?.let { p.info(tr("Horloge minimale", "Minimum clock"), tr("$it MHz (mesurée par fm1-emu)", "$it MHz (measured by fm1-emu)")) }
        e.size?.let { p.info(tr("Taille", "Size"), kb(it)) }
        p.info(tr("Vérification", "Check"), if (e.sha256 != null) tr("SHA-256 du Hub", "Hub's SHA-256") else tr("aucune empreinte publiée", "no checksum published"))
        if (e.repo != null || e.hubPage != null || e.installer != null) {
            p.group(tr("Liens", "Links"))
            e.repo?.let { r -> p.nav(tr("Code source", "Source code"), r.removePrefix("https://").removePrefix("github.com/")) { open(r) } }
            e.hubPage?.let { h -> p.nav(tr("Sur The Firmware Hub", "On The Firmware Hub")) { open(h) } }
            e.installer?.let { i -> p.nav(tr("L'installer sur le FM-1", "Install it on the FM-1"), tr("page de l'auteur", "author's page")) { open(i) } }
        }
        if (stateFile("cat:${e.id}").exists()) {
            p.group(tr("Mémoire", "Memory"))
            p.info(tr("Sauvegardé", "Saved"), kb(stateFile("cat:${e.id}").length()) + tr(" · revient à chaque lancement", " · comes back at each start"))
            p.action(tr("Effacer sa mémoire", "Erase its memory"), tr("Ses sons et réglages enregistrés : il repart comme neuf", "Its saved sounds and settings: it starts again as new"), danger = true) { forgetState("cat:${e.id}") }
        }
        if (inst != null) {
            p.group()
            p.action(tr("Supprimer du téléphone", "Remove from the phone"), kb(inst.file.length()), danger = true) {
                if (firmware == "cat:${e.id}" && (running || booting)) {
                    Toast.makeText(this, tr("Il tourne : choisis-en d'abord un autre", "It is running: pick another one first"), Toast.LENGTH_SHORT).show()
                } else { store.delete(e.id); sheet?.refresh() }
            }
        }
    }

    // ---- settings

    private fun outName() = if (outTarget == AudioIo.OUT_FM1) tr("Sur le FM-1", "On the FM-1") else tr("Sur le téléphone", "On the phone")
    private val MIDI_MODES = listOf(
        Triple(tr("Horloge seulement", "Clock only"), tr("Start, stop et horloge : le FM-1 (LOOM-8) mène le tempo", "Start, stop and clock: the FM-1 (LOOM-8) leads the tempo"), 0),
        Triple(tr("Tout vers l'entrée MIDI", "Everything to the MIDI input"), tr("Chaque message va à l'entrée MIDI du firmware émulé", "Each message goes to the emulated firmware's MIDI input"), 1),
        Triple(tr("Piloter le panneau", "Play the panel"), tr("Un profil change notes et CC en touches, boutons et encodeurs", "A profile turns notes and CCs into keys, buttons and encoders"), 2))
    private fun recFormat() = tr("${if (recorder.bits == 32) "32 bits flottant" else "${recorder.bits} bits"} · ", "${if (recorder.bits == 32) "32-bit float" else "${recorder.bits}-bit"} · ") +
        tr("${if (recorder.rate == 44100) "44,1" else "48"} kHz · ${if (recorder.channels == 2) "stéréo" else "mono"}", "${if (recorder.rate == 44100) "44.1" else "48"} kHz · ${if (recorder.channels == 2) "stereo" else "mono"}")
    private fun clockName() = if (mhz == MHZ_AUTO) "Auto · ${clockFor(firmware)} MHz" else "$mhz MHz"

    /** The settings, grouped; [midiOnly]: straight to the MIDI page (the MIDI light in the bar) */
    private fun openSettings(midiOnly: Boolean = false) {
        val sh = Sheet(this).onClose { sheet = null; catalogList = null }
        sheet = sh
        if (midiOnly) { sh.show("MIDI") { midiPage() }; return }
        sh.show(tr("Réglages", "Settings")) {
            group("Firmware")
            nav("Firmware", firmwareLabel(firmware).lowercase().replaceFirstChar { it.uppercase() }) { sh.push("Firmwares") { catalogPage(sh) } }
            group(tr("Son", "Sound"))
            nav(tr("Sortie", "Output"), outName()) { sh.push(tr("Sortie du son", "Sound output")) { outputPage() } }
            nav(tr("Mixeur et EQ", "Mixer and EQ"), mixSummary()) { sh.push(tr("Mixeur et EQ", "Mixer and EQ")) { mixerPage() } }
            toggle(tr("Latence basse", "Low latency"), tr("Réserves de 20-25 ms au lieu de 50-60 : moins de retard, un peu plus de risque de craquements", "20-25 ms buffers instead of 50-60: less delay, a bit more risk of crackles"), lowLatency) {
                lowLatency = it; prefs.edit().putBoolean("lowLatency", it).apply(); Native.setLatency(lowLatency, offsetMs)
            }
            slider(tr("Alignement des deux FM-1", "Aligning the two FM-1s"), offsetMs, -100, 100, { v ->
                when { v > 0 -> tr("FM-1 branché +$v ms", "plugged FM-1 +$v ms"); v < 0 -> tr("émulé +${-v} ms", "emulated +${-v} ms"); else -> "0 ms" } }) {
                offsetMs = it; prefs.edit().putInt("offsetMs", it).apply(); Native.setLatency(lowLatency, offsetMs)
            }
            toggle(tr("Limiteur", "Limiter"), tr("Le mix ne sature pas quand les deux sons sont forts (-1 dBFS) ; enregistrements en 16 et 24 bits compris", "The mix does not clip when both sounds are loud (-1 dBFS); 16 and 24-bit recordings included"), limiter) {
                limiter = it; prefs.edit().putBoolean("limiter", it).apply(); Native.setLimiter(it)
            }
            note(tr("Alignement : si l'un des deux sons arrive après l'autre, retarde celui qui est en avance. En Bluetooth, ", "Alignment: if one sound arrives after the other, delay the one that is early. Over Bluetooth, ") +
                tr("l'enceinte ajoute 150 à 300 ms à tout : un casque filaire (adaptateur ou hub USB-C) ou une enceinte LE Audio en ajoutent beaucoup moins.", "the speaker adds 150 to 300 ms to everything: wired headphones (adapter or USB-C hub) or an LE Audio speaker add much less."))
            group("MIDI")
            nav(tr("MIDI reçu", "MIDI received"), if (panelRemote.state == true) tr("Contrôles du FM-1 (PANEL)", "FM-1 controls (PANEL)") else MIDI_MODES[midiMode].first) { sh.push("MIDI") { midiPage() } }
            group(tr("Enregistrement", "Recording"))
            nav(tr("Contenu", "Content"), if (recorder.mode == Recorder.MODE_MIX) tr("Le mix", "The mix") else tr("Deux pistes", "Two tracks")) { sh.push(tr("Contenu", "Content")) { recContentPage() } }
            nav("Format", recFormat()) { sh.push("Format") { recFormatPage() } }
            note(tr("Bouton ● en haut ; fichiers dans Musique/${Recorder.FOLDER}.", "● button at the top; files in Music/${Recorder.FOLDER}."))
            group(tr("Potards", "Knobs"))
            nav(tr("Vitesse des potards", "Knob speed"), knobSpeedName()) { sh.push(tr("Vitesse des potards", "Knob speed")) { knobSpeedPage() } }
            group(tr("Clavier", "Keyboard"))
            nav(tr("Notes sur les touches", "Notes on the keys"), NOTE_NAMES[noteNames()]) { sh.push(tr("Clavier", "Keyboard")) { keyboardPage() } }
            group("Machine")
            nav(tr("Horloge", "Clock"), clockName()) { sh.push(tr("Horloge", "Clock")) { clockPage() } }
            toggle(tr("Puce flash récente", "Recent flash chip"), tr("UC25HQ80, des FM-1 récents", "UC25HQ80, on recent FM-1s"), newChip) {
                newChip = it; prefs.edit().putBoolean("newChip", it).apply(); restart()
            }
            group(tr("Aide", "Help"))
            nav(tr("Diagnostic audio USB", "USB audio diagnostic")) { sh.push("Diagnostic") { diagnosticPage() } }
            nav(tr("À propos et licences", "About and licences")) { sh.push(tr("À propos", "About")) { aboutPage() } }
        }
    }

    private fun balName(v: Int) = when { v < 0 -> tr("G ${-v}", "L ${-v}"); v > 0 -> tr("D $v", "R $v"); else -> tr("centre", "centre") }
    private fun mixSummary() = tr("émulé $gEmuPct % · FM-1 $gExtPct % · master $masterPct %", "emulated $gEmuPct % · FM-1 $gExtPct % · master $masterPct %")

    /** a balance near the centre is the centre (a finger can hardly land on 0) */
    private fun snap(v: Int) = if (kotlin.math.abs(v) < 5) 0 else v

    private fun applyMix() {
        Native.setMix(balEmuPct / 100f, balExtPct / 100f, if (muted[2]) 0f else masterPct / 100f)
        for (s in 0 until 3) Native.setEq(s, eqDb[3 * s].toFloat(), eqDb[3 * s + 1].toFloat(), eqDb[3 * s + 2].toFloat())
        syncMixer()
    }

    /** the emulated FM-1's EQ (0..2) is the firmware's; the real FM-1's and the master's (3..8) the app's */
    private fun putEq(i: Int, v: Int) = if (i < 3) fwPut("eq$i", v) else prefs.edit().putInt("eq$i", v).apply()

    private fun dbName(v: Int) = if (v > 0) "+$v dB" else if (v < 0) "$v dB" else "0 dB"
    private fun eqSummary(s: Int) = if ((0 until 3).all { eqDb[3 * s + it] == 0 }) tr("plat", "flat")
        else (0 until 3).joinToString(" · ") { b -> listOf("G", "M", "A")[b] + " " + eqDb[3 * s + b].let { if (it > 0) "+$it" else "$it" } }

    /** One EQ: three bands, ±12 dB. */
    private fun Sheet.Page.eqPage(s: Int) {
        group()
        val names = listOf(tr("Grave (150 Hz, plateau)", "Low (150 Hz, shelf)"), tr("Médium (1 kHz, cloche)", "Mid (1 kHz, bell)"), tr("Aigu (6 kHz, plateau)", "High (6 kHz, shelf)"))
        for (b in 0 until 3) slider(names[b], eqDb[3 * s + b], -12, 12, { dbName(it) }) {
            eqDb[3 * s + b] = it; putEq(3 * s + b, it); applyMix()
        }
        action(tr("Remettre à plat", "Flatten")) {
            for (b in 0 until 3) { eqDb[3 * s + b] = 0; putEq(3 * s + b, 0) }
            applyMix(); sheet?.refresh()
        }
    }

    /** Volume and balance of each source, and the master: what plays, and the recording « Le mix ». */
    private fun Sheet.Page.mixerPage() {
        group(tr("FM-1 émulé", "Emulated FM-1"))
        slider("Volume", gEmuPct, 0, 150, { "$it %" }) { gEmuPct = it; applyGains(); fwPut("gEmu", it); syncMixer() }
        slider("Balance", balEmuPct, -100, 100, { balName(it) }) { balEmuPct = snap(it); fwPut("balEmu", balEmuPct); applyMix() }
        nav("EQ", eqSummary(0)) { sheet?.push(tr("EQ · FM-1 émulé", "EQ · emulated FM-1")) { eqPage(0) } }
        group(tr("FM-1 branché (USB)", "Plugged FM-1 (USB)"))
        slider("Volume", gExtPct, 0, 150, { "$it %" }) { gExtPct = it; applyGains(); prefs.edit().putInt("gExt", it).apply(); syncMixer() }
        slider("Balance", balExtPct, -100, 100, { balName(it) }) { balExtPct = snap(it); prefs.edit().putInt("balExt", it).apply(); applyMix() }
        nav("EQ", eqSummary(1)) { sheet?.push(tr("EQ · FM-1 branché", "EQ · plugged FM-1")) { eqPage(1) } }
        group(tr("Sortie", "Output"))
        slider("Master", masterPct, 0, 150, { "$it %" }) { masterPct = it; prefs.edit().putInt("master", it).apply(); applyMix() }
        nav("EQ master", eqSummary(2)) { sheet?.push("EQ · master") { eqPage(2) } }
        action(tr("Tout remettre à zéro", "Reset everything")) {
            gEmuPct = 100; gExtPct = 100; balEmuPct = 0; balExtPct = 0; masterPct = 100
            applyGains()
            prefs.edit().putInt("gExt", 100).putInt("balExt", 0).putInt("master", 100).apply()
            fwPut("gEmu", 100); fwPut("balEmu", 0)
            for (i in 0 until 9) { eqDb[i] = 0; putEq(i, 0) }
            applyMix(); sheet?.refresh()
        }
        note(tr("Ordre : l'EQ de chaque FM-1, son volume et sa balance, puis le master et son EQ, puis le limiteur. ", "Order: each FM-1's EQ, its volume and balance, then the master and its EQ, then the limiter. ") +
            tr("Vaut pour ce qui sort et pour l'enregistrement « Le mix ». « Deux pistes » ", "Applies to what goes out and to the \"The mix\" recording. \"Two tracks\" ") +
            tr("garde les deux sons bruts, pour les retravailler. Balance : vers un côté, l'autre canal baisse ; ", "keeps both sounds raw, to rework them. Balance: towards one side, the other channel goes down; ") +
            tr("l'image stéréo de chaque FM-1 est gardée.", "each FM-1's stereo image is kept."))
    }

    private fun Sheet.Page.outputPage() {
        group()
        choice(tr("Sur le FM-1", "On the FM-1"), tr("Le téléphone lui envoie l'émulé ; le FM-1 mixe avec son propre son (si son firmware a une sortie USB)", "The phone sends it the emulated one; the FM-1 mixes it with its own sound (if its firmware has a USB output)"),
            outTarget == AudioIo.OUT_FM1) { setOut(AudioIo.OUT_FM1) }
        choice(tr("Sur le téléphone", "On the phone"), tr("Le mix complet : un casque branché (USB-C ou adaptateur) d'abord, puis le Bluetooth, sinon le haut-parleur", "The full mix: plugged headphones (USB-C or adapter) first, then Bluetooth, else the speaker"),
            outTarget == AudioIo.OUT_PHONE) { setOut(AudioIo.OUT_PHONE) }
        note(tr("Sur le téléphone, le FM-1 joue aussi son propre son : baisse son MASTER ; avec LOOM-8, Setup > USB audio sur « stereo » pour qu'il envoie quand même à plein niveau.", "On the phone, the FM-1 also plays its own sound: turn its MASTER down; with LOOM-8, Setup > USB audio on \"stereo\" so that it still sends at full level."))
    }

    private fun setOut(t: Int) {
        outTarget = t
        prefs.edit().putInt("out", t).apply()
        audio.target = t
        audio.route()
        fxOutput()
        showSwitches()
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
            if (err.isNotEmpty()) { status.text = tr("Effets : $err", "Effects: $err"); return }
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
        return tr("$nm : ses touches, boutons, encodeurs et MASTER pilotent l'émulé, ses voyants montrent ceux de l'émulé. ", "$nm: its keys, buttons, encoders and MASTER play the emulated one, its lights show the emulated one's. ") +
            tr("Raccourcis : appui long sur MIDI ici, GLO + FX une seconde sur le FM-1.", "Shortcuts: long press on MIDI here, GLO + FX for a second on the FM-1.")
    }

    private fun Sheet.Page.midiPage() {
        group(tr("Contrôles du FM-1 branché", "Plugged FM-1's controls"))
        when (panelRemote.state) {
            null -> note(tr("Aucun LOOM-8 avec PANEL MIDI branché en USB : ses contrôles restent à lui. (LOOM-8 de la version ", "No LOOM-8 with PANEL MIDI plugged over USB: its controls stay its own. (LOOM-8 from the ") +
                tr("forkée, avec PANEL MIDI dans Setup > USB and MIDI.)", "forked version, with PANEL MIDI in Setup > USB and MIDI.)"))
            else -> toggle(tr("Piloter l'émulé", "Play the emulated one"), panelSub(), panelRemote.state == true) { panelRemote.set(it) }
        }
        group(tr("Ce que fait le MIDI reçu", "What received MIDI does"))
        for ((t, sub, k) in MIDI_MODES) choice(t, sub, midiMode == k) { midiMode = k; fwPut("midiMode", k); applyMidiMode() }
        group(tr("Profil du contrôleur (« Piloter le panneau »)", "Controller profile (\"Play the panel\")"))
        val names = (assets.list("profiles")?.toList() ?: emptyList()) + (File(filesDir, "profiles").list()?.toList() ?: emptyList())
        for (nm in names) choice(nm.removeSuffix(".json"), null, nm == profileName) { profileName = nm; fwPutStr("profile", nm); applyMidiMode() }
        action(tr("Charger un profil JSON…", "Load a JSON profile…")) { pickProfile.launch(arrayOf("*/*")) }
        note(tr("remotefm1-panel : choisi tout seul quand LOOM-8 prête ses contrôles, l'ancien réglage revient après.", "remotefm1-panel: chosen on its own when LOOM-8 lends its controls, the previous setting comes back after."))
    }

    private fun Sheet.Page.recContentPage() {
        group()
        choice(tr("Le mix", "The mix"), tr("Ce que tu entends, curseurs compris", "What you hear, faders included"), recorder.mode == Recorder.MODE_MIX) { setRec("recMode", Recorder.MODE_MIX) { recorder.mode = it } }
        choice(tr("Deux pistes", "Two tracks"), tr("« nom - émulé » et « nom - FM-1 », alignées, avant les curseurs", "\"name - emulated\" and \"name - FM-1\", aligned, before the faders"), recorder.mode == Recorder.MODE_SPLIT) { setRec("recMode", Recorder.MODE_SPLIT) { recorder.mode = it } }
    }

    private fun Sheet.Page.recFormatPage() {
        group(tr("Résolution", "Resolution"))
        choice(tr("16 bits", "16-bit"), tr("Le format des sources", "The sources' format"), recorder.bits == 16) { setRec("recBits", 16) { recorder.bits = it } }
        choice(tr("24 bits", "24-bit"), tr("La somme du mix sans saturation", "The mix's sum without clipping"), recorder.bits == 24) { setRec("recBits", 24) { recorder.bits = it } }
        choice(tr("32 bits flottant", "32-bit float"), tr("Aucune saturation, pour retravailler", "No clipping at all, to rework"), recorder.bits == 32) { setRec("recBits", 32) { recorder.bits = it } }
        group(tr("Fréquence", "Sample rate"))
        choice("44,1 kHz", tr("Celle des sources", "The sources'"), recorder.rate == 44100) { setRec("recRate", 44100) { recorder.rate = it } }
        choice("48 kHz", tr("Rééchantillonné : vidéo, projets à 48 kHz", "Resampled: video, 48 kHz projects"), recorder.rate == 48000) { setRec("recRate", 48000) { recorder.rate = it } }
        group(tr("Canaux", "Channels"))
        choice(tr("Stéréo", "Stereo"), null, recorder.channels == 2) { setRec("recCh", 2) { recorder.channels = it } }
        choice("Mono", null, recorder.channels == 1) { setRec("recCh", 1) { recorder.channels = it } }
        note(tr("Vaut pour le prochain enregistrement.", "Applies to the next recording."))
    }

    private fun setRec(key: String, v: Int, set: (Int) -> Unit) { set(v); prefs.edit().putInt(key, v).apply() }

    private fun Sheet.Page.clockPage() {
        val m = entryOf(firmware)?.minMhz
        group()
        choice("Auto", if (m != null) tr("Le minimum mesuré pour ce firmware ($m MHz) avec une marge : ${clockFor(firmware).takeIf { mhz == MHZ_AUTO } ?: (minOf(240, maxOf(80, (m * 5 / 4 + 9) / 10 * 10)))} MHz, moins de travail pour le téléphone", "The minimum measured for this firmware ($m MHz) with a margin: ${clockFor(firmware).takeIf { mhz == MHZ_AUTO } ?: (minOf(240, maxOf(80, (m * 5 / 4 + 9) / 10 * 10)))} MHz, less work for the phone")
            else tr("Pas de mesure pour ce firmware : 240 MHz", "No measurement for this firmware: 240 MHz"), mhz == MHZ_AUTO) { setClock(MHZ_AUTO) }
        for (v in intArrayOf(240, 200, 160, 120)) choice("$v MHz", if (v == 240) tr("Celle du vrai FM-1", "The real FM-1's") else null, mhz == v) { setClock(v) }
        note(tr("Redémarre le firmware émulé. Si le son craque ou que la charge dépasse 100 %, baisse l'horloge.", "Restarts the emulated firmware. If the sound crackles or the load goes over 100 %, lower the clock."))
    }

    private fun setClock(v: Int) { if (v != mhz) { mhz = v; fwPut("mhz", v); restart() } }

    private fun Sheet.Page.diagnosticPage() {
        val diag = Diagnostic.report(this@MainActivity) + tr("\nCapture : ", "\nCapture: ") + audio.captureStatus() + "\n" + audio.usbReport() +
            tr("\nMoteur : ", "\nEngine: ") + (if (running) Native.status() else tr("éteint", "off")) +
            tr(" (ADPF : session d'indication de performance Android sur le fil du moteur, Android 13 et plus)",
               " (ADPF: Android performance hint session on the engine thread, Android 13 and later)")
        group()
        action(tr("Copier le diagnostic", "Copy the diagnostic")) {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("FM1 Mix diagnostic", diag))
            Toast.makeText(this@MainActivity, tr("Diagnostic copié", "Diagnostic copied"), Toast.LENGTH_SHORT).show()
        }
        group()
        text(diag, 11f, selectable = true)
    }

    private fun Sheet.Page.aboutPage() {
        group()
        text(tr("FM1 Mix $APP_VERSION, bêta. Un FM-1 émulé sur le téléphone, mixé avec un vrai FM-1 en USB, enregistré, découpé.", "FM1 Mix $APP_VERSION, beta. An emulated FM-1 on the phone, mixed with a real FM-1 over USB, recorded, sliced."))
        group("Licences")
        info("FM1 Mix", "GPL-3.0")
        info(tr("Émulateur fm1-emu", "fm1-emu emulator"), "MIT · Joakim Eriksson")
        info(tr("Découpe (CHOP de SLOOP)", "Slicing (SLOOP's CHOP)"), "GPL-3.0")
        info(tr("Envoi vers LOOM-8", "Sending to LOOM-8"), "MPL-2.0 · Charles Vestal")
        info(tr("Liste des firmwares", "Firmware list"), "fm1-emu, The Firmware Hub")
        info(tr("Effets du pad : code généré par Faust", "Pad effects: code generated by Faust"), tr("GRAME · bibliothèques Faust", "GRAME · Faust libraries"))
        info(tr("Filtre, Hall, Phaser, Flanger, Overdrive, Pompe", "Filter, Hall, Phaser, Flanger, Overdrive, Pump"), "MIT (STK-4.3) · Julius O. Smith III")
        info(tr("Écho, Pitch, lissages", "Echo, Pitch, smoothing"), tr("LGPL avec exception Faust · GRAME", "LGPL with the Faust exception · GRAME"))
        info(tr("Nuage diffus", "Diffuse cloud"), "GPL-2.0+ · Julian Parker, Till Bovermann")
        info(tr("Tape stop, Beat repeat, moteur du pad", "Tape stop, Beat repeat, pad engine"), "GPL-3.0 · FM1 Mix")
        nav(tr("Code de fm1-emu", "fm1-emu's code")) { open("https://github.com/joakimeriksson/fm1-emu") }
        nav("SLOOP") { open("https://github.com/isod89/sloop-fm1") }
        nav("The Firmware Hub") { open(FirmwareCatalog.HUB) }
        nav("Faust") { open("https://faust.grame.fr") }
        nav(tr("Bibliothèques Faust", "Faust libraries")) { open("https://github.com/grame-cncm/faustlibraries") }
        note(tr("Chaque firmware reste sous la licence de son auteur (sa page dans le catalogue).", "Each firmware stays under its author's licence (its page in the catalogue)."))
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
            Toast.makeText(this, tr("Pas de LOOM-8 avec PANEL MIDI branché en USB", "No LOOM-8 with PANEL MIDI plugged over USB"), Toast.LENGTH_SHORT).show()
            return
        }
        panelRemote.set(!s)
    }

    /** LOOM-8 lent its panel (true), took it back (false) or is gone (null). */
    private fun onPanel(s: Boolean?) {
        val on = s == true
        showSwitches()
        if (on) {
            // the panel's MIDI is read by the profile made for it
            if (midiMode != 2 || profileName != PANEL_PROFILE) {
                if (beforePanel == null) beforePanel = Pair(midiMode, profileName)
                midiMode = 2
                profileName = PANEL_PROFILE
                fwPut("midiMode", 2); fwPutStr("profile", PANEL_PROFILE)
                applyMidiMode()
            }
            Toast.makeText(this, tr("Contrôles du FM-1 → émulé", "FM-1 controls → emulated"), Toast.LENGTH_SHORT).show()
        } else {
            beforePanel?.let { (m, p) ->
                midiMode = m
                profileName = p
                fwPut("midiMode", m); fwPutStr("profile", p)
                applyMidiMode()
            }
            beforePanel = null
            if (s == false) Toast.makeText(this, tr("Contrôles rendus à LOOM-8", "Controls given back to LOOM-8"), Toast.LENGTH_SHORT).show()
        }
        sheet?.refresh()
    }

    private fun applyMidiMode() {
        if (!running) return
        val json = if (midiMode == 2) profileJson(profileName) else null
        val err = Native.setMidiMode(midiMode, json)
        modeMsg = if (err.isEmpty()) "" else tr("· profil : $err", "· profile: $err")
    }

    // ------------------------------------------------------------------ helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(fillColor: Int, strokeColor: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(fillColor)
        if (strokeColor != 0) setStroke(dp(2), strokeColor)
    }
}
