package de.xott.player.data.remote.xtream

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests für die toleranten Xtream-DTOs.
 *
 * Jeder Fall hier stammt aus einer real vorkommenden Panel-Variante:
 * Zahlen als Strings, `null` statt Leerstring, fehlende Felder.
 * Mit den Standard-Serializern würde jeder einzelne davon die komplette
 * Senderliste unbrauchbar machen.
 */
class XtreamDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun `stream_id als Zahl und als String ergeben dasselbe`() {
        val asNumber = json.decodeFromString<XtreamLiveStreamDto>(
            """{"stream_id": 12345, "name": "RTL"}""",
        )
        val asString = json.decodeFromString<XtreamLiveStreamDto>(
            """{"stream_id": "12345", "name": "RTL"}""",
        )

        assertEquals("12345", asNumber.streamId)
        assertEquals(asNumber.streamId, asString.streamId)
    }

    @Test
    fun `null-Werte werden zu neutralen Standardwerten`() {
        val dto = json.decodeFromString<XtreamLiveStreamDto>(
            """{"stream_id": "1", "name": "X", "category_id": null, "epg_channel_id": null, "stream_icon": null}""",
        )

        assertNull(dto.categoryId)
        assertNull(dto.epgChannelId)
        assertNull(dto.streamIcon)
    }

    @Test
    fun `fehlende Felder fuehren nicht zum Fehler`() {
        val dto = json.decodeFromString<XtreamLiveStreamDto>("""{"stream_id": "7"}""")

        assertEquals("7", dto.streamId)
        assertEquals("", dto.name)
        assertEquals(0, dto.num)
        assertEquals(0, dto.tvArchive)
    }

    @Test
    fun `tv_archive akzeptiert Zahl und String`() {
        assertEquals(
            1,
            json.decodeFromString<XtreamLiveStreamDto>("""{"stream_id":"1","tv_archive":1}""").tvArchive,
        )
        assertEquals(
            1,
            json.decodeFromString<XtreamLiveStreamDto>("""{"stream_id":"1","tv_archive":"1"}""").tvArchive,
        )
    }

    @Test
    fun `Bewertung akzeptiert Zahl String und Leerstring`() {
        fun rating(raw: String) =
            json.decodeFromString<XtreamVodStreamDto>("""{"stream_id":"1","rating":$raw}""").rating

        assertEquals(8.4, rating("8.4"), 0.001)
        assertEquals(8.4, rating("\"8.4\""), 0.001)
        assertEquals(0.0, rating("\"\""), 0.001)
        assertEquals(0.0, rating("null"), 0.001)
    }

    @Test
    fun `Anmeldung wird nur bei auth gleich 1 als erfolgreich gewertet`() {
        val ok = json.decodeFromString<XtreamAuthResponse>(
            """{"user_info":{"auth":1,"status":"Active","username":"u"}}""",
        )
        assertTrue(ok.userInfo!!.isAuthenticated)

        val banned = json.decodeFromString<XtreamAuthResponse>(
            """{"user_info":{"auth":1,"status":"Banned"}}""",
        )
        assertFalse(banned.userInfo!!.isAuthenticated)

        val rejected = json.decodeFromString<XtreamAuthResponse>(
            """{"user_info":{"auth":0,"message":"Falsches Passwort"}}""",
        )
        assertFalse(rejected.userInfo!!.isAuthenticated)
    }

    @Test
    fun `is_trial akzeptiert Zahl Boolean und String`() {
        fun trial(raw: String) =
            json.decodeFromString<XtreamUserInfo>("""{"auth":1,"is_trial":$raw}""").isTrial

        assertTrue(trial("1"))
        assertTrue(trial("\"1\""))
        assertTrue(trial("true"))
        assertFalse(trial("0"))
        assertFalse(trial("\"\""))
    }

    @Test
    fun `Server-URL wird normalisiert`() {
        fun normalized(url: String) =
            XtreamCredentials(url, "u", "p").normalizedBaseUrl()

        assertEquals("http://server.tv:8080", normalized("server.tv:8080"))
        assertEquals("http://server.tv:8080", normalized("http://server.tv:8080/"))
        assertEquals("https://server.tv", normalized("https://server.tv"))
        // Aus einer kopierten Panel-URL bleibt nur die Basis übrig.
        assertEquals(
            "http://server.tv:8080",
            normalized("http://server.tv:8080/player_api.php?username=u&password=p"),
        )
        assertEquals(
            "http://server.tv:8080",
            normalized("http://server.tv:8080/get.php?username=u&password=p&type=m3u_plus"),
        )
    }
}
