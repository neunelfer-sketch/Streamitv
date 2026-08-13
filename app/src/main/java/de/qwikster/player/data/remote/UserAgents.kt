package de.qwikster.player.data.remote

import de.qwikster.player.core.isBlockedStatus
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Namen, unter denen sich die App bei einem Anbieter meldet.
 *
 * Vor vielen Panels sitzt heute eine Schutzschicht – ein CDN oder ein
 * Weiterverteilungsschutz –, die anhand des Abspielprogramms entscheidet, ob
 * sie eine Anfrage überhaupt durchlässt. Wer nicht auf ihrer Liste steht,
 * bekommt eine Abfuhr: mal ein 403, mal ein Code, den der HTTP-Standard gar
 * nicht kennt (884 ist so einer). Der Zugang ist dabei völlig in Ordnung, und
 * der Zuschauer kann daran nichts ändern – am Panel schon gar nicht.
 *
 * Deshalb probiert die App der Reihe nach mehrere gängige Namen durch. Die
 * Reihenfolge ist nicht beliebig:
 *
 * 1. **Der eigene Name.** Wo nichts filtert, bleibt es dabei – und der
 *    Anbieter sieht ehrlich, welches Programm bei ihm anfragt.
 * 2. **VLC.** Der mit Abstand verbreitetste Abspieler für IPTV-Ströme; er
 *    steht praktisch überall auf der Positivliste.
 * 3. **ffmpeg.** Was die meisten Set-Top-Boxen und Panel-eigenen Werkzeuge
 *    intern benutzen.
 * 4. **Ein gewöhnlicher Browser.** Der letzte Ausweg für Schutzschichten, die
 *    Abspielprogramme grundsätzlich nicht mögen und nur Browser durchlassen.
 *
 * Der Preis ist eng begrenzt: Weitergeprobiert wird ausschließlich nach einer
 * *Abweisung*. Ein Serverfehler, eine falsche Adresse oder eine
 * Zeitüberschreitung bricht sofort ab – die scheitern beim zweiten Anlauf
 * genauso, und vier Fehlversuche statt einem würden nur die Wartezeit
 * vervierfachen.
 */
object UserAgents {

    /** Der eigene Name – auch beim Streamen benutzt, manche Panels prüfen darauf. */
    const val DEFAULT = "Qwikster/1.0 (Android TV)"

    /** Zweitname für Panels, die nur bekannte Abspieler durchlassen. */
    const val VLC = "VLC/3.0.20 LibVLC/3.0.20"

    private const val FFMPEG = "Lavf/60.16.100"

    private const val BROWSER =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Die Reihenfolge, in der beim Import durchprobiert wird. */
    val ALL = listOf(DEFAULT, VLC, FFMPEG, BROWSER)
}

/**
 * Führt [request] aus und wiederholt sie bei einer Abweisung unter den
 * weiteren Namen aus [UserAgents.ALL].
 *
 * Gibt die erste brauchbare Antwort zurück – oder, wenn alle abgewiesen
 * wurden, die letzte. So sieht der Aufrufer den Code, den der Anbieter
 * tatsächlich geschickt hat, und kann ihn melden.
 *
 * **Der Aufrufer muss die Antwort schließen** (`use { … }`), wie bei
 * `Call.execute()` auch. Die verworfenen Zwischenantworten schließt diese
 * Funktion selbst; bliebe eine offen, hinge ihre Verbindung dauerhaft im Pool.
 *
 * Blockiert den Thread – wie jedes `execute()`. Aufrufer sind bereits auf
 * einem IO-Thread.
 */
fun OkHttpClient.executeTryingUserAgents(request: Request): Response {
    var rejected: Response? = null
    for (agent in UserAgents.ALL) {
        rejected?.close()
        val response = newCall(
            request.newBuilder().header("User-Agent", agent).build(),
        ).execute()
        if (response.isSuccessful || !isBlockedStatus(response.code)) return response
        rejected = response
    }
    return requireNotNull(rejected)
}
