// SPDX-License-Identifier: GPL-3.0-only
// The slicer's screen: SLOOP's CHOP panel (web/editor.html, https://github.com/isod89/sloop-fm1, GPL-3.0-only) for a
// phone: open a recording, find the hits, tap them while it plays, a tempo grid or equal parts, keep or leave out
// chops, export them as WAV files, or send a loop or the chops to the FM-1's firmware when it takes samples.
package app.fm1mix

import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SlicerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URI = "uri"
        /** longest recording opened (the analysis and the sound stay in memory) */
        const val MAX_SECONDS = 300
        private val AMBER = 0xFFF29B38.toInt()
        private val INK = 0xFFD9D6CE.toInt()
        private val DIM = 0xFF8E8C87.toInt()
        private val BG = 0xFF26272A.toInt()
    }

    private val ui = Handler(Looper.getMainLooper())
    private var model: SlicerModel? = null
    private var fileName = ""
    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var wave: WaveView
    private lateinit var selInfo: TextView
    private lateinit var keepBox: CheckBox
    private lateinit var fwInfo: TextView
    private lateinit var fwBox: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var sensBar: SeekBar
    private lateinit var bpmField: EditText
    private lateinit var divSpin: Spinner
    private lateinit var partsField: EditText
    private lateinit var barsField: EditText
    private lateinit var snapBox: CheckBox
    private lateinit var levelBox: CheckBox
    private val prefs by lazy { getSharedPreferences("fm1mix", MODE_PRIVATE) }
    private val divs = listOf(tr("1 mesure", "1 bar") to 4.0, "1/2" to 2.0, "1/4" to 1.0, "1/8" to 0.5, "1/16" to 0.25)

    // playback
    private var track: AudioTrack? = null
    @Volatile private var playing = false
    @Volatile private var playFrom = 0      // file frames
    @Volatile private var playTo = 0
    private var playThread: Thread? = null

    // the FM-1's firmware
    @Volatile private var session: SampleTargets.Session? = null
    @Volatile private var busy = false

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) load(uri) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        bar.addView(btn("←") { finish() })
        title = TextView(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(dp(8), 0, dp(8), 0); text = tr("DÉCOUPE", "SLICE")
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btn(tr("Ouvrir", "Open")) { chooseFile() })
        root.addView(bar)
        info = TextView(this).apply { textSize = 11f; setTextColor(DIM); setPadding(dp(14), 0, dp(14), dp(4)) }
        root.addView(info)
        wave = WaveView(this)
        root.addView(wave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(24)) }
        // listen and tap
        snapBox = CheckBox(this).apply { text = tr("caler", "snap"); isChecked = true; setTextColor(INK) }
        col.addView(row(btn(tr("▶ Tout", "▶ All")) { playAll() }, btn("■") { stopPlay() }, btn("TAP") { tap() }, snapBox, btn(tr("Effacer", "Erase")) { edit { it.clear() } }))
        col.addView(hint(tr("TAP pendant l'écoute : un repère sur le coup entendu (« caler » le met sur l'attaque la plus proche). ", "TAP while listening: a marker on the beat heard (\"snap\" puts it on the nearest attack). ") +
            tr("Toucher la forme d'onde : choisir et écouter un morceau ; appui long : le garder ou l'écarter.", "Tap the waveform: pick and hear a slice; long press: keep it or drop it.")))
        // hits
        sensBar = SeekBar(this).apply { max = 9; progress = prefs.getInt("chopSens", 5) - 1 }
        col.addView(row(btn(tr("Attaques", "Attacks")) { edit { it.detect(sensBar.progress + 1) }; prefs.edit().putInt("chopSens", sensBar.progress + 1).apply() },
            label(tr("sensibilité", "sensitivity")), sensBar))
        // grid
        bpmField = number(prefs.getString("chopBpm", "120") ?: "120", true)
        divSpin = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, divs.map { it.first })
            setSelection(prefs.getInt("chopDiv", 2))
        }
        col.addView(row(btn(tr("Grille", "Grid")) { grid() }, bpmField, label("BPM"), divSpin))
        // equal parts
        partsField = number(prefs.getString("chopParts", "8") ?: "8", false)
        col.addView(row(btn(tr("Parts égales", "Equal parts")) { parts() }, partsField, label(tr("morceaux", "slices"))))
        col.addView(hint(tr("Grille et parts égales vont du premier au dernier repère quand il y en a deux, sinon du premier (ou du début) à la fin.", "Grid and equal parts go from the first marker to the last when there are two, else from the first (or the start) to the end.")))

        // the selected chop
        selInfo = TextView(this).apply { setTextColor(INK); textSize = 14f }
        keepBox = CheckBox(this).apply {
            text = tr("garder", "keep"); setTextColor(INK)
            setOnClickListener { val m = model ?: return@setOnClickListener; if (m.sel >= 0) { m.toggleKeep(m.sel); refresh() } }
        }
        col.addView(section(tr("Morceau choisi", "Chosen slice")))
        col.addView(row(selInfo, keepBox, btn("▶") { playSel() }, btn(tr("✕ repère", "✕ marker")) { edit { it.removeSelected() } }))

        // export
        col.addView(section(tr("Exporter", "Export")))
        levelBox = CheckBox(this).apply { text = tr("niveau commun (le plus fort à -0,8 dB, écarts gardés, comme SLOOP)", "common level (the loudest at -0.8 dB, differences kept, like SLOOP)"); isChecked = prefs.getBoolean("chopLevel", true); setTextColor(INK) }
        col.addView(levelBox)
        col.addView(row(btn(tr("Morceaux gardés → WAV", "Kept slices → WAV")) { exportPieces() }, btn(tr("Boucle → WAV", "Loop → WAV")) { exportLoop() }))
        col.addView(hint(tr("Dans Musique/${Recorder.FOLDER}/Découpes, au format de l'enregistrement (fréquence, canaux, résolution).", "In Music/${Recorder.FOLDER}/Découpes, in the recording's format (rate, channels, resolution).")))

        // the FM-1
        col.addView(section(tr("Vers le FM-1", "To the FM-1")))
        fwInfo = TextView(this).apply { setTextColor(INK); textSize = 13f; text = tr("Recherche du firmware…", "Looking for the firmware…") }
        col.addView(fwInfo)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
        col.addView(progress)
        barsField = number("1", false)
        fwBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fwBox.addView(row(btn(tr("Boucle →", "Loop →")) { sendLoop() }, barsField, label(tr("mesures", "bars")), btn(tr("Morceaux →", "Slices →")) { sendPieces() }))
        fwBox.addView(row(btn(tr("Annuler le dernier envoi", "Undo the last send")) { restore() }, btn(tr("Rechercher", "Search")) { detect() }))
        col.addView(fwBox)
        col.addView(row(btn(tr("Rechercher le FM-1", "Look for the FM-1")) { detect() }))

        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val u = intent?.getStringExtra(EXTRA_URI)
        if (u != null) load(Uri.parse(u)) else chooseFile()
        detect()
    }

    override fun onStop() {
        stopPlay()
        super.onStop()
    }

    override fun onDestroy() {
        session?.let { s -> Thread { runCatching { s.close() } }.start() }
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ files

    /** the app's recordings (Music/FM1 Mix), newest first, or any file */
    private fun chooseFile() {
        val names = ArrayList<String>()
        val uris = ArrayList<Uri>()
        val coll = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            contentResolver.query(coll, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH),
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?", arrayOf("${Environment.DIRECTORY_MUSIC}/${Recorder.FOLDER}%"),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && names.size < 200) {
                    val n = c.getString(1) ?: continue
                    if (!n.lowercase().endsWith(".wav")) continue
                    val rel = c.getString(2) ?: ""
                    names += if (rel.contains("Découpes")) "✂ $n" else n
                    uris += ContentUris.withAppendedId(coll, c.getLong(0))
                }
            }
        }
        val items = names + tr("Autre fichier WAV…", "Another WAV file…")
        AlertDialog.Builder(this).setTitle(tr("Ouvrir un enregistrement", "Open a recording"))
            .setItems(items.toTypedArray()) { _, i -> if (i < uris.size) load(uris[i]) else openFile.launch(arrayOf("audio/*")) }
            .setNegativeButton(tr("Annuler", "Cancel")) { _, _ -> if (model == null) finish() }
            .show()
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: tr("son", "sound")
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: tr("son", "sound")
    }

    private fun load(uri: Uri) {
        stopPlay()
        info.text = tr("Lecture…", "Reading…")
        Thread {
            val r = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException(tr("illisible", "unreadable"))
                val snd = WavIO.read(bytes)
                if (snd.frames.toDouble() / snd.rate > MAX_SECONDS) throw IllegalStateException(tr("plus de ${MAX_SECONDS / 60} minutes : découpe d'abord un passage plus court", "over ${MAX_SECONDS / 60} minutes: slice a shorter part first"))
                SlicerModel(snd)
            }
            ui.post {
                r.onSuccess { m ->
                    model = m
                    fileName = displayName(uri).removeSuffix(".wav").removeSuffix(".WAV")
                    title.text = fileName
                    val s = m.sound
                    info.text = "%.2f s · %d Hz · %s · %s".format(m.seconds, s.rate, if (s.channels == 1) "mono" else tr("${s.channels} canaux", "${s.channels} channels"),
                        if (s.float) tr("32 bits flottant", "32-bit float") else tr("${s.bits} bits", "${s.bits}-bit"))
                    wave.setModel(m)
                    refresh()
                }.onFailure { e -> info.text = tr("Impossible d'ouvrir : ${e.message}", "Cannot open: ${e.message}") }
            }
        }.start()
    }

    // ------------------------------------------------------------------ editing

    private fun edit(f: (SlicerModel) -> Unit) {
        val m = model ?: return
        f(m)
        refresh()
    }

    private fun grid() {
        val bpm = bpmField.text.toString().replace(',', '.').toDoubleOrNull() ?: 120.0
        prefs.edit().putString("chopBpm", bpmField.text.toString()).putInt("chopDiv", divSpin.selectedItemPosition).apply()
        edit { it.grid(bpm, divs[divSpin.selectedItemPosition].second) }
    }

    private fun parts() {
        val n = (partsField.text.toString().toIntOrNull() ?: 8).coerceIn(1, 64)
        prefs.edit().putString("chopParts", n.toString()).apply()
        edit { it.equal(n) }
    }

    private fun tap() {
        val m = model ?: return
        val t = track
        if (!playing || t == null) { Toast.makeText(this, tr("TAP pendant l'écoute (▶)", "TAP while listening (▶)"), Toast.LENGTH_SHORT).show(); return }
        m.tap((playFrom + t.playbackHeadPosition).toLong(), snapBox.isChecked)
        refresh()
    }

    private fun refresh() {
        val m = model
        wave.invalidate()
        if (m == null) return
        val p = m.pieces()
        val k = p.count { !it.off }
        if (m.sel in p.indices) {
            val c = p[m.sel]
            selInfo.text = tr("n° %d · %.3f s", "no. %d · %.3f s").format(m.sel + 1, (c.end - c.start).toDouble() / Chop.RATE)
            keepBox.isEnabled = true
            keepBox.isChecked = !c.off
        } else {
            selInfo.text = if (p.isEmpty()) tr("aucun repère", "no markers") else tr("${p.size} morceaux, $k gardés", "${p.size} slices, $k kept")
            keepBox.isEnabled = false
        }
        // the loop's bars from the tempo, when one is set
        val bpm = bpmField.text.toString().replace(',', '.').toDoubleOrNull()
        val kept = m.kept()
        if (bpm != null && kept.isNotEmpty()) {
            val secs = (kept.last().end - kept.first().start).toDouble() / Chop.RATE
            barsField.setText(m.barsAt(bpm, secs).toString())
        }
    }

    // ------------------------------------------------------------------ playback

    private fun playAll() { val m = model ?: return; play(0, m.sound.frames) }

    private fun playSel() {
        val m = model ?: return
        val p = m.pieces()
        if (m.sel !in p.indices) return
        play(m.toFile(p[m.sel].start), m.toFile(p[m.sel].end))
    }

    private fun play(from: Int, to: Int) {
        stopPlay()
        val m = model ?: return
        val s = m.sound
        val ch = if (s.channels >= 2) 2 else 1
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(s.rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(s.rate, if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        playFrom = from
        playTo = to
        playing = true
        t.play()
        playThread = Thread {
            val block = 1024
            val buf = FloatArray(block * ch)
            var f = from
            while (playing && f < to) {
                val n = minOf(block, to - f)
                for (i in 0 until n) {
                    val base = (f + i) * s.channels
                    if (ch == 2) { buf[2 * i] = s.data[base]; buf[2 * i + 1] = s.data[base + 1] } else buf[i] = s.data[base]
                }
                t.write(buf, 0, n * ch, AudioTrack.WRITE_BLOCKING)
                f += n
            }
            // let the end play out, then stop
            while (playing && t.playbackHeadPosition < to - from) Thread.sleep(10)
            ui.post { if (track === t) stopPlay() }
        }.also { it.start() }
        ui.post(head)
    }

    private val head = object : Runnable {
        override fun run() {
            val t = track ?: return
            val m = model ?: return
            if (!playing) return
            wave.playhead = ((playFrom + t.playbackHeadPosition).toDouble() * Chop.RATE / m.sound.rate).toInt()
            wave.invalidate()
            ui.postDelayed(this, 30)
        }
    }

    private fun stopPlay() {
        playing = false
        ui.removeCallbacks(head)
        val t = track
        track = null
        runCatching { playThread?.join(300) }
        playThread = null
        if (t != null) { runCatching { t.pause(); t.flush(); t.stop() }; runCatching { t.release() } }
        if (::wave.isInitialized) { wave.playhead = -1; wave.invalidate() }
    }

    // ------------------------------------------------------------------ export

    private fun saveWav(folder: String, name: String, bytes: ByteArray): Boolean {
        val v = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/${Recorder.FOLDER}/Découpes/$folder")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v) ?: return false
        return try {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException()
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            runCatching { contentResolver.delete(uri, null, null) }
            false
        }
    }

    private fun outBits(s: WavIO.Sound) = if (s.float) 32 else if (s.bits == 24) 24 else 16

    private fun askName(title: String, def: String, then: (String) -> Unit) {
        val field = EditText(this).apply { setText(def); setSelectAllOnFocus(true); isSingleLine = true }
        AlertDialog.Builder(this).setTitle(title)
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) })
            .setPositiveButton("OK") { _, _ -> then(Recorder.clean(field.text.toString())) }
            .setNegativeButton(tr("Annuler", "Cancel"), null).show()
        field.requestFocus()
    }

    private fun exportPieces() {
        val m = model ?: return
        val use = m.kept()
        if (use.isEmpty()) { toast(tr("Aucun morceau gardé : pose des repères (Attaques, TAP, Grille, Parts égales)", "No slice kept: place markers (Attacks, TAP, Grid, Equal parts)")); return }
        prefs.edit().putBoolean("chopLevel", levelBox.isChecked).apply()
        askName(tr("Nom des morceaux", "Slices' name"), fileName) { base ->
            val gain = if (levelBox.isChecked) m.commonGain(use) else 1f
            Thread {
                var ok = 0
                for ((k, c) in use.withIndex()) {
                    val b = WavIO.write(m.render(c, gain), m.sound.channels, m.sound.rate, outBits(m.sound))
                    if (saveWav(base, "%s %02d".format(base, c.i + 1), b)) ok++
                    ui.post { info.text = "Export ${k + 1}/${use.size}" }
                }
                ui.post { toast(tr("$ok morceau(x) dans Musique/${Recorder.FOLDER}/Découpes/$base", "$ok slice(s) in Music/${Recorder.FOLDER}/Découpes/$base")); info.text = "" }
            }.start()
        }
    }

    private fun exportLoop() {
        val m = model ?: return
        val k = m.kept()
        if (k.isEmpty()) { toast(tr("Aucun morceau gardé", "No slice kept")); return }
        askName(tr("Nom de la boucle", "Loop name"), tr("$fileName boucle", "$fileName loop")) { name ->
            val whole = Chop.Piece(k.first().start, k.last().end, 0, false, k.last().end)
            val gain = if (levelBox.isChecked) m.commonGain(listOf(whole)) else 1f
            Thread {
                val ok = saveWav(fileName, name, WavIO.write(m.render(whole, gain), m.sound.channels, m.sound.rate, outBits(m.sound)))
                ui.post { toast(if (ok) tr("Boucle dans Musique/${Recorder.FOLDER}/Découpes/$fileName", "Loop in Music/${Recorder.FOLDER}/Découpes/$fileName") else tr("Export impossible", "Export failed")) }
            }.start()
        }
    }

    // ------------------------------------------------------------------ the FM-1's firmware

    /** find the plugged FM-1 and what its firmware can take (SampleTargets) */
    private fun detect() {
        if (busy) return
        busy = true
        fwInfo.text = tr("Recherche du firmware…", "Looking for the firmware…")
        val old = session
        session = null
        Thread {
            runCatching { old?.close() }
            val found = runCatching { SampleTargets.detect(this) }.getOrNull()
            ui.post {
                busy = false
                val s = found?.session
                session = s
                if (s != null) {
                    fwInfo.text = tr("${s.target.label} détecté (${found.deviceName}).\n${s.target.can.note}", "${s.target.label} found (${found.deviceName}).\n${s.target.can.note}")
                    fwBox.visibility = View.VISIBLE
                } else {
                    fwBox.visibility = View.GONE
                    fwInfo.text = when (found?.deviceName) {
                        null -> tr("Aucun FM-1 branché en USB. Les morceaux s'exportent en WAV.", "No FM-1 plugged over USB. Slices export as WAV.")
                        else -> tr("${found.deviceName} : ce firmware ne prend pas d'échantillons depuis l'appli (ou n'a pas répondu). ", "${found.deviceName}: this firmware takes no samples from the app (or did not answer). ") +
                            tr("Exporte en WAV. Firmwares pris en charge : ${SampleTargets.ALL.joinToString { it.label }}.", "Export as WAV. Supported firmwares: ${SampleTargets.ALL.joinToString { it.label }}.")
                    }
                }
            }
        }.start()
    }

    private fun runOnFirmware(what: String, job: (SampleTargets.Session, (String, Double) -> Unit) -> String) {
        val s = session ?: return toast(tr("Aucun firmware compatible", "No compatible firmware"))
        if (busy) return
        busy = true
        stopPlay()
        progress.visibility = View.VISIBLE
        progress.progress = 0
        fwInfo.text = what
        Thread {
            val r = runCatching {
                job(s) { step, f -> ui.post { fwInfo.text = step; progress.progress = (f * 1000).toInt() } }
            }
            ui.post {
                busy = false
                progress.visibility = View.GONE
                r.onSuccess { fwInfo.text = it; toast(it) }.onFailure { fwInfo.text = tr("Échec : ${it.message}", "Failed: ${it.message}") }
            }
        }.start()
    }

    private fun sendLoop() {
        val m = model ?: return
        if (m.kept().isEmpty()) return toast(tr("Aucun morceau gardé : la boucle va du premier au dernier gardé", "No slice kept: the loop goes from the first kept to the last"))
        val bars = (barsField.text.toString().toIntOrNull() ?: 1).coerceIn(1, LoomSamples.BARS_MAX)
        askName(tr("Nom dans le firmware (11 lettres)", "Name in the firmware (11 letters)"), fileName.take(11)) { name ->
            val x = m.loopMono()
            confirmThen(tr("Ajouter la boucle « ${LoomSamples.cleanName(name)} » ($bars mesure(s)) à ${session?.target?.label} ?", "Add the loop \"${LoomSamples.cleanName(name)}\" ($bars bar(s)) to ${session?.target?.label}?")) {
                runOnFirmware(tr("Envoi de la boucle…", "Sending the loop…")) { s, p ->
                    s.add(listOf(SampleTargets.NewSample(name, x, m.sound.rate, LoomSamples.LOOP_ROOT, bars)), p)
                }
            }
        }
    }

    private fun sendPieces() {
        val m = model ?: return
        val use = m.kept()
        if (use.isEmpty()) return toast(tr("Aucun morceau gardé", "No slice kept"))
        val can = session?.target?.can ?: return
        if (use.size > can.maxSamples) return toast(tr("${use.size} morceaux : ${session?.target?.label} en prend ${can.maxSamples} au plus", "${use.size} slices: ${session?.target?.label} takes ${can.maxSamples} at most"))
        askName(tr("Début des noms (les numéros suivent)", "Start of the names (numbers follow)"), fileName.take(8)) { base ->
            val items = use.map { c -> SampleTargets.NewSample("%s %02d".format(base.take(8), c.i + 1), m.pieceMono(c), m.sound.rate, 60, 0) }
            confirmThen(tr("Ajouter ${items.size} morceau(x) à ${session?.target?.label} ?", "Add ${items.size} slice(s) to ${session?.target?.label}?")) {
                runOnFirmware(tr("Envoi des morceaux…", "Sending the slices…")) { s, p -> s.add(items, p) }
            }
        }
    }

    private fun restore() {
        val s = session ?: return
        if (!s.canRestore) return toast(tr("Rien à annuler", "Nothing to undo"))
        confirmThen(tr("Remettre la banque de ${s.target.label} comme avant le dernier envoi ?", "Put ${s.target.label}'s bank back as before the last send?")) {
            runOnFirmware(tr("Restauration…", "Restoring…")) { ss, p -> ss.restoreLast(p) }
        }
    }

    private fun confirmThen(msg: String, f: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton(tr("Oui", "Yes")) { _, _ -> f() }.setNegativeButton(tr("Non", "No"), null).show()
    }

    // ------------------------------------------------------------------ small pieces

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); minWidth = 0; minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF3A3C41.toInt()) }
        setOnClickListener { f() }
    }
    private fun label(t: String) = TextView(this).apply { text = t; setTextColor(DIM); setPadding(dp(6), 0, dp(6), 0) }
    private fun hint(t: String) = TextView(this).apply { text = t; textSize = 11f; setTextColor(DIM); setPadding(0, dp(2), 0, dp(6)) }
    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(AMBER); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(14), 0, dp(4))
    }
    private fun number(v: String, decimal: Boolean) = EditText(this).apply {
        setText(v); setTextColor(Color.WHITE); minEms = 3; isSingleLine = true
        inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
    }
    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3))
        for (x in v) addView(x, LinearLayout.LayoutParams(if (x is SeekBar) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, if (x is SeekBar) 1f else 0f).apply { setMargins(dp(3), 0, dp(3), 0) })
    }

    // ------------------------------------------------------------------ the waveform

    /** The sound (the analysis, mono at Chop.RATE), its markers and chops; touch to choose, long touch to keep or not. */
    private inner class WaveView(ctx: Context) : View(ctx) {
        private var m: SlicerModel? = null
        private var cols = FloatArray(0)       // per pixel: the peak
        var playhead = -1
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; typeface = Typeface.DEFAULT_BOLD }

        private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val mm = m ?: return false
                mm.sel = mm.pieceAt(pos(e.x))
                refresh()
                if (mm.sel >= 0) playSel()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                val mm = m ?: return
                val i = mm.pieceAt(pos(e.x))
                if (i >= 0) { mm.sel = i; mm.toggleKeep(i); performHapticFeedback(HAPTIC_LONG_PRESS); refresh() }
            }
        })

        private val HAPTIC_LONG_PRESS = android.view.HapticFeedbackConstants.LONG_PRESS

        fun setModel(mm: SlicerModel) { m = mm; cols = FloatArray(0); invalidate() }

        private fun pos(px: Float): Int { val mm = m ?: return 0; return ((px / width) * mm.x.size).toInt().coerceIn(0, mm.x.size - 1) }
        private fun xOf(pos: Int): Float { val mm = m ?: return 0f; return pos.toFloat() / mm.x.size * width }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { cols = FloatArray(0) }

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent) = gestures.onTouchEvent(e)

        override fun onDraw(c: Canvas) {
            c.drawColor(0xFF1A1B1D.toInt())
            val mm = m
            if (mm == null) {
                txt.color = DIM; c.drawText(tr("Ouvre un enregistrement", "Open a recording"), 30f, height / 2f, txt); txt.color = Color.WHITE
                return
            }
            if (cols.size != width && width > 0) {
                cols = FloatArray(width)
                val x = mm.x
                var pk = 1e-9
                for (v in x) pk = maxOf(pk, Math.abs(v))
                for (i in 0 until width) {
                    val a = (i.toLong() * x.size / width).toInt()
                    val b = maxOf(a + 1, ((i + 1).toLong() * x.size / width).toInt())
                    var v = 0.0
                    for (k in a until minOf(b, x.size)) v = maxOf(v, Math.abs(x[k]))
                    cols[i] = (v / pk).toFloat()
                }
            }
            val pieces = mm.pieces()
            // chops: kept in amber, left out dimmed, the selected one brighter
            for (pc in pieces) {
                val x0 = xOf(pc.start); val x1 = xOf(pc.end)
                p.color = when { pc.i == mm.sel -> 0x55F29B38; pc.off -> 0x22FFFFFF; else -> 0x22F29B38 }
                c.drawRect(x0, 0f, x1, height.toFloat(), p)
            }
            // the sound
            val mid = height / 2f
            for (i in cols.indices) {
                val inPiece = pieces.firstOrNull { xOf(it.start) <= i && i < xOf(it.end) }
                p.color = if (inPiece == null || inPiece.off) 0xFF6B6D72.toInt() else INK
                val h = cols[i] * (height * 0.45f)
                c.drawLine(i.toFloat(), mid - h, i.toFloat(), mid + h, p)
            }
            // markers and their numbers
            p.strokeWidth = 3f
            for ((k, pos) in mm.marks.withIndex()) {
                val x = xOf(pos)
                p.color = AMBER
                c.drawLine(x, 0f, x, height.toFloat(), p)
                c.drawText("${k + 1}", x + 4f, 26f, txt)
            }
            if (playhead >= 0) { p.color = 0xFFE5534B.toInt(); val x = xOf(playhead); c.drawLine(x, 0f, x, height.toFloat(), p) }
            p.strokeWidth = 1f
        }
    }
}
