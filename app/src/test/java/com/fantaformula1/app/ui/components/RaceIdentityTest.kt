package com.fantaformula1.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Identita' visiva per gara: il nome del gran premio ha la precedenza sul
 * paese, le gare non riconosciute hanno una palette deterministica per round.
 */
class RaceIdentityTest {

    @Test
    fun `nome del gran premio riconosciuto`() {
        val visual = RaceIdentity.visualFor("Miami Grand Prix", "United States", 5)

        assertEquals("🇺🇸", visual.flag)
        assertEquals(0xFFC2186B, visual.startColor)
    }

    @Test
    fun `il nome ha la precedenza sul paese`() {
        // "Miami Grand Prix" negli Stati Uniti deve avere il tema Miami
        // (rosa/teal), non quello di Austin (blu/rosso).
        val miami = RaceIdentity.visualFor("Miami Grand Prix", "United States", 1)
        val austin = RaceIdentity.visualFor("United States Grand Prix", "United States", 1)

        assertEquals("🇺🇸", miami.flag)
        assertEquals("🇺🇸", austin.flag)
        assertNotEquals(miami.startColor, austin.startColor)
    }

    @Test
    fun `paese riconosciuto quando il nome non lo e'`() {
        // Nome non in mappa ma paese si: usa il tema del paese.
        val visual = RaceIdentity.visualFor("Gran Premio di Monza", "Italy", 16)

        assertEquals("🇮🇹", visual.flag)
        assertEquals(0xFF7A0A16, visual.startColor)
    }

    @Test
    fun `gara sconosciuta usa la palette deterministica per round`() {
        val round1 = RaceIdentity.visualFor("Gran Premio X", "Paese Y", 1)
        val round3 = RaceIdentity.visualFor("Gran Premio X", "Paese Y", 3)

        assertEquals("🏁", round1.flag)
        assertEquals("🏁", round3.flag)
        assertNotEquals(round1.startColor, round3.startColor)
        // Il round 9 ricade sul primo elemento: palette ciclica ma stabile.
        assertEquals(round1, RaceIdentity.visualFor("Gran Premio X", "Paese Y", 9))
    }

    @Test
    fun `round zero o negativo usa il primo colore della palette`() {
        val visual = RaceIdentity.visualFor(null, null, 0)

        assertEquals("🏁", visual.flag)
        assertEquals(RaceIdentity.visualFor(null, null, 1), visual)
    }
}