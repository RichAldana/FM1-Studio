package app.fm1mix

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.io.File

/**
 * The firmware manager (Réglages › FM-1 matériel › Gestionnaire de firmwares; a firmware's page › Flasher sur le
 * FM-1…): the plugged FM-1 flashed with a firmware of the Hub, of the bank or a file, backed up before when its firmware
 * can be (Fm1Backup.kt), m-bootloader after if asked; the bank (FirmwareBank.kt) and its history; a cut install
 * resumed. The pages follow the design canvas "Flasheur de firmware": choose, checks, progress, done, cut.
 *
 * One job at a time, on its own thread; the pages show its state and are rebuilt as it moves (a closed sheet does not
 * stop it: the status line follows it, and the manager shows it again). Only USB MIDI is used, one FM-1 at a time
 * (several: with the multi-source state, later).
 */
class FirmwareManager(private val act: Activity, private val host: Host) {
    interface Host {
        /** the catalogue's firmwares */
        fun catalog(): List<FirmwareCatalog.Entry>
        /** [e]'s package on the phone, or null */
        fun installed(e: FirmwareCatalog.Entry): File?
        /** download [e] (then on the screen's thread: its file or an error) */
        fun download(e: FirmwareCatalog.Entry, then: (File?, String?) -> Unit)
        /** the FM-1's MIDI for the manager alone: the emulated one deaf to it, PANEL MIDI let go, the screen kept on */
        fun hold(on: Boolean)
        /** load [e] in the emulated FM-1, with [save]'s data given back to it (null: as it is) */
        fun emulate(e: FirmwareCatalog.Entry, save: FirmwareBank.Save?)
        /** a file picked by the user (its bytes and name), on the screen's thread */
        fun pickFile(then: (ByteArray, String) -> Unit)
        fun share(f: File, mime: String, subject: String)
        /** a line for the status bar */
        fun say(text: String)
    }

    companion object {
        const val BOOT_ASSET = "m-bootloader/fm1-uboot-2.4.fwsc"
        const val BOOT_SHA = "28fb1336dbff22882f545169d57afbb5a732eeb6268331aaa444f1c657b286d3"
        const val BOOT_NAME = "m-bootloader 2.4"
        const val BATTERY_MIN = 30
    }

    val bank = FirmwareBank(act)
    private val ui = Handler(Looper.getMainLooper())
    private val ports = AndroidOtaPorts(act)
    private var sheet: Sheet? = null
    /** who needs the FM-1's MIDI to itself now: "look", "checks", "job" */
    private val holds = HashSet<String>()

    private fun hold(who: String, on: Boolean) {
        val before = holds.isNotEmpty()
        if (on) holds += who else holds -= who
        if (holds.isNotEmpty() != before) host.hold(holds.isNotEmpty())
    }

    /** the sheet went: what only its pages held is let go (a job keeps its own) */
    fun closed() { checks = emptyList(); checkSrc = null; hold("checks", false); hold("look", false); sheet = null }

    // ------------------------------------------------------------------ the plugged FM-1

    @Volatile private var found: OtaIdentity? = null
    @Volatile private var foundName = ""
    @Volatile private var foundLoader = false
    @Volatile private var looking = false
    @Volatile private var lookedAt = 0L

    /** who is plugged in (the handshake), then the page again */
    private fun look() {
        if (looking || job != null) return
        looking = true
        Thread {
            ui.post { hold("look", true) }
            Thread.sleep(800)
            var id: OtaIdentity? = null; var name = ""
            runCatching { ports.find { true } }.getOrNull()?.let { d -> id = d.id; name = d.name; d.link.close() }
            ui.post {
                hold("look", false)
                found = id; foundName = name; foundLoader = id?.isLoader == true
                looking = false; lookedAt = System.currentTimeMillis()
                refresh()
            }
        }.start()
    }

    private fun kindName(id: OtaIdentity?) = id?.let { Fm1Backup.kindOf(it.text)?.name }

    private fun refresh() { sheet?.let { if (it.isShowing) it.refresh() } }

    // ------------------------------------------------------------------ what can be flashed

    /** a firmware to write: where it comes from, its package (once there), its resources, its name */
    class Source(val label: String, val entry: FirmwareCatalog.Entry? = null, val save: FirmwareBank.Save? = null, var fwsc: ByteArray? = null, var res: ByteArray? = null,
                 val hubSha: String? = null) {
        val product get() = fwsc?.let { runCatching { Fm1Pkg.productOf(it) }.getOrNull() }
    }

    private var chosen: Source? = null
    private var backupFirst = true
    private var bootAfter = true
    /** a save to give back to the FM-1 after the flash ("Remettre … sur le FM-1") */
    private var restoreAfter: FirmwareBank.Save? = null

    // ------------------------------------------------------------------ the job

    class Step(val key: String, val title: String, var sub: String = "", var state: Int = 0)   // 0 to come, 1 now, 2 done, 3 failed, -1 skipped

    inner class Job(val title: String, val src: Source?) {
        val steps = ArrayList<Step>()
        var pct = -1
        var line = ""
        @Volatile var done = false
        var ok = false
        var error: String? = null
        var errorCode: String? = null
        var cut = false
        var result = ""
        val saved = ArrayList<String>()
        var prev: String? = null
        var prevSave: FirmwareBank.Save? = null
        var started = System.currentTimeMillis()
        var ended = 0L
        var asking = false
        private val none = Step("", "")
        /** the step [k] (one the job does not have: a stand-in, so that a resume can skip the first steps) */
        fun step(k: String) = steps.firstOrNull { it.key == k } ?: none
        fun at(k: String, state: Int, sub: String? = null) { val s = step(k); s.state = state; if (sub != null) s.sub = sub; changed() }
    }

    @Volatile private var job: Job? = null
    private var lastPaint = 0L

    private fun changed() {
        val now = System.currentTimeMillis()
        if (now - lastPaint < 200) return
        lastPaint = now
        ui.post {
            refresh()
            job?.let { j -> if (!j.done) host.say(j.title + if (j.pct >= 0) " · ${j.pct} %" else "") }
        }
    }

    private fun finish(j: Job) {
        j.done = true; j.ended = System.currentTimeMillis()
        ui.post {
            hold("job", false)
            hold("checks", false)
            host.say(if (j.ok) tr("${j.title} : réussi", "${j.title}: done") else tr("${j.title} : ${j.error ?: "arrêté"}", "${j.title}: ${j.error ?: "stopped"}"))
            lastPaint = 0
            val sh = sheet
            if (sh != null && sh.isShowing) sh.refresh()
        }
    }

