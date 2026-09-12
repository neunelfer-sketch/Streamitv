package de.xott.player.data.remote.xtream

import de.xott.player.data.model.StreamKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Zugangsdaten eines Xtream-Panels. */
data class XtreamCredentials(
    val baseUrl: String,
    val username: String,
    val password: String,
) {
    /**
     * Normalisiert, was Nutzer typischerweise per Fernbedienung eintippen oder
     * aus einer WhatsApp-Nachricht kopieren:
     *
     * ```
     * server.tv:8080                                  -> http://server.tv:8080
     * http://server.tv:8080/                          -> http://server.tv:8080
     * http://server.tv:8080/player_api.php?username=… -> http://server.tv:8080
     * http://server.tv:8080/get.php?username=…        -> http://server.tv:8080
     * ```
     */
    fun normalizedBaseUrl(): String {
        var url = baseUrl.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "http://$url"
        }
        // Query und bekannte Endpunkt-Pfade abschneiden.
        url = url.substringBefore('?')
        listOf("/player_api.php", "/panel_api.php", "/get.php", "/xmltv.php").forEach { endpoint ->
            if (url.endsWith(endpoint, ignoreCase = true)) {
                url = url.dropLast(endpoint.length)
            }
        }
        return url.trimEnd('/')
    }
}

/** Fehler, die beim Sprechen mit dem Panel auftreten können. */
sealed class XtreamException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class InvalidUrl(url: String) : XtreamException("Ungültige Server-URL: $url")
    class Http(val code: Int, url: String) : XtreamException("HTTP $code bei $url")
    class Auth(val reason: String) : XtreamException("Anmeldung fehlgeschlagen: $reason")
    class Parse(endpoint: String, cause: Throwable) :
        XtreamException("Antwort von $endpoint konnte nicht gelesen werden", cause)
}

/**
 * Dünner, typsicherer Wrapper um `player_api.php`.
 *
 * Bewusst ohne Retrofit: Xtream-Panels antworten oft mit falschem
 * `Content-Type` (`text/html` statt `application/json`) und liefern bei
 * Fehlern `[]` statt eines Objekts. Mit OkHttp + manuellem Parsen lässt sich
 * das robuster abfangen als über einen Converter.
 *
 * Alle Methoden sind `suspend` und laufen auf [Dispatchers.IO].
 */
@Singleton
class XtreamApi @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {

    // -----------------------------------------------------------------------
    // Öffentliche Endpunkte
    // -----------------------------------------------------------------------

    /**
     * Meldet den Nutzer an und liefert Konto-/Serverinformationen.
     * @throws XtreamException.Auth wenn das Panel `auth != 1` meldet.
     */
    suspend fun authenticate(credentials: XtreamCredentials): XtreamAuthResponse {
        val body = request(credentials, action = null)
        val response = decode<XtreamAuthResponse>(body, "player_api.php")
            ?: throw XtreamException.Auth("Leere Antwort vom Server")

        val info = response.userInfo ?: throw XtreamException.Auth("Server lieferte keine Kontodaten")
        if (!info.isAuthenticated) {
            val reason = info.message.ifBlank { info.status.ifBlank { "Benutzername oder Passwort falsch" } }
            throw XtreamException.Auth(reason)
        }
        return response
    }

    suspend fun getCategories(credentials: XtreamCredentials, kind: StreamKind): List<XtreamCategoryDto> {
        val action = when (kind) {
            StreamKind.LIVE -> "get_live_categories"
            StreamKind.VOD -> "get_vod_categories"
            StreamKind.SERIES -> "get_series_categories"
        }
        return decodeList(request(credentials, action), action)
    }

    /** @param categoryId `null` = alle Sender auf einmal (deutlich schneller als pro Kategorie). */
    suspend fun getLiveStreams(
        credentials: XtreamCredentials,
        categoryId: String? = null,
    ): List<XtreamLiveStreamDto> {
        val params = categoryId?.let { mapOf("category_id" to it) } ?: emptyMap()
        return decodeList(request(credentials, "get_live_streams", params), "get_live_streams")
    }

