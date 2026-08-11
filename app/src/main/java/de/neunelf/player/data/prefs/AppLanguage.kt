package de.neunelf.player.data.prefs

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
enum class AppLanguage(val tag: String?, val label: String) {
    SYSTEM(null, "Systemsprache"),
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
