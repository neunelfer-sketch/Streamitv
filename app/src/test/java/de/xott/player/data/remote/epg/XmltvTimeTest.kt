package de.xott.player.data.remote.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests für die Zeit- und Episodenformate in XMLTV.
 *
 * Diese beiden Funktionen sind reine Rechenlogik ohne Android-Abhängigkeit
 * und lassen sich deshalb ohne Emulator prüfen – anders als der
 * XML-Durchlauf selbst, der `android.util.Xml` braucht.
 */
class XmltvTimeTest {

    @Test
    fun `liest Zeitstempel mit positivem Offset`() {
        // 2025-08-09 18:00:00 +0200 == 16:00:00 UTC
        val expected = 1_754_755_200_000L
        assertEquals(expected, XmltvParser.parseXmltvTime("20250809180000 +0200"))
    }

    @Test
    fun `Offset ohne Leerzeichen wird ebenfalls erkannt`() {
        assertEquals(
            XmltvParser.parseXmltvTime("20250809180000 +0200"),
            XmltvParser.parseXmltvTime("20250809180000+0200"),
        )
    }

    @Test
    fun `negativer Offset verschiebt in die andere Richtung`() {
        val plus = XmltvParser.parseXmltvTime("20250809180000 +0200")
        val minus = XmltvParser.parseXmltvTime("20250809180000 -0200")
        // Vier Stunden Unterschied zwischen +02:00 und −02:00.
        assertEquals(4 * 3_600_000L, minus - plus)
    }

    @Test
    fun `halbe Stunden im Offset werden beruecksichtigt`() {
        val utc = XmltvParser.parseXmltvTime("20250809180000 +0000")
        val india = XmltvParser.parseXmltvTime("20250809180000 +0530")
        assertEquals(5 * 3_600_000L + 30 * 60_000L, utc - india)
    }

    @Test
    fun `fehlender Offset wird als UTC gelesen`() {
        assertEquals(
            XmltvParser.parseXmltvTime("20250809180000 +0000"),
            XmltvParser.parseXmltvTime("20250809180000"),
        )
    }

    @Test
    fun `Zeitstempel ohne Sekunden ist gueltig`() {
        assertEquals(
            XmltvParser.parseXmltvTime("20250809180000 +0000"),
            XmltvParser.parseXmltvTime("202508091800 +0000"),
        )
    }

    @Test
    fun `Schaltjahr wird korrekt gerechnet`() {
        // 2024-02-29 existiert; der Tag danach ist der 1. März.
        val leapDay = XmltvParser.parseXmltvTime("20240229120000 +0000")
        val nextDay = XmltvParser.parseXmltvTime("20240301120000 +0000")
        assertEquals(86_400_000L, nextDay - leapDay)
    }

    @Test
    fun `Jahrhundertwechsel wird korrekt gerechnet`() {
        // 2000 ist ein Schaltjahr (durch 400 teilbar), 1900 war keines.
        val feb28 = XmltvParser.parseXmltvTime("20000228000000 +0000")
        val mar01 = XmltvParser.parseXmltvTime("20000301000000 +0000")
        assertEquals(2 * 86_400_000L, mar01 - feb28)
    }

    @Test
    fun `Muell liefert 0 statt einer Exception`() {
        assertEquals(0L, XmltvParser.parseXmltvTime(null))
        assertEquals(0L, XmltvParser.parseXmltvTime(""))
        assertEquals(0L, XmltvParser.parseXmltvTime("nicht-datum"))
        assertEquals(0L, XmltvParser.parseXmltvTime("2025"))
    }

    @Test
    fun `xmltv_ns Episodennummern sind nullbasiert`() {
        // "2.10.0/2" bedeutet Staffel 3, Episode 11.
        assertEquals(3 to 11, XmltvParser.parseEpisodeNum("xmltv_ns", "2.10.0/2"))
    }

    @Test
    fun `xmltv_ns mit leerer Staffel liefert nur die Episode`() {
        assertEquals(null to 5, XmltvParser.parseEpisodeNum("xmltv_ns", ".4."))
    }

    @Test
    fun `onscreen Format wird direkt uebernommen`() {
        assertEquals(3 to 12, XmltvParser.parseEpisodeNum("onscreen", "S03E12"))
        assertEquals(1 to 2, XmltvParser.parseEpisodeNum("", "s1e2"))
    }

    @Test
    fun `unbrauchbare Episodenangabe liefert null`() {
        assertNull(XmltvParser.parseEpisodeNum("onscreen", "Folge zwei"))
        assertNull(XmltvParser.parseEpisodeNum("xmltv_ns", ""))
    }
}
