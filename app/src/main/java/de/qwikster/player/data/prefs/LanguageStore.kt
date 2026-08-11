package de.qwikster.player.data.prefs

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Speichert die gewählte App-Sprache und legt sie über die Ressourcen.
 *
 * Warum eigenständig und nicht `AppCompatDelegate.setApplicationLocales`:
 * Dessen Weg funktioniert unterhalb von Android 13 **nur** in einer
 * `AppCompatActivity` – AppCompat trägt die Sprache dort in
 * `attachBaseContext` nach und kennt ausschließlich seine eigenen
 * Activities. Diese App benutzt eine schlichte `ComponentActivity` (Compose
 * braucht AppCompat nicht), und Fire OS liegt durchweg unter Android 13.
 * Der Aufruf lief damit ins Leere: gespeichert wurde nichts, angewandt
 * schon gar nichts – die Sprachauswahl blieb auf genau der Hardware
 * wirkungslos, für die die App gedacht ist.
 *
 * Deshalb hier bewusst `SharedPreferences` statt des sonst überall
 * verwendeten DataStore: Der Wert wird in `attachBaseContext` gebraucht,
 * also bevor Coroutinen oder Hilt zur Verfügung stehen. Ein `runBlocking`
 * auf DataStore an dieser Stelle verzögert jeden Kaltstart; ein
 * SharedPreferences-Lesezugriff ist genau dafür gemacht.
 */
object LanguageStore {

    private const val PREFS_NAME = "qwikster_locale"
    private const val KEY_TAG = "language_tag"

    /** Gewählter Sprach-Tag ("de", "tr", …) oder `null` für die Gerätesprache. */
    fun tag(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TAG, null)
            ?.takeIf { it.isNotBlank() }

    fun setTag(context: Context, tag: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply { if (tag.isNullOrBlank()) remove(KEY_TAG) else putString(KEY_TAG, tag) }
            .apply()
    }

    /**
     * Hüllt einen Basis-Kontext so ein, dass jeder Ressourcenzugriff die
     * gewählte Sprache benutzt. Gehört in `attachBaseContext` von Activity
     * **und** Application – Letzteres, weil ViewModels und Repositories
     * ihre Texte über den Anwendungskontext holen.
     */
    fun wrap(base: Context): Context {
        val locale = tag(base)?.let { Locale.forLanguageTag(it) } ?: return base
        Locale.setDefault(locale)
        return base.createConfigurationContext(base.resources.configuration.withLocale(locale))
    }

    /**
     * Legt die Sprache auf einen bereits laufenden Kontext.
     *
     * Nötig für den Anwendungskontext: Der entsteht einmal beim Start des
     * Prozesses, `Activity.recreate()` erneuert ihn nicht. Ohne diesen
     * Schritt bliebe alles, was ein ViewModel über `context.getString(…)`
     * holt, in der alten Sprache stehen, während die Oberfläche schon
     * umgestellt ist.
     */
    @Suppress("DEPRECATION")
    fun applyToRunning(context: Context) {
        val locale = tag(context)?.let { Locale.forLanguageTag(it) } ?: systemLocale()
        Locale.setDefault(locale)
        val resources = context.resources
        resources.updateConfiguration(
            resources.configuration.withLocale(locale),
            resources.displayMetrics,
        )
    }

    private fun Configuration.withLocale(locale: Locale): Configuration =
        Configuration(this).apply {
            // `setLocale` zieht auch die Schreibrichtung nach – wichtig für
            // Arabisch, das sonst weiter von links nach rechts liefe.
            setLocale(locale)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setLocales(LocaleList(locale))
            }
        }

    /**
     * Die Sprache des *Geräts*, nicht die der App.
     *
     * `Locale.getDefault()` taugt dafür nicht: Sobald einmal eine App-Sprache
     * gesetzt wurde, liefert es genau diese zurück – die Auswahl "Systemsprache"
     * käme dann nie mehr bei der echten Systemsprache an.
     */
    private fun systemLocale(): Locale {
        val configuration = Resources.getSystem().configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
        } else {
            @Suppress("DEPRECATION")
            configuration.locale ?: Locale.getDefault()
        }
    }
}