    private fun battery(): Pair<Int, Boolean> {
        val i = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 100 to true
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (lvl < 0 || scale <= 0) 100 else lvl * 100 / scale
        return pct to (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
    }

    private fun bootImage(): ByteArray? = runCatching {
        val raw = act.assets.open(BOOT_ASSET).use { it.readBytes() }
        if (Fm1Ota.sha256hex(raw) != BOOT_SHA || Fm1Pkg.productOf(raw) != "FM-1_099") null else Fm1Pkg.logicalImage(raw)
    }.getOrNull()

    /** what the install's steps say, in the job's steps */
    private fun otaStep(j: Job) = OtaStep { kind, _, _, pct, text ->
        when (kind) {
            "start" -> j.at("verify", 1)
            "verify" -> {}
            "loader" -> { j.at("verify", 2); j.at("loader", 1) }
            "mode" -> { j.at("loader", 2); j.at("write", 1, if (text == "fast") tr("installation rapide : seuls les secteurs qui changent", "fast install: only the sectors that change") else tr("installation classique", "classic install")) }
            "write" -> { if (pct != null) { j.pct = pct; changed() } }
            "reboot" -> { j.pct = 100; j.at("write", 2); j.at("back", 1) }
            "done" -> j.at("back", 2, tr("le FM-1 annonce $text", "the FM-1 reports $text"))
            "bootstart" -> j.at("boot", 1)
            "bootwrite" -> j.at("boot", 1, tr("la zone de démarrage", "the boot area"))
            "bootdone" -> j.at("boot", 2)
        }
    }

    private val pending get() = File(act.filesDir, "flash-pending")

    /** the package being written, kept until the install is over: to resume after a cut, even after a restart */
    private fun keepPending(src: Source, boot: Boolean) {
        val d = pending.apply { mkdirs() }
        File(d, "firmware.fwsc").writeBytes(src.fwsc!!)
        src.res?.let { File(d, "res.bin").writeBytes(it) } ?: File(d, "res.bin").delete()
        File(d, "job.json").writeText(JSONObject().put("label", src.label).put("boot", boot).toString())
    }

    private fun pendingSource(): Pair<Source, Boolean>? = runCatching {
        val j = JSONObject(File(pending, "job.json").readText())
        val res = File(pending, "res.bin").takeIf { it.exists() }?.readBytes()
        Source(j.getString("label"), fwsc = File(pending, "firmware.fwsc").readBytes(), res = res) to j.optBoolean("boot")
    }.getOrNull()

    private fun clearPending() { pending.deleteRecursively() }

    /** the backup of [dev] (its link open) into the bank: where it went, or null when its firmware has none */
    private fun backupOf(dev: OtaDevice, j: Job, keyData: String, keySounds: String?): String? {
        val kind = Fm1Backup.kindOf(dev.id.text) ?: return null
        j.at(keyData, 1)
        val img = Fm1Backup.backup(dev.link, kind.magic) { n -> j.step(keyData).sub = tr("$n octets", "$n bytes"); changed() }
        val sections = ArrayList<Pair<String, ByteArray>>()
        j.at(keyData, 2, tr("${img.size} octets", "${img.size} bytes") + if (Fm1Backup.playing(img)) tr(" · pris pendant la lecture", " · taken while playing") else "")
        if (kind.loomSamples && keySounds != null) {
            j.at(keySounds, 1)
            val bank = runCatching { LoomBank(dev.link).read { f -> j.step(keySounds).sub = "${(f * 100).toInt()} %"; changed() } }
            bank.onFailure { j.at(keySounds, 3, it.message ?: "") }
            bank.getOrNull()?.let { sections += "LSMP" to it; j.at(keySounds, 2, tr("${it.size / 1024} Ko", "${it.size / 1024} KB")) }
            if (bank.isSuccess && bank.getOrNull() == null) j.at(keySounds, 2, tr("aucun son à lui", "no sounds of its own"))
        }
        val fiche = JSONObject().put("fm1", "A").put("port", dev.name).put("identity", dev.id.text).put("version", dev.id.version)
            .put("playing", Fm1Backup.playing(img)).put("sounds", sections.isNotEmpty()).put("what", "backup")
        val dir = bank.save(kind.name, fiche, bank.packageFor(dev.id.text), Fm1Backup.join(img, sections), kind.ext)
        bank.log(JSONObject().put("what", "backup").put("ok", true).put("identity", dev.id.text).put("firmware", kind.name).put("dir", dir))
        return dir
    }

    /** flash [src] (its package there and checked) onto the plugged FM-1 */
    private fun startFlash(src: Source, backup: Boolean, boot: Boolean, restore: FirmwareBank.Save?, resume: Boolean = false) {
        val j = Job(tr("Écriture de ${src.label}", "Writing ${src.label}"), src)
        if (!resume) j.steps += Step("backup", tr("Sauvegarde", "Backup"), if (backup) "" else tr("non demandée", "not asked"), if (backup) 0 else -1)
        if (!resume) j.steps += Step("sounds", tr("Ses sons", "Its sounds"), "", -1)
        if (!resume) j.steps += Step("verify", tr("Le FM-1 vérifie le paquet", "The FM-1 checks the package"), tr("rien n'est encore écrit à ce stade", "nothing is written yet at this stage"))
        if (!resume) j.steps += Step("loader", tr("Le chargeur de mise à jour démarre", "The update loader starts"), tr("le FM-1 redémarre en mode mise à jour", "the FM-1 restarts in update mode"))
        j.steps += Step("write", tr("Écriture", "Writing"))
        j.steps += Step("back", tr("Redémarrage et contrôle", "Restart and check"), tr("le FM-1 doit annoncer ${src.product}", "the FM-1 must report ${src.product}"))
        if (src.res != null) j.steps += Step("res", tr("Ses ressources", "Its resources"))
        j.steps += Step("boot", BOOT_NAME, tr("la zone de démarrage seulement", "the boot area only"), if (boot) 0 else -1)
        if (restore != null) j.steps += Step("restore", tr("Ses données remises", "Its data given back"), restore.when_)
        job = j
        hold("job", true)
        Thread {
            Thread.sleep(800)
            val product = src.product!!
            val img = Fm1Pkg.logicalImage(src.fwsc!!)
            val up = Fm1Updater(ports)
            var writing = false
            try {
                if (!resume) {
                    val dev = ports.find { !it.isLoader } ?: throw OtaError("notfound", tr("FM-1 introuvable : le câble USB, et aucune autre app ne s'en sert ?", "FM-1 not found: the USB cable, and no other app using it?"))
                    j.prev = dev.id.text
                    if (backup && Fm1Backup.kindOf(dev.id.text) != null) {
                        if (Fm1Backup.kindOf(dev.id.text)?.loomSamples == true) j.step("sounds").state = 0
                        backupOf(dev, j, "backup", "sounds")?.let { j.saved += it; j.prevSave = bank.saves().firstOrNull { s -> s.dir == it } }
                    } else if (backup) j.at("backup", -1, tr("ce firmware ne permet pas de sauvegarder ses données", "this firmware cannot back up its data"))
                    dev.link.close()
                    Thread.sleep(500)
                    keepPending(src, boot)
                    writing = true
                    up.install(img, product, otaStep(j), Fm1Ota.sha256hex(src.fwsc!!))
                } else {
                    writing = true
                    j.at("write", 1)
                    if (!up.resume(img, otaStep(j), product)) throw OtaError("notfound", tr("aucun FM-1 en mode mise à jour", "no FM-1 in update mode"))
                }
                writing = false
                clearPending()
                j.result = product
                src.fwsc?.let { pkg ->
                    val dir = bank.save(src.label, JSONObject().put("fm1", "A").put("identity", product).put("what", "written").put("from", j.prev ?: ""), pkg, null, null)
                    j.saved += dir
                    bank.log(JSONObject().put("what", "flash").put("ok", true).put("identity", product).put("firmware", src.label).put("from", j.prev ?: "").put("dir", dir).put("boot", boot))
                    src.entry?.let { e -> runCatching { bank.keepInitial("${e.id}-${e.version}.fwsc", pkg) } }
                }
                src.res?.let { r ->
                    j.at("res", 1)
                    val rc = runCatching { up.installRes(r, product) { p -> j.step("res").sub = "$p %"; changed() } }
                    rc.onFailure { j.at("res", 3, it.message ?: "") }
                    rc.onSuccess { j.at("res", if (it == 0) 2 else 3, if (it == 0) tr("écrites", "written") else tr("code $it", "code $it")) }
                }
                if (boot) {
                    val bi = bootImage()
                    if (bi == null) j.at("boot", 3, tr("le paquet livré avec l'app ne se vérifie pas", "the package shipped with the app does not check"))
                    else runCatching { up.installBoot(bi, product, otaStep(j)) }.onFailure { e ->
                        j.at("boot", 3, if ((e as? OtaError)?.code == "bootrefused") tr("refusé par ce firmware : rien n'a été écrit", "refused by this firmware: nothing was written") else (e.message ?: ""))
                    }
                }
                restore?.let { s -> giveBack(j, s, product) }
                j.ok = true
            } catch (e: Exception) {
                j.error = e.message ?: e.toString()
                j.errorCode = (e as? OtaError)?.code
                j.cut = writing && j.errorCode in setOf("lost", "stopped", "noloader")
                if (j.cut) j.steps.firstOrNull { it.state == 1 }?.state = 3
                else j.steps.firstOrNull { it.state == 1 }?.let { it.state = 3; it.sub = j.error ?: "" }
                runCatching { bank.log(JSONObject().put("what", "flash").put("ok", false).put("firmware", src.label).put("from", j.prev ?: "").put("error", j.error)) }
            } finally { finish(j) }
        }.start()
        ui.post { sheet?.let { sh -> sh.push(tr("Flashage", "Flashing")) { progressPage(sh) } } }
    }

    /** [s]'s data back onto the FM-1 running [product] (after a flash, or as it is) */
    private fun giveBack(j: Job, s: FirmwareBank.Save, product: String?) {
        j.at("restore", 1, tr("en attente du FM-1", "waiting for the FM-1"))
        val data = bank.read(s.data!!.uri)
        val (img, sections) = Fm1Backup.split(data)
        val dev = Fm1Updater(ports).waitFor(40000) { !it.isLoader && (product == null || it.text == product) }
            ?: throw OtaError("noreturn", tr("le FM-1 n'est pas revenu", "the FM-1 did not come back"))
        try {
            j.at("restore", 1, tr("envoi", "sending"))
            val rc = Fm1Backup.restore(dev.link, img, onAsk = { j.asking = true; j.at("restore", 1, tr("Réponds OUI sur le FM-1 : touche SEL (HOME : non)", "Answer YES on the FM-1: SEL key (HOME: no)")) },
                onPct = { p -> if (!j.asking) { j.step("restore").sub = "$p %"; changed() } })
            j.asking = false
            if (rc != 0) throw OtaError("restore", tr("le FM-1 n'a pas repris la sauvegarde : ${Fm1Backup.error(rc)}", "the FM-1 did not take the backup: ${Fm1Backup.error(rc)}"))
            val lsmp = sections.firstOrNull { it.first == "LSMP" }?.second
            if (lsmp != null && SampleTargets.SEND_ENABLED) {
                j.at("restore", 1, tr("ses sons", "its sounds"))
                LoomBank(dev.link).write(lsmp) { f -> j.step("restore").sub = tr("ses sons ${(f * 100).toInt()} %", "its sounds ${(f * 100).toInt()} %"); changed() }
            }
            j.at("restore", 2, if (lsmp != null && !SampleTargets.SEND_ENABLED) tr("ses projets et réglages ; ses sons restent dans la sauvegarde (envoi de sons au vrai FM-1 pas encore ouvert)", "its projects and settings; its sounds stay in the backup (sending sounds to the real FM-1 not open yet)") else tr("remises", "given back"))
            bank.log(JSONObject().put("what", "restore").put("ok", true).put("identity", dev.id.text).put("dir", s.dir))
        } finally { dev.link.close() }
    }

    /** "Sauvegarder ce FM-1 maintenant" */
    private fun startBackup() {
        val j = Job(tr("Sauvegarde du FM-1", "Backing up the FM-1"), null)
        j.steps += Step("find", tr("Le FM-1", "The FM-1"))
        j.steps += Step("backup", tr("Ses données", "Its data"))
        j.steps += Step("sounds", tr("Ses sons", "Its sounds"), "", -1)
        job = j
        hold("job", true)
        Thread {
            Thread.sleep(800)
            try {
                j.at("find", 1)
                val dev = ports.find { !it.isLoader } ?: throw OtaError("notfound", tr("FM-1 introuvable", "FM-1 not found"))
                try {
                    j.at("find", 2, dev.id.text)
                    if (Fm1Backup.kindOf(dev.id.text)?.loomSamples == true) j.step("sounds").state = 0
                    val dir = backupOf(dev, j, "backup", "sounds") ?: throw OtaError("nobackup", tr("ce firmware ne permet pas de sauvegarder ses données", "this firmware cannot back up its data"))
                    j.saved += dir
                    j.result = dev.id.text
                } finally { dev.link.close() }
                j.ok = true
            } catch (e: Exception) {
                j.error = e.message ?: e.toString()
                j.steps.firstOrNull { it.state == 1 }?.let { it.state = 3; it.sub = j.error ?: "" }
            } finally { finish(j) }
        }.start()
        sheet?.let { sh -> sh.push(tr("Sauvegarde", "Backup")) { progressPage(sh) } }
    }

    /** "Remettre … sur le FM-1" for a save: its package first when the FM-1 runs something else */
    private fun startGiveBack(s: FirmwareBank.Save) {
        val pkg = s.pkg?.let { runCatching { bank.read(it.uri) }.getOrNull() }
        val now = found?.text
        if (pkg != null && runCatching { Fm1Pkg.productOf(pkg) }.getOrNull() != now) {
            chosen = Source(s.firmware, save = s, fwsc = pkg)
            restoreAfter = s
            sheet?.let { sh -> sh.push(tr("Avant d'écrire", "Before writing")) { checksPage(sh) } }
            return
        }
        val j = Job(tr("Remise de ${s.firmware}", "Giving ${s.firmware} back"), null)
        j.steps += Step("restore", tr("Ses données remises", "Its data given back"), s.when_)
        job = j
        hold("job", true)
        Thread {
            Thread.sleep(800)
            try { giveBack(j, s, null); j.ok = true; j.result = s.firmware }
            catch (e: Exception) { j.error = e.message ?: e.toString(); j.steps.firstOrNull { it.state == 1 }?.let { it.state = 3; it.sub = j.error ?: "" } }
            finally { finish(j) }
        }.start()
        sheet?.let { sh -> sh.push(tr("Remise", "Giving back")) { progressPage(sh) } }
    }

    // ------------------------------------------------------------------ the pages

    /** the manager, pushed into [sh] (the settings) */
    fun open(sh: Sheet) {
        sheet = sh
        sh.push(tr("Gestionnaire de firmwares", "Firmware manager")) { managerPage(sh) }
        if (job == null && System.currentTimeMillis() - lookedAt > 3000) look()
    }

    /** "Flasher sur le FM-1…" from a firmware's page: the choice with [e] already chosen */
    fun flash(sh: Sheet, e: FirmwareCatalog.Entry) {
        sheet = sh
        chosen = Source(e.name + if (e.version.isNotEmpty()) " ${e.version}" else "", entry = e, hubSha = e.sha256)
        restoreAfter = null
        if (found == null) look()
        sh.push(tr("Flasher un firmware", "Flash a firmware")) { choosePage(sh) }
    }

    private fun Sheet.Page.symbol(state: Int) = when (state) { 2 -> "✓"; 1 -> "…"; 3 -> "✗"; -1 -> "–"; else -> "○" }

    private fun Sheet.Page.managerPage(sh: Sheet) {
        val j = job
        if (j != null && !j.done) {
            group(tr("En cours", "Running"))
            nav(j.title, if (j.pct >= 0) "${j.pct} %" else "…") { sh.push(j.title) { progressPage(sh) } }
        }
        group(tr("FM-1 branché", "Plugged FM-1"))
        val id = found
        when {
            looking -> info(tr("Recherche…", "Looking…"), "")
            id == null -> {
                text(tr("Aucun FM-1 ne répond en USB. Branche-le (pas en Bluetooth) et vérifie qu'aucune autre app ne s'en sert.", "No FM-1 answers over USB. Plug it in (not over Bluetooth) and check no other app is using it."))
                action(tr("Chercher à nouveau", "Look again")) { look(); sh.refresh() }
            }
            id.isLoader -> {
                text(tr("Un FM-1 attend en mode mise à jour (${id.text}) : une installation a été coupée.", "An FM-1 waits in update mode (${id.text}): an install was cut."))
                action(tr("Reprendre l'installation", "Resume the install")) { resumePage(sh) }
            }
            else -> {
                info(foundName.ifEmpty { "FM-1" }, id.text)
                kindName(id)?.let { info(tr("Sauvegarde", "Backup"), tr("$it sait sauvegarder", "$it can back up")) }
                    ?: info(tr("Sauvegarde", "Backup"), tr("ce firmware ne sait pas", "this firmware cannot"))
                if (bank.bootInstalled()) info(BOOT_NAME.substringBefore(' '), tr("installé par FM1 Mix", "installed by FM1 Mix"))
                action(tr("Chercher à nouveau", "Look again")) { look(); sh.refresh() }
            }
        }
        group()
        action(tr("Flasher un firmware…", "Flash a firmware…")) { chosen = null; restoreAfter = null; sh.push(tr("Flasher un firmware", "Flash a firmware")) { choosePage(sh) } }
        if (id != null && !id.isLoader && kindName(id) != null)
            action(tr("Sauvegarder ce FM-1 maintenant", "Back up this FM-1 now"), tr("ses projets, réglages et sons, dans la banque", "its projects, settings and sounds, into the bank")) { if (job?.done != false) startBackup() }

        val saves = bank.saves()
        val inits = bank.initials()
        val hist = bank.history()
        group(tr("La banque · Documents/FM1 Mix/Firmwares", "The bank · Documents/FM1 Mix/Firmwares"))
        nav("Initial", "${inits.size}", tr("les firmwares tels que publiés sur le Hub", "the firmwares as published on the Hub")) { sh.push("Initial") { initialPage(sh) } }
        nav(tr("Sauvegardes", "Backups"), "${saves.size}", saves.firstOrNull()?.let { tr("la dernière : ${it.firmware}, ${it.when_}", "the last: ${it.firmware}, ${it.when_}") }) { sh.push(tr("Sauvegardes", "Backups")) { savesPage(sh) } }
        nav(tr("Historique", "History"), "${hist.length()}", hist.optJSONObject(0)?.let { historyLine(it) }) { sh.push(tr("Historique", "History")) { historyPage() } }
        action(tr("Exporter la banque…", "Export the bank…"), tr("un .zip de tout le dossier, à envoyer ou ranger", "a .zip of the whole folder, to send or store")) { exportBank() }

        group(tr("Secours", "Rescue"))
        val p = pendingSource()
        action(tr("Reprendre une installation coupée", "Resume a cut install"), p?.let { tr("${it.first.label} : le FM-1 attend en mode mise à jour", "${it.first.label}: the FM-1 waits in update mode") }
            ?: tr("quand le FM-1 attend en mode mise à jour", "when the FM-1 waits in update mode")) { resumePage(sh) }
        action(tr("Remettre le firmware M-VAVE", "Put M-VAVE's firmware back"), tr("ton fichier V15 ou V16 d'origine, vérifié par son empreinte", "your original V15 or V16 file, checked by its checksum")) {
            host.pickFile { bytes, name ->
                val prod = runCatching { Fm1Pkg.productOf(bytes) }.getOrNull()
                val ok = prod != null && runCatching { Fm1Pkg.stockChecksum(prod, bytes.size.toLong(), Fm1Ota.sha256hex(bytes)) }.getOrDefault(false)
                if (!ok) Toast.makeText(act, tr("Ce n'est pas un fichier d'origine de M-VAVE (V15 ou V16) : refusé.", "Not an original M-VAVE file (V15 or V16): refused."), Toast.LENGTH_LONG).show()
                else { chosen = Source(tr("Firmware M-VAVE", "M-VAVE firmware") + " " + prod!!.substringAfter('_').trimStart('0'), fwsc = bytes); restoreAfter = null; backupFirst = true
                    sh.push(tr("Avant d'écrire", "Before writing")) { checksPage(sh) } }
            }
        }
        note(tr("Si un FM-1 ne démarre plus : avec m-bootloader, maintiens PLAY à l'allumage pour son menu de secours (REC : mode mise à jour), puis reprends ici. Sans m-bootloader, écran noir : il faut le script de secours sur un ordinateur (fm1-base, tools).",
            "If an FM-1 no longer starts: with m-bootloader, hold PLAY while switching on for its rescue menu (REC: update mode), then resume here. Without m-bootloader and a black screen: the rescue script on a computer (fm1-base, tools)."))
    }

    private fun day(ms: Long) = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(ms))

