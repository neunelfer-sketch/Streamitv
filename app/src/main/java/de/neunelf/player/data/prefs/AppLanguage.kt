package de.neunelf.player.data.prefs

import androidx.annotation.StringRes
import de.neunelf.player.R

/**
 * Sprachen, zwischen denen in den Einstellungen gewechselt werden kann.
 *
 * Jede Beschriftung steht in der jeweils eigenen Sprache ("Türkçe" statt
 * "Türkisch") – so findet jeder seinen Eintrag auch dann, wenn die Liste
 * gerade in einer anderen Sprache angezeigt wird.
 *
 * [SYSTEM] setzt keine eigene Sprache, sondern löscht eine zuvor gewählte
 * Übersteuerung wieder – die App folgt dann wieder der Gerätesprache, wie
 * bisher.
 */
enum class AppLanguage(
    val tag: String?,
    /** Eigenname der Sprache – bleibt in jeder Anzeigesprache gleich. */
    val label: String,
    /**
     * Nur für [SYSTEM] gesetzt: Der Eintrag benennt keine Sprache, sondern
     * eine Einstellung, und wird deshalb als einziger mitübersetzt.
     */
    @StringRes val labelRes: Int? = null,
) {
    SYSTEM(null, "", R.string.settings_language_system),
    GERMAN("de", "Deutsch"),
    ENGLISH("en", "English"),
    TURKISH("tr", "Türkçe"),
    ARABIC("ar", "العربية"),
    RUSSIAN("ru", "Русский"),
    POLISH("pl", "Polski"),
    FRENCH("fr", "Français"),
    SPANISH("es", "Español"),
    ITALIAN("it", "Italiano"),
    DUTCH("nl", "Nederlands"),
}