    suspend fun getVodStreams(
        credentials: XtreamCredentials,
        categoryId: String? = null,
    ): List<XtreamVodStreamDto> {
        val params = categoryId?.let { mapOf("category_id" to it) } ?: emptyMap()
        return decodeList(request(credentials, "get_vod_streams", params), "get_vod_streams")
    }

    suspend fun getSeries(
        credentials: XtreamCredentials,
        categoryId: String? = null,
    ): List<XtreamSeriesDto> {
        val params = categoryId?.let { mapOf("category_id" to it) } ?: emptyMap()
        return decodeList(request(credentials, "get_series", params), "get_series")
    }

    suspend fun getSeriesInfo(credentials: XtreamCredentials, seriesId: String): XtreamSeriesInfoResponse {
        val body = request(credentials, "get_series_info", mapOf("series_id" to seriesId))
        return decode<XtreamSeriesInfoResponse>(body, "get_series_info") ?: XtreamSeriesInfoResponse()
    }

    suspend fun getVodInfo(credentials: XtreamCredentials, vodId: String): XtreamVodInfoResponse {
        val body = request(credentials, "get_vod_info", mapOf("vod_id" to vodId))
        return decode<XtreamVodInfoResponse>(body, "get_vod_info") ?: XtreamVodInfoResponse()
    }

    /**
     * Kurz-EPG für einen einzelnen Sender (typisch: die nächsten 4–10 Sendungen).
     * Wird als Fallback benutzt, wenn keine XMLTV-Quelle konfiguriert ist.
     */
    suspend fun getShortEpg(
        credentials: XtreamCredentials,
        streamId: String,
        limit: Int = 8,
    ): List<XtreamEpgListingDto> {
        val body = request(
            credentials,
            "get_short_epg",
            mapOf("stream_id" to streamId, "limit" to limit.toString()),
        )
        // Manche Panels antworten mit `{"epg_listings":[…]}`, andere direkt mit `[…]`.
        return decode<XtreamEpgResponse>(body, "get_short_epg")?.listings
            ?: decodeList(body, "get_short_epg")
    }

    // -----------------------------------------------------------------------
    // URL-Bau
    // -----------------------------------------------------------------------

    /**
     * Baut die Wiedergabe-URL nach dem Xtream-Schema:
     *
     * ```
     * Live:   {base}/live/{user}/{pass}/{id}.{ext}
     * Film:   {base}/movie/{user}/{pass}/{id}.{ext}
     * Serie:  {base}/series/{user}/{pass}/{episodeId}.{ext}
     * ```
     *
     * @param extension bei Live meist `ts` (stabiler auf schwacher Hardware)
     *        oder `m3u8` (HLS, besseres Seeking/ABR).
     */
    fun buildStreamUrl(
        credentials: XtreamCredentials,
        kind: StreamKind,
        streamId: String,
        extension: String = "ts",
    ): String {
        val segment = when (kind) {
            StreamKind.LIVE -> "live"
            StreamKind.VOD -> "movie"
            StreamKind.SERIES -> "series"
        }
        val base = credentials.normalizedBaseUrl()
        val ext = extension.trim().removePrefix(".").ifBlank { "ts" }
        return "$base/$segment/${credentials.username.urlEncoded()}/" +
            "${credentials.password.urlEncoded()}/$streamId.$ext"
    }