    private fun historyLine(e: JSONObject): String {
        val ok = if (e.optBoolean("ok")) tr("réussi", "done") else tr("échoué", "failed")
        val w = when (e.optString("what")) {
            "flash" -> (e.optString("from").takeIf { it.isNotEmpty() }?.let { "$it → " } ?: "") + e.optString("firmware") + tr(" installé", " installed")
            "backup" -> tr("sauvegarde de ", "backup of ") + e.optString("firmware")
            "restore" -> tr("remise d'une sauvegarde", "a backup given back")
            else -> e.optString("what")
        }
        return "$w · $ok · ${day(e.optLong("date"))}"
    }

    private fun Sheet.Page.historyPage() {
        val h = bank.history()
        if (h.length() == 0) { note(tr("Rien pour l'instant.", "Nothing yet.")); return }
        group()
        for (i in 0 until h.length()) h.optJSONObject(i)?.let { e ->
            val more = e.optString("error").takeIf { it.isNotEmpty() } ?: e.optString("dir").removePrefix(FirmwareBank.REL)
            text(historyLine(e) + if (more.isNotEmpty()) "\n$more" else "", 13f)
        }
    }

    private fun Sheet.Page.initialPage(sh: Sheet) {
        val l = bank.initials()
        if (l.isEmpty()) note(tr("Chaque firmware téléchargé ou flashé depuis le catalogue y est gardé tel que publié.", "Each firmware downloaded or flashed from the catalogue is kept there as published."))
        else {
            group()
            for (it in l) nav(it.name.removeSuffix(".fwsc"), "${it.size / 1024} Ko") {
                val bytes = runCatching { bank.read(it.uri) }.getOrNull() ?: return@nav
                chosen = Source(it.name.removeSuffix(".fwsc"), fwsc = bytes); restoreAfter = null
                sh.push(tr("Flasher un firmware", "Flash a firmware")) { choosePage(sh) }
            }
        }
    }

