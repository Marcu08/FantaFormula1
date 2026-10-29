package com.fantaformula1.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 0013: la chiave delle quote e' definita in UN SOLO posto e deve restare
 * identica alla migrazione SQL (usata per pubblicare, leggere e comporre).
 */
class QuoteKeysTest {

    @Test
    fun `testa a testa - coppia canonica e lato scelto`() {
        // La coppia e' la stessa invertendo i piloti...
        val lecPicksVer = QuoteKeys.headToHead("LEC", "VER")
        val verPicksLec = QuoteKeys.headToHead("VER", "LEC")
        assertEquals("LEC|VER|LEC", lecPicksVer)
        assertEquals("LEC|VER|VER", verPicksLec)
        // ...ma il lato scelto cambia la chiave (quota asimmetrica).
        assert(lecPicksVer != verPicksLec)
    }

    @Test
    fun `dnf e punti includono il lato`() {
        assertEquals("PER|true", QuoteKeys.dnf("PER", true))
        assertEquals("PER|false", QuoteKeys.dnf("PER", false))
        assertEquals("VER|true", QuoteKeys.points("VER", true))
        assertEquals("VER|false", QuoteKeys.points("VER", false))
    }

    @Test
    fun `chiave della voce - completa e incompleta`() {
        assertEquals(
            "PER|true",
            QuoteKeys.of(
                PredictionEntry(
                    type = PredictionType.DRIVER_DNF,
                    driverIds = listOf("PER"),
                    booleanValue = true,
                ),
            ),
        )
        assertEquals(
            "VER|false",
            QuoteKeys.of(
                PredictionEntry(
                    type = PredictionType.DRIVER_POINTS_OVER_UNDER,
                    driverIds = listOf("VER"),
                    over = false,
                ),
            ),
        )
        assertEquals(
            "LEC|VER|VER",
            QuoteKeys.of(
                PredictionEntry(
                    type = PredictionType.HEAD_TO_HEAD,
                    driverIds = listOf("VER", "LEC"),
                ),
            ),
        )

        // Incompleta: niente chiave.
        assertNull(
            QuoteKeys.of(
                PredictionEntry(type = PredictionType.DRIVER_DNF, driverIds = listOf("PER")),
            ),
        )
        assertNull(
            QuoteKeys.of(
                PredictionEntry(
                    type = PredictionType.DRIVER_POINTS_OVER_UNDER,
                    driverIds = listOf("VER"),
                ),
            ),
        )
        assertNull(
            QuoteKeys.of(
                PredictionEntry(type = PredictionType.HEAD_TO_HEAD, driverIds = listOf("VER")),
            ),
        )
        // Tipo senza quota: niente chiave.
        assertNull(
            QuoteKeys.of(
                PredictionEntry(type = PredictionType.WINNER, driverIds = listOf("VER")),
            ),
        )
    }
}