    /**
     * Catch-up-/Archiv-URL (Timeshift). Wird von Panels unterstützt, die
     * `tv_archive = 1` melden.
     *
     * ```
     * {base}/streaming/timeshift.php?username=…&password=…&stream=ID&start=YYYY-MM-DD:HH-MM&duration=Minuten
     * ```
     */
    fun buildTimeshiftUrl(
        credentials: XtreamCredentials,
        streamId: String,
        startFormatted: String,
        durationMinutes: Int,
    ): String {
        val base = credentials.normalizedBaseUrl()
        return "$base/streaming/timeshift.php" +
            "?username=${credentials.username.urlEncoded()}" +
            "&password=${credentials.password.urlEncoded()}" +
            "&stream=$streamId" +
            "&start=$startFormatted" +
            "&duration=$durationMinutes"
    }

    /** Vollständige XMLTV-EPG-Datei des Panels (oft gzip-komprimiert). */
    fun buildXmltvUrl(credentials: XtreamCredentials): String {
        val base = credentials.normalizedBaseUrl()
        return "$base/xmltv.php?username=${credentials.username.urlEncoded()}" +
            "&password=${credentials.password.urlEncoded()}"
    }

    /** Klassischer M3U-Export des Panels – nützlich als Fallback-Import. */
    fun buildM3uUrl(credentials: XtreamCredentials, outputFormat: String = "ts"): String {
        val base = credentials.normalizedBaseUrl()
        return "$base/get.php?username=${credentials.username.urlEncoded()}" +
            "&password=${credentials.password.urlEncoded()}" +
            "&type=m3u_plus&output=$outputFormat"
    }

    // -----------------------------------------------------------------------
    // Interna
    // -----------------------------------------------------------------------

    /** Führt einen `player_api.php`-Aufruf aus und gibt den Rohtext zurück. */
    private suspend fun request(
        credentials: XtreamCredentials,
        action: String?,
        extraParams: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val url = buildApiUrl(credentials, action, extraParams)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw XtreamException.Http(response.code, url.redactCredentials())
            }
            response.body?.string().orEmpty()
        }
    }

    private fun buildApiUrl(
        credentials: XtreamCredentials,
        action: String?,
        extraParams: Map<String, String>,
    ): HttpUrl {
        val base = credentials.normalizedBaseUrl()
        val parsed = "$base/player_api.php".toHttpUrlOrNull()
            ?: throw XtreamException.InvalidUrl(credentials.baseUrl)

        return parsed.newBuilder().apply {
            addQueryParameter("username", credentials.username)
            addQueryParameter("password", credentials.password)
            action?.let { addQueryParameter("action", it) }
            extraParams.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
    }

    /**
     * Dekodiert eine Liste. Panels liefern bei leeren Kategorien oder Fehlern
     * gerne `{"user_info":…}`, `false`, `""` oder `null` statt `[]` –
     * all das wird hier zu einer leeren Liste statt zu einer Exception.
     */
    private inline fun <reified T> decodeList(body: String, endpoint: String): List<T> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            when (val element = json.parseToJsonElement(trimmed)) {
                is JsonArray -> json.decodeFromJsonElement<List<T>>(element)
                // Panel meldet einen Fehler als Objekt -> als "keine Daten" behandeln.
                is JsonObject -> emptyList()
                else -> emptyList()
            }
        } catch (e: Exception) {
            throw XtreamException.Parse(endpoint, e)
        }
    }

    private inline fun <reified T> decode(body: String, endpoint: String): T? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val element = json.parseToJsonElement(trimmed)
            if (element is JsonObject) json.decodeFromJsonElement<T>(element) else null
        } catch (e: Exception) {
            throw XtreamException.Parse(endpoint, e)
        }
    }
}

// ---------------------------------------------------------------------------
// Kleine Hilfen
// ---------------------------------------------------------------------------

/** Minimales URL-Encoding für Benutzername/Passwort in Pfadsegmenten. */
private fun String.urlEncoded(): String =
    java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

/** Entfernt Zugangsdaten aus URLs, bevor sie in Logs oder Fehlermeldungen landen. */
fun HttpUrl.redactCredentials(): String =
    newBuilder()
        .setQueryParameter("username", "***")
        .setQueryParameter("password", "***")
        .build()
        .toString()