    private fun Sheet.Page.savesPage(sh: Sheet) {
        val l = bank.saves()
        if (l.isEmpty()) { note(tr("Aucune sauvegarde pour l'instant. Elles se font avant chaque flashage (si le firmware le permet) ou depuis le gestionnaire.", "No backup yet. They are made before each flash (when the firmware can) or from the manager.")); return }
        for ((name, items) in l.groupBy { it.firmware }) {
            group(name)
            for (s in items) nav(s.when_, if (s.data != null) tr("données", "data") else tr("paquet", "package"),
                listOfNotNull(s.identity.takeIf { it.isNotEmpty() }, tr("FM-1 ${s.fm1}", "FM-1 ${s.fm1}"), if (s.hasSounds) tr("avec ses sons", "with its sounds") else null).joinToString(" · ")) {
                sh.push(s.firmware + " · " + s.when_) { savePage(sh, s) }
            }
        }
    }

    /** the catalogue's entry a save is of (by its package, else its name) */
    fun entryFor(s: FirmwareBank.Save): FirmwareCatalog.Entry? {
        val cat = host.catalog()
        val prod = s.identity
        return cat.firstOrNull { e -> host.installed(e)?.let { f -> runCatching { Fm1Pkg.productOf(f.readBytes()) == prod }.getOrDefault(false) } == true }
            ?: cat.firstOrNull { it.name.equals(s.firmware, true) } ?: cat.firstOrNull { it.name.contains(s.firmware, true) }
    }

