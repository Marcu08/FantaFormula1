package com.fantaformula1.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sagome piste: chiavi presenti, coordinate normalizzate, mapping gara. */
class CircuitTracksTest {

    @Test
    fun `tutte le 25 piste di riferimento sono present`() {
        val expectedKeys = setOf(
            "bahrain", "saudi", "australia", "japan", "china", "miami",
            "imola", "monaco", "canada", "spain", "catalunya", "austria",
            "uk", "hungary", "belgium", "netherlands", "italy",
            "azerbaijan", "singapore", "usa", "mexico", "brazil", "vegas",
            "qatar", "abu_dhabi",
        )
        assertEquals(expectedKeys, CircuitTracks.byKey.keys)
    }

    @Test
    fun `ogni tracciato ha un numero pari di coordinate normalizzate`() {
        CircuitTracks.byKey.forEach { (key, points) ->
            assertTrue("$key: $points punti", points.size >= 8) // min 4 punti
            assertEquals("coordinate non a coppie per $key", 0, points.size % 2)
            points.forEach { value ->
                assertTrue("$key fuori range: $value", value in 0f..1.001f)
            }
        }
    }

    @Test
    fun `mapping per nome gara - casi arbitri`() {
        assertEquals("vegas", CircuitTracks.keyFor("Las Vegas Grand Prix", "United States"))
        assertEquals("miami", CircuitTracks.keyFor("Miami Grand Prix", "United States"))
        assertEquals("imola", CircuitTracks.keyFor("Emilia Romagna Grand Prix", "Italy"))
        assertEquals("brazil", CircuitTracks.keyFor("Sao Paulo Grand Prix", "Brazil"))
        assertEquals("catalunya", CircuitTracks.keyFor("Catalan Grand Prix", "Spain"))
    }

    @Test
    fun `mapping per paese quando il nome non matcha`() {
        assertEquals("bahrain", CircuitTracks.keyFor("Gran Premio di Manama", "Bahrain"))
        assertEquals("spain", CircuitTracks.keyFor("Gran Premio", "Spain"))
        assertEquals("italy", CircuitTracks.keyFor("Gran Premio di Terra", "Italy"))
    }

    @Test
    fun `peso e' monoto giorno senza doppioni`() {
        // name keyword batte il paese, come per RaceIdentity.
        assertEquals(
            "usa",
            CircuitTracks.keyFor("United States Grand Prix", "Italy"),
        )
    }

    @Test
    fun `gara inesistente non rompe`() {
        assertNull(CircuitTracks.keyFor("Gara di Zanzibar", "Tanzania"))
        assertNull(CircuitTracks.keyFor(null, null))
    }
}