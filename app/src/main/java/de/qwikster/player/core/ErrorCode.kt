package de.qwikster.player.core

import android.content.Context
import de.qwikster.player.R

/**
 * Verständliche Meldung zu einem HTTP-Fehlercode.
 *
 * 403 bekommt eine eigene, weil "Server antwortete mit HTTP 403" niemandem
 * weiterhilft: Der Zugang ist in aller Regel völlig in Ordnung, der Anbieter
 * weist die Anfrage nur ab – fast immer, weil bereits die erlaubte Zahl
 * gleichzeitiger Verbindungen belegt ist oder der Zugang an eine andere
 * Internetverbindung gebunden ist. Beides kann der Zuschauer selbst prüfen,
 * sobald er es weiß.
 */
fun Context.httpErrorMessage(code: Int): String = when (code) {
    403 -> getString(R.string.error_http_forbidden)
    else -> getString(R.string.error_http_status, code)
}

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