    private fun Sheet.Page.savePage(sh: Sheet, s: FirmwareBank.Save) {
        group()
        info(tr("Quand", "When"), s.when_)
        if (s.identity.isNotEmpty()) info(tr("Identité", "Identity"), s.identity)
        info("FM-1", s.fm1 + (s.fiche.optString("port").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""))
        info(tr("Contenu", "Content"), listOfNotNull(s.pkg?.let { tr("le paquet", "the package") }, s.data?.let { tr("ses données", "its data") }, if (s.hasSounds) tr("ses sons", "its sounds") else null).joinToString(", "))
        if (s.playing) note(tr("Prise pendant la lecture : ce qui n'était pas encore enregistré n'y est pas.", "Taken while playing: what was not saved yet is not in it."))
        group()
        if (s.data != null) {
            val e = entryFor(s)
            if (e != null) action(tr("Charger dans l'émulé", "Load in the emulated FM-1"), tr("${e.name}, avec ces données", "${e.name}, with this data")) { sh.close(); host.emulate(e, s) }
            action(tr("Remettre sur le FM-1", "Give back to the FM-1"), if (s.pkg != null) tr("le paquet d'abord s'il n'y est plus, puis les données", "the package first if it is gone, then the data") else tr("les données, au firmware qui les a faites", "the data, to the firmware that made them")) { startGiveBack(s) }
        }
        if (s.pkg != null) action(tr("Flasher ce paquet…", "Flash this package…")) {
            val b = runCatching { bank.read(s.pkg.uri) }.getOrNull() ?: return@action
            chosen = Source(s.firmware, save = s, fwsc = b); restoreAfter = null
            sh.push(tr("Flasher un firmware", "Flash a firmware")) { choosePage(sh) }
        }
        note(s.dir.removePrefix(android.os.Environment.DIRECTORY_DOCUMENTS + "/"))
    }

