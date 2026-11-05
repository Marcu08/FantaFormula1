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
    fun `deadline compatta senza fuso se il dispositivo usa lo stesso fuso`() {
        val instant = Instant.parse("2026-11-07T18:00:00Z") // 19:00 a Roma
        val text = Formats.deadline(instant, zone = rome, deviceZone = rome)
        assertEquals("7 nov · 19:00", text)
    }

    @Test
    fun `deadline mostra il fuso solo se diverso da quello del dispositivo`() {
        val instant = Instant.parse("2026-11-07T18:00:00Z")
        val text = Formats.deadline(
            instant,
            zone = rome,
            deviceZone = ZoneId.of("UTC"),
        )
        assertEquals("7 nov · 19:00 (Europe/Rome)", text)
    }

    @Test
    fun `deadline rispetta ora legale e ora solare`() {
        assertEquals(
            "1 lug · 14:00",
            Formats.deadline(
                Instant.parse("2026-07-01T12:00:00Z"),
                zone = rome,
                deviceZone = rome,
            ),
        )
        assertEquals(
            "1 dic · 13:00",
            Formats.deadline(
                Instant.parse("2026-12-01T12:00:00Z"),
                zone = rome,
                deviceZone = rome,
            ),
        )
    }

    @Test
    fun `punti positivi con segno`() {
        assertEquals("+12", Formats.points(12.0))
    }

    @Test
    fun `punti negativi senza segno doppio`() {
        assertEquals("-3,5", Formats.points(-3.5))
    }

    @Test
    fun `punti zero senza segno`() {
        assertEquals("0", Formats.points(0.0))
    }

    @Test
    fun `punti interi senza decimali inutili`() {
        assertEquals("25", Formats.points(25.0, signed = false))
    }

    @Test
    fun `punti con virgola e massimo due decimali`() {
        assertEquals("0,5", Formats.points(0.5, signed = false))
        assertEquals("12,25", Formats.points(12.25, signed = false))
        assertEquals("33,6", Formats.points(33.6, signed = false))
        assertEquals("-12,25", Formats.points(-12.25, signed = false))
        assertEquals("0", Formats.points(0.0, signed = false))
    }

    @Test
    fun `decimali al massimo su due cifre come il motore`() {
        assertEquals("+12,34", Formats.points(12.34))
        assertEquals("+12,35", Formats.points(12.345))
        // Due punteggi diversi non devono sembrare uguali.
        assertTrue(Formats.points(12.34) != Formats.points(12.36))
    }
}
