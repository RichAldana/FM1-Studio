package app.fm1mix

import java.util.Locale

/**
 * The app's two languages: French when the phone (or, from Android 13, FM1 Mix's own language setting) is in French,
 * English otherwise. Each text is written in place, in both: tr("Enregistrer", "Record").
 */
object Lang {
    val fr: Boolean get() = Locale.getDefault().language == "fr"
}

fun tr(fr: String, en: String) = if (Lang.fr) fr else en
