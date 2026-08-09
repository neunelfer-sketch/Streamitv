package de.neunelf.player.data.remote.m3u

import de.neunelf.player.data.model.StreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests für den M3U-Parser.
 *
 * Die Beispiele stammen bewusst aus den Dialekten, die in der Praxis
 * Probleme machen – nicht aus der "sauberen" Spezifikation.
 */
class M3uParserTest {

    @Test
    fun `liest Standard-Eintrag mit allen Attributen`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U url-tvg="http://server.tv/xmltv.php?username=u&password=p"
            #EXTINF:-1 tvg-id="rtl.de" tvg-name="RTL HD" tvg-logo="http://logos/rtl.png" group-title="Deutschland",RTL HD
            http://server.tv:8080/live/user/pass/12345.ts
            """.trimIndent(),
        )

        assertEquals(1, playlist.entries.size)
        val entry = playlist.entries.first()
        assertEquals("RTL HD", entry.title)
        assertEquals("rtl.de", entry.tvgId)
        assertEquals("http://logos/rtl.png", entry.logoUrl)
        assertEquals("Deutschland", entry.group)
        assertEquals(StreamKind.LIVE, entry.kind)
        assertEquals(
            listOf("http://server.tv/xmltv.php?username=u&password=p"),
            playlist.epgUrls,
        )
    }

    @Test
    fun `Komma im Gruppennamen zerreisst den Sendernamen nicht`() {
        // Der Anzeigename beginnt erst nach dem letzten Komma außerhalb
        // von Anführungszeichen – genau hier scheitern naive Parser.
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Sport, DE" tvg-name="Sky Sport",Sky Sport Bundesliga 1
            http://server.tv/live/1.ts
            """.trimIndent(),
        )

        val entry = playlist.entries.single()
        assertEquals("Sky Sport Bundesliga 1", entry.title)
        assertEquals("Sport, DE", entry.group)
    }

    @Test
    fun `uebernimmt EXTGRP und EXTVLCOPT`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,Mein Sender
            #EXTGRP:Nachrichten
            #EXTVLCOPT:http-user-agent=VLC/3.0.20
            #EXTVLCOPT:http-referrer=http://example.com/
            http://server.tv/live/2.ts
            """.trimIndent(),
        )

        val entry = playlist.entries.single()
        assertEquals("Nachrichten", entry.group)
        assertEquals("VLC/3.0.20", entry.userAgent)
        assertEquals("http://example.com/", entry.referrer)
    }

    @Test
    fun `erkennt VOD und Serien am Pfad`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,Ein Film
            http://server.tv/movie/user/pass/999.mp4
            #EXTINF:-1,Eine Serie S01E01
            http://server.tv/series/user/pass/888.mkv
            #EXTINF:-1,Ein Sender
            http://server.tv/live/user/pass/777.ts
            """.trimIndent(),
        )

        assertEquals(
            listOf(StreamKind.VOD, StreamKind.SERIES, StreamKind.LIVE),
            playlist.entries.map { it.kind },
        )
    }

    @Test
    fun `ignoriert URLs ohne vorangehendes EXTINF`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            http://server.tv/verwaist.ts
            #EXTINF:-1,Gueltig
            http://server.tv/live/3.ts
            """.trimIndent(),
        )

        assertEquals(1, playlist.entries.size)
        assertEquals("Gueltig", playlist.entries.single().title)
    }

    @Test
    fun `ignoriert unbekannte Direktiven`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #KODIPROP:inputstream.adaptive.license_type=widevine
            #EXTINF:-1,Sender
            #IRGENDWAS:egal
            http://server.tv/live/4.ts
            """.trimIndent(),
        )

        assertEquals(1, playlist.entries.size)
    }

    @Test
    fun `stabile IDs ueberleben ein erneutes Einlesen`() {
        // Kritisch für Favoriten: dieselbe URL muss nach jedem Refresh
        // dieselbe streamId ergeben.
        val source = """
            #EXTM3U
            #EXTINF:-1,Sender
            http://server.tv/live/user/pass/4242.ts
        """.trimIndent()

        val first = M3uParser.toChannels(M3uParser.parse(source), playlistId = 1L)
        val second = M3uParser.toChannels(M3uParser.parse(source), playlistId = 1L)

        assertEquals(first.single().streamId, second.single().streamId)
    }

    @Test
    fun `leitet Kategorien aus group-title ab`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Deutschland",A
            http://server.tv/live/1.ts
            #EXTINF:-1 group-title="Deutschland",B
            http://server.tv/live/2.ts
            #EXTINF:-1 group-title="Sport",C
            http://server.tv/live/3.ts
            """.trimIndent(),
        )

        val categories = M3uParser.toCategories(playlist, playlistId = 1L, kind = StreamKind.LIVE)
        assertEquals(listOf("Deutschland", "Sport"), categories.map { it.name })
    }

    @Test
    fun `faellt auf tvg-name zurueck wenn der Anzeigename fehlt`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="Ersatzname",
            http://server.tv/live/5.ts
            """.trimIndent(),
        )

        assertEquals("Ersatzname", playlist.entries.single().title)
    }

    @Test
    fun `leere Playlist liefert leeres Ergebnis statt Fehler`() {
        val playlist = M3uParser.parse("#EXTM3U")
        assertTrue(playlist.entries.isEmpty())
        assertTrue(playlist.epgUrls.isEmpty())
    }

    @Test
    fun `uebernimmt tvg-chno als Senderplatz`() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-chno="42",Sender
            http://server.tv/live/6.ts
            """.trimIndent(),
        )

        val channel = M3uParser.toChannels(playlist, playlistId = 1L).single()
        assertEquals(42, channel.number)
        assertNull(channel.epgChannelId)
    }
}
