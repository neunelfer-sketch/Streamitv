package de.neunelf.player.core

/**
 * Hängt einen kurzen Diagnosecode an eine für den Nutzer bestimmte
 * Fehlermeldung an.
 *
 * Der Nutzer kann den Text so 1:1 weitergeben ("Fehler beim Laden (Code:
 * HTTP-403)"), ohne selbst etwas herausfinden zu müssen – und wir erkennen
 * aus der Ferne sofort, welcher Fall genau vorlag, statt aus einem vagen
 * "Fehler beim Laden" raten zu müssen. Bewusst als Textzusatz statt als
 * eigenes Feld: So landet der Code garantiert überall dort, wo die Meldung
 * ohnehin schon angezeigt wird, ohne jede Anzeigestelle einzeln anzupassen.
 */
fun String.withErrorCode(code: String): String = "$this (Code: $code)"

/**
 * Ausnahmen, die einen eigenen, aussagekräftigeren Diagnosecode mitbringen
 * (z. B. "HTTP-403" statt nur "IOException"). Ohne das würden mehrere ganz
 * unterschiedliche Fehlerursachen (falsche URL, Server down, abgelaufener
 * Zugang, …) alle denselben wenig hilfreichen generischen Klassennamen
 * zeigen, sobald sie z. B. über Kotlins `error(...)` als schlichte
 * `IllegalStateException` geworfen werden.
 */
interface CodedException {
    val errorCode: String
}

/** Kurzcode für eine Ausnahme: eigener Code, wenn vorhanden, sonst der Klassenname. */
fun Throwable.toErrorCode(): String =
    (this as? CodedException)?.errorCode ?: this::class.simpleName ?: "UNKNOWN"