    private fun Sheet.Page.choosePage(sh: Sheet) {
        note(tr("Étape 1 sur 3 · sur le FM-1 branché", "Step 1 of 3 · on the plugged FM-1") + (found?.let { " (${it.text})" } ?: ""))
        val c = chosen
        group(tr("Initial (Hub)", "Initial (Hub)"))
        for (e in host.catalog().filter { !it.byo && it.pkg != null }) {
            val f = host.installed(e)
            val sub = listOfNotNull(e.version.takeIf { it.isNotEmpty() }, e.author.takeIf { it.isNotEmpty() },
                if (f != null) (if (e.sha256 != null) tr("sur le téléphone · vérifié", "on the phone · checked") else tr("sur le téléphone", "on the phone"))
                else tr("à télécharger", "to download") + (e.size?.let { " · ${it / 1024} Ko" } ?: "")).joinToString(" · ")
            choice(e.name, sub, c?.entry?.id == e.id) { chosen = Source(e.name + if (e.version.isNotEmpty()) " ${e.version}" else "", entry = e, hubSha = e.sha256) }
        }
        val withPkg = bank.saves().filter { it.pkg != null }
        if (withPkg.isNotEmpty()) {
            group(tr("Sauvegardes", "Backups"))
            for (s in withPkg.take(12)) choice("${s.firmware} · ${s.when_}", s.identity, c?.save?.dir == s.dir) {
                runCatching { bank.read(s.pkg!!.uri) }.getOrNull()?.let { chosen = Source(s.firmware, save = s, fwsc = it) }
            }
        }
        group()
        if (c != null && c.entry == null && c.save == null) choice(c.label, tr("un fichier", "a file"), true) {}
        action(tr("Un fichier…", "A file…"), tr("un paquet .fwsc du téléphone", "a .fwsc package from the phone")) {
            host.pickFile { b, name ->
                if (runCatching { Fm1Pkg.productOf(b) }.isFailure) Toast.makeText(act, tr("Ce n'est pas un paquet FM-1.", "Not an FM-1 package."), Toast.LENGTH_LONG).show()
                else { chosen = Source(name.removeSuffix(".fwsc"), fwsc = b); sh.refresh() }
            }
        }
        group(tr("Avant et après", "Before and after"))
        val canBackup = found?.let { kindName(it) != null } ?: true
        if (canBackup) toggle(tr("Sauvegarder d'abord", "Back up first"), (kindName(found)?.let { tr("les projets et les sons de $it, dans Sauvegardes", "$it's projects and sounds, into Backups") } ?: tr("si le firmware du FM-1 le permet", "when the FM-1's firmware can")), backupFirst) { backupFirst = it }
        else info(tr("Sauvegarder d'abord", "Back up first"), tr("impossible", "not possible"))
        toggle(tr("Installer m-bootloader après", "Install m-bootloader after"), tr("Recommandé : un menu de secours si un firmware ne démarre plus", "Recommended: a rescue menu if a firmware no longer starts"), bootAfter) { bootAfter = it }
        if (!canBackup) note(tr("Le firmware du FM-1 (${found?.text}) ne permet pas de sauvegarder ses données. Ce que l'app y a écrit reste de toute façon dans la banque.", "The FM-1's firmware (${found?.text}) cannot back up its data. What the app wrote there stays in the bank anyway."))
        group()
        if (c != null) action(tr("Continuer : ${c.label}", "Continue: ${c.label}")) { sh.push(tr("Avant d'écrire", "Before writing")) { checksPage(sh) } }
        else note(tr("Choisis un firmware.", "Pick a firmware."))
    }

    // the checks: done on a thread when the page opens
    private class Check(val title: String, var sub: String, var state: Int = 1)
    private var checks: List<Check> = emptyList()
    @Volatile private var checksOk = false
    private var checkGen = 0

    private fun runChecks(sh: Sheet) {
        val src = chosen ?: return
        if (job?.done == false) return
        val me = ++checkGen
        val cFile = Check(tr("Le fichier est intact", "The file is intact"), tr("empreinte SHA-256, structure du paquet", "SHA-256 checksum, the package's structure"))
        val cModel = Check(tr("Il est fait pour ce FM-1", "It is made for this FM-1"), tr("le modèle annoncé par le FM-1 et celui du paquet", "the model the FM-1 reports and the package's"))
        val cSave = Check(tr("Sauvegarde", "Backup"), "")
        val bat = battery()
        val cBat = Check(tr("Batterie ${bat.first} %", "Battery ${bat.first} %") + if (bat.second) tr(", en charge", ", charging") else "",
            tr("en dessous de $BATTERY_MIN % sans charge : refusé", "below $BATTERY_MIN % without charging: refused"), if (bat.first >= BATTERY_MIN || bat.second) 2 else 3)
        val cHold = Check(tr("L'émulé et PANEL MIDI en pause", "The emulated FM-1 and PANEL MIDI paused"), tr("la prise MIDI du FM-1 réservée au flashage", "the FM-1's MIDI kept for the flash"), 2)
        val cScreen = Check(tr("Écran gardé allumé", "Screen kept on"), tr("jusqu'à la fin", "until the end"), 2)
        checks = listOf(cFile, cModel, cSave, cBat, cHold, cScreen)
        checksOk = false
        hold("checks", true)
        fun paint() = ui.post { if (me == checkGen && sh.isShowing) sh.refresh() }
        Thread {
            // the package (downloaded first when it is the catalogue's and not on the phone)
            if (src.fwsc == null && src.entry != null) {
                val f = host.installed(src.entry)
                if (f != null) src.fwsc = f.readBytes()
                else {
                    cFile.sub = tr("téléchargement…", "downloading…"); paint()
                    val l = java.util.concurrent.CountDownLatch(1)
                    var err: String? = null
                    ui.post { host.download(src.entry) { file, e -> if (file != null) src.fwsc = runCatching { file.readBytes() }.getOrNull(); err = e; l.countDown() } }
                    l.await()
                    if (src.fwsc == null) { cFile.state = 3; cFile.sub = err ?: tr("téléchargement impossible", "download failed") }
                }
            }
            val pkg = src.fwsc
            var product: String? = null
            if (pkg != null) {
                val r = runCatching {
                    product = Fm1Pkg.productOf(pkg)
                    val sha = Fm1Ota.sha256hex(pkg)
                    if (src.hubSha != null && src.hubSha != sha) throw IllegalArgumentException(tr("ce n'est pas le paquet du Hub (SHA-256)", "not the Hub's package (SHA-256)"))
                    Fm1Pkg.checkUfw(Fm1Pkg.logicalImage(pkg))
                    if (Fm1Ota.isStock(product!!) && !Fm1Pkg.stockChecksum(product!!, pkg.size.toLong(), sha))
                        throw IllegalArgumentException(tr("se dit firmware M-VAVE sans être un fichier connu", "says it is M-VAVE's without being a known file"))
                    src.hubSha != null
                }
                r.onSuccess { cFile.state = 2; cFile.sub = (if (it) tr("empreinte SHA-256 du Hub, ", "the Hub's SHA-256, ") else "") + tr("structure du paquet · $product", "the package's structure · $product") }
                r.onFailure { cFile.state = 3; cFile.sub = it.message ?: "" }
                // the resources, beside the Hub's package (NAME-res.bin)
                if (r.isSuccess && src.res == null && src.entry?.pkg?.endsWith(".fwsc") == true) runCatching {
                    val raw = FirmwareStore.open(src.entry!!.pkg!!.removeSuffix(".fwsc") + "-res.bin").first.use { it.readBytes() }
                    Fm1Ota.resPayload(raw); src.res = raw
                }
            }
            paint()
            Thread.sleep(800)
            val dev = runCatching { ports.find { true } }.getOrNull()
            dev?.link?.close()
            val id = dev?.id
            if (id != null) { found = id; foundName = dev.name; foundLoader = id.isLoader }
            when {
                id == null -> { cModel.state = 3; cModel.sub = tr("aucun FM-1 ne répond en USB", "no FM-1 answers over USB") }
                id.isLoader -> { cModel.state = 3; cModel.sub = tr("le FM-1 attend en mode mise à jour : reprends l'installation coupée (Secours)", "the FM-1 waits in update mode: resume the cut install (Rescue)") }
                product != null && id.model != product!!.substringBefore('_') -> { cModel.state = 3; cModel.sub = tr("le FM-1 est ${id.text}, le paquet est pour $product", "the FM-1 is ${id.text}, the package is for $product") }
                product == null -> { cModel.state = 0; cModel.sub = id.text }
                id.open && !Fm1Ota.isStock(product!!) -> { cModel.state = 3; cModel.sub = tr("le FM-1 a le firmware Open de M-VAVE : remets d'abord son firmware d'origine", "the FM-1 runs M-VAVE's Open firmware: put its original firmware back first") }
                else -> { cModel.state = 2; cModel.sub = "${id.text} → $product" }
            }
            val kind = id?.let { Fm1Backup.kindOf(it.text) }
            cSave.state = when { !backupFirst -> -1; kind == null -> -1; else -> 2 }
            cSave.sub = when { !backupFirst -> tr("non demandée", "not asked"); kind == null -> tr("ce firmware ne permet pas de sauvegarder ses données", "this firmware cannot back up its data")
                else -> tr("faite juste avant l'écriture : ${kind.name}, dans Sauvegardes", "made just before writing: ${kind.name}, into Backups") }
            checksOk = checks.none { it.state == 3 || it.state == 1 } && cModel.state == 2 && cFile.state == 2
            paint()
        }.start()
    }

