package com.volla.hub

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Verwaltet die App-Sprache (Deutsch/Englisch) unabhängig von der Systemsprache.
 *
 * Nutzt AppCompatDelegate.setApplicationLocales(), das per-App-Sprachen ab
 * Android 13 nativ über das System-Backend abbildet und auf älteren Versionen
 * (bis minSdk 24 in diesem Projekt) über einen AppCompat-eigenen Mechanismus
 * zurückfällt. Es ist keine zusätzliche Dependency nötig, da androidx.appcompat
 * bereits eingebunden ist.
 *
 * Die gewählte Sprache wird zusätzlich in den bestehenden "settings"-
 * SharedPreferences gespiegelt (gleiches Muster wie der Dark-Mode-Schalter),
 * damit VollaHubApp.onCreate() sie beim Kaltstart wieder anwenden kann, bevor
 * die erste Activity erzeugt wird.
 */
object LanguageHelper {
    private const val PREFS_NAME = "settings"
    private const val KEY_LANGUAGE = "app_language"

    const val LANG_DE = "de"
    const val LANG_EN = "en"

    /** Aktuell aktive App-Sprache ("de" oder "en"), Default "de". */
    fun getCurrentLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, LANG_DE) ?: LANG_DE
    }

    /** Schaltet zwischen Deutsch und Englisch um. */
    fun toggleLanguage(context: Context) {
        val newLang = if (getCurrentLanguage(context) == LANG_DE) LANG_EN else LANG_DE
        setLanguage(context, newLang)
    }

    fun setLanguage(context: Context, languageCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()

        val appLocale = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(appLocale)
        // setApplicationLocales löst bereits einen Recreate aller offenen
        // Activities aus; ein zusätzliches activity.recreate() ist nicht nötig.
    }

    /**
     * Wendet die zuletzt gespeicherte Sprache erneut an. Wird in
     * VollaHubApp.onCreate() aufgerufen, damit die Auswahl auch nach einem
     * Kaltstart (Prozess-Neustart) sofort wirksam ist, analog zum
     * Dark-Mode-Muster dort.
     */
    fun applySavedLanguage(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANGUAGE, null) ?: return
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(saved))
    }
}
