package com.fantaformula1.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** U3/U12: formattazione italiana di countdown, date e punti. */
class FormatsTest {

    private val rome = ZoneId.of("Europe/Rome")
    private val italian = Locale.ITALY

    @Test
    fun `countdown con giorni e ore`() {
        assertEquals(
            "2 g 4 h",
            Formats.countdown(Duration.ofHours(2 * 24 + 4), italian),
        )
    }

    @Test
    fun `countdown con ore e minuti`() {
        assertEquals(
            "5 h 20 m",
            Formats.countdown(Duration.ofMinutes(5 * 60 + 20), italian),
        )
    }

    @Test
    fun `countdown solo minuti`() {
        assertEquals("42 m", Formats.countdown(Duration.ofMinutes(42), italian))
    }

    @Test
    fun `countdown sotto il minuto`() {
        assertTrue(Formats.countdown(Duration.ofSeconds(30), italian).contains("1 m"))
    }

    @Test
    fun `data e ora con locale italiano`() {
        val instant = Instant.parse("2026-03-15T13:00:00Z") // 14:00 a Roma
        val text = Formats.dateTime(instant, italian, rome)
        assertTrue(text.contains("2026"))
        assertTrue(text.contains("14:00"))
    }

    @Test
    fun `deadline mostra il fuso orario`() {
        val instant = Instant.parse("2026-03-15T13:00:00Z")
        val text = Formats.deadline(instant, italian, rome)
        assertTrue(text.endsWith("(Europe/Rome)"))
        assertTrue(text.contains("14:00"))
    }

    @Test
    fun `punti positivi con segno`() {
        assertEquals("+12", Formats.points(12.0, italian))
    }

    @Test
    fun `punti negativi senza segno doppio`() {
        assertEquals("-3,5", Formats.points(-3.5, italian))
    }

    @Test
    fun `punti zero senza segno`() {
        assertEquals("0", Formats.points(0.0, italian))
    }

    @Test
    fun `decimali al massimo su una cifra`() {
        assertEquals("+12,3", Formats.points(12.34, italian))
    }
}