    private fun Sheet.Page.checksPage(sh: Sheet) {
        val src = chosen ?: return
        val running = job
        if (running != null && !running.done) {
            group(); nav(running.title, if (running.pct >= 0) "${running.pct} %" else "…") { sh.push(running.title) { progressPage(sh) } }
            return
        }
        if (checks.isEmpty() || checkSrc !== src) { checkSrc = src; runChecks(sh) }
        note(tr("Étape 2 sur 3 · tout est vérifié avant le premier octet", "Step 2 of 3 · everything is checked before the first byte"))
        group()
        info(found?.text ?: "FM-1", "→ ${src.label}")
        if (bootAfter) info("", tr("puis $BOOT_NAME", "then $BOOT_NAME"))
        if (restoreAfter != null) info("", tr("puis ses données (${restoreAfter!!.when_})", "then its data (${restoreAfter!!.when_})"))
        group()
        for (c in checks) info(symbol(c.state) + "  " + c.title, c.sub)
        group()
        text(tr("Ne débranche pas le câble et n'éteins pas le FM-1 pendant l'écriture. Si c'est coupé quand même, l'app reprend là où elle s'était arrêtée.",
            "Do not unplug the cable or switch the FM-1 off while writing. If it is cut anyway, the app resumes where it stopped."), 13f)
        if (checksOk) action(tr("Flasher", "Flash")) { if (job?.done != false) { checks = emptyList(); startFlash(src, backupFirst, bootAfter, restoreAfter) } }
        else if (checks.any { it.state == 1 }) info(tr("Vérification…", "Checking…"), "")
        else action(tr("Vérifier à nouveau", "Check again")) { checkSrc = null; sh.refresh() }
        action(tr("Annuler", "Cancel")) { checks = emptyList(); checkSrc = null; hold("checks", false); sh.pop() }
    }
    private var checkSrc: Source? = null

    private fun Sheet.Page.progressPage(sh: Sheet) {
        val j = job ?: return
        if (j.done) { endPage(sh, j); return }
        if (j.src != null) note(tr("Étape 3 sur 3", "Step 3 of 3"))
        group()
        text(j.title + if (j.pct >= 0) "\n${j.pct} %" else "", 20f)
        if (j.asking) text(tr("Le FM-1 demande « RESTORE FROM USB ? » : réponds oui avec SEL.", "The FM-1 asks \"RESTORE FROM USB?\": answer yes with SEL."), 15f)
        group()
        for (s in j.steps) if (s.state != -1 || s.sub.isNotEmpty()) info(symbol(s.state) + "  " + s.title, s.sub)
        if (j.src != null) note(tr("Ne débranche pas le câble, n'éteins pas le FM-1. Pas de bouton Annuler pendant l'écriture : l'arrêter au milieu laisserait le FM-1 en mode mise à jour.",
            "Do not unplug the cable or switch the FM-1 off. No Cancel button while writing: stopping in the middle would leave the FM-1 in update mode."))
    }

    private fun Sheet.Page.endPage(sh: Sheet, j: Job) {
        if (!j.ok && j.cut) { cutPage(sh, j); return }
        group()
        if (j.ok) {
            text("✓  " + if (j.src != null) tr("${j.src.label} est sur le FM-1", "${j.src.label} is on the FM-1") else j.title + tr(" : réussi", ": done"), 20f)
            val secs = (j.ended - j.started) / 1000
            val bootOk = j.steps.firstOrNull { it.key == "boot" }?.state == 2
            if (j.src != null) text(tr("Contrôlé : le FM-1 annonce bien ${j.result}", "Checked: the FM-1 does report ${j.result}") +
                (if (bootOk) " · " + tr("$BOOT_NAME installé", "$BOOT_NAME installed") else "") + " · ${secs / 60} min ${secs % 60} s", 13f)
        } else {
            text("✗  " + (j.error ?: tr("arrêté", "stopped")), 16f)
            if (j.src != null && !j.cut) text(tr("Rien n'a été écrit sur le FM-1 : il garde son firmware.", "Nothing was written on the FM-1: it keeps its firmware."), 13f)
        }
        group()
        for (s in j.steps) if (s.state != -1) info(symbol(s.state) + "  " + s.title, s.sub)
        if (j.saved.isNotEmpty()) {
            group(tr("Rangé dans la banque", "Stored in the bank"))
            for (d in j.saved) info(tr("Sauvegardes", "Backups"), d.removePrefix(FirmwareBank.SAVES).removeSuffix("/").replace("/", " / "))
        }
        val ps = j.prevSave
        if (j.ok && ps != null && ps.data != null) {
            group(tr("Et maintenant", "And now"))
            entryFor(ps)?.let { e -> action(tr("Charger ${ps.firmware} dans l'émulé", "Load ${ps.firmware} in the emulated FM-1"), tr("avec sa sauvegarde : tes projets passent sur le téléphone", "with its backup: your projects move to the phone")) { sh.close(); host.emulate(e, ps) } }
            if (ps.pkg != null) action(tr("Remettre ${ps.firmware} sur le FM-1", "Put ${ps.firmware} back on the FM-1"), tr("depuis la sauvegarde, en un geste", "from the backup, in one go")) { job = null; startGiveBack(ps) }
        }
        group()
        action(tr("Terminé", "Done")) { job = null; sh.close() }
    }

    private fun Sheet.Page.cutPage(sh: Sheet, j: Job) {
        group()
        text("!  " + tr("L'écriture a été coupée", "The write was cut"), 20f)
        text(tr("${j.error ?: ""}${if (j.pct >= 0) " (${j.pct} %)" else ""}. Le FM-1 est resté en mode mise à jour : il attend la suite, rien n'est perdu.",
            "${j.error ?: ""}${if (j.pct >= 0) " (${j.pct} %)" else ""}. The FM-1 stayed in update mode: it waits for the rest, nothing is lost."), 14f)
        resumeSteps()
        group()
        action(tr("Reprendre", "Resume")) { job = null; resumeNow(sh) }
        action(tr("Plus tard", "Later"), tr("Gestionnaire › Secours › Reprendre", "Manager › Rescue › Resume")) { job = null; sh.close() }
    }

    private fun Sheet.Page.resumeSteps() {
        group(tr("Pour reprendre", "To resume"))
        text(tr("1  Rebranche le câble. Laisse le FM-1 allumé.\n2  Touche Reprendre : l'app le retrouve en mode mise à jour.\n3  Seuls les morceaux pas encore écrits sont envoyés.",
            "1  Plug the cable back in. Leave the FM-1 on.\n2  Tap Resume: the app finds it in update mode.\n3  Only the parts not yet written are sent."), 14f)
        note(tr("Si le FM-1 a été éteint et ne redémarre plus : avec m-bootloader, maintiens PLAY à l'allumage pour son menu de secours (REC : mode mise à jour), puis reprends ici. Sans m-bootloader et écran noir : il faut le script de secours sur un ordinateur (fm1-base).",
            "If the FM-1 was switched off and no longer starts: with m-bootloader, hold PLAY while switching on for its rescue menu (REC: update mode), then resume here. Without m-bootloader and a black screen: the rescue script on a computer (fm1-base)."))
    }

    private fun resumePage(sh: Sheet) {
        sh.push(tr("Reprendre", "Resume")) {
            val p = pendingSource()
            group()
            if (p == null) text(tr("L'app n'a pas d'installation en attente. Si le FM-1 attend en mode mise à jour, flashe-le à nouveau : choisis le même firmware (l'écriture reprend là où elle en était).",
                "The app has no install waiting. If the FM-1 waits in update mode, flash it again: pick the same firmware (the write resumes where it was)."))
            else text(tr("En attente : ${p.first.label}", "Waiting: ${p.first.label}"), 16f)
            resumeSteps()
            if (p != null) { group(); action(tr("Reprendre", "Resume")) { resumeNow(sh) } }
        }
    }

    private fun resumeNow(sh: Sheet) {
        val p = pendingSource()
        if (p == null) { Toast.makeText(act, tr("Aucune installation en attente.", "No install waiting."), Toast.LENGTH_LONG).show(); return }
        if (job?.done == false) return
        startFlash(p.first, backup = false, boot = p.second, restore = null, resume = true)
    }

    /** the bank as a .zip, to send */
    private fun exportBank() {
        Toast.makeText(act, tr("Préparation…", "Preparing…"), Toast.LENGTH_SHORT).show()
        Thread {
            val r = runCatching {
                val dir = File(act.cacheDir, "export").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = File(dir, "FM1 Mix - Firmwares.zip")
                val n = f.outputStream().use { bank.export(it) }
                if (n == 0) throw IllegalStateException(tr("la banque est vide", "the bank is empty"))
                f
            }
            ui.post {
                r.onFailure { Toast.makeText(act, tr("Export impossible : ", "Cannot export: ") + (it.message ?: it.toString()), Toast.LENGTH_LONG).show() }
                r.onSuccess { host.share(it, "application/zip", "FM1 Mix · Firmwares") }
            }
        }.start()
    }

    // ------------------------------------------------------------------ the emulated FM-1's choice

    /**
     * Picking [e] for the emulated FM-1: when the bank holds a backup of it, which to load (the design's "Firmware de
     * l'émulé"): the backup (advised) or the initial version. Without one, straight on ([plain]). True when asked.
     */
    fun askEmulated(sh: Sheet, e: FirmwareCatalog.Entry, hasMemory: Boolean, plain: () -> Unit): Boolean {
        val kind = host.installed(e)?.let { f -> runCatching { Fm1Backup.kindOf(Fm1Pkg.productOf(f.readBytes())) }.getOrNull() }
            ?: Fm1Backup.KNOWN.firstOrNull { e.name.contains(it.name, true) }
            ?: return false
        val saves = bank.savesOf(kind.name)
        if (saves.isEmpty()) return false
        val pick = saves.first()
        sh.push(tr("Firmware de l'émulé", "The emulated firmware")) {
            note(e.name)
            group(tr("Quelle version charger dans l'émulé ?", "Which version to load in the emulated FM-1?"))
            choice(tr("La sauvegarde · conseillée", "The backup · advised"), "${pick.when_}, " + tr("du FM-1 ${pick.fm1}", "from FM-1 ${pick.fm1}") +
                (pick.identity.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") + tr(" · avec tes projets", " · with your projects") + if (pick.hasSounds) tr(" et tes sons", " and sounds") else "", pickedSave) { pickedSave = true }
            choice(tr("La version initiale", "The initial version"), "${e.version} " + tr("du Firmware Hub", "from the Firmware Hub") +
                if (hasMemory) tr(", avec la mémoire de l'émulé", ", with the emulated FM-1's memory") else tr(", vierge", ", blank"), !pickedSave) { pickedSave = false }
            if (saves.size > 1) {
                group(tr("Une autre sauvegarde", "Another backup"))
                for (s in saves.drop(1).take(8)) nav(s.when_, null, s.identity) { sh.close(); host.emulate(e, s) }
            }
            note(tr("Avec la sauvegarde, l'émulé démarre ${kind.name} puis l'app lui rend ses données comme au vrai FM-1 : il affiche « RESTORE FROM USB ? » et l'app répond oui.",
                "With the backup, the emulated FM-1 starts ${kind.name}, then the app gives its data back as to the real FM-1: it shows \"RESTORE FROM USB?\" and the app answers yes."))
            group()
            action(if (pickedSave) tr("Charger la sauvegarde", "Load the backup") else tr("Charger la version initiale", "Load the initial version")) {
                sh.close(); if (pickedSave) host.emulate(e, pick) else plain()
            }
        }
        return true
    }
    private var pickedSave = true
}
