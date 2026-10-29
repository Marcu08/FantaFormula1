package com.fantaformula1.app.ui.results

import com.fantaformula1.app.core.domain.RaceResultData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La tastiera italiana usa la virgola: non deve perdere i punti inseriti. */
class PointsParsingTest {

    @Test
    fun `punto e virgola decimale sono equivalenti`() {
        assertEquals(12.5, parseDecimal("12.5")!!, 0.001)
        assertEquals(12.5, parseDecimal("12,5")!!, 0.001)
    }

    @Test
    fun `testo vuoto o non numerico resta null`() {
        assertNull(parseDecimal(""))
        assertNull(parseDecimal("abc"))
    }

    @Test
    fun `zero safety car resta zero nel form`() {
        assertEquals("0", safetyCarsFormValue(RaceResultData(raceId = "r1", safetyCars = 0)))
        assertEquals("", safetyCarsFormValue(RaceResultData(raceId = "r1", safetyCars = null)))
    }

    @Test
    fun `la conferma SC import e' una modifica e non viene ricaricata via`() {
        val state = ResultUiState(
            safetyCars = "",
            safetyCarsUnknown = true,
            scImport = ScImportState.Loading,
        ).withConfirmedSafetyCar(0)

        assertEquals("0", state.safetyCars)
        assertFalse(state.safetyCarsUnknown)
        assertTrue(state.edited)
        assertEquals(ScImportState.Idle, state.scImport)
    }

    @Test
    fun `il totale pit stop esplicito resta nel form`() {
        assertEquals(
            "40",
            totalPitStopsFormValue(RaceResultData(raceId = "r1", totalPitStops = 40)),
        )
        assertEquals("", totalPitStopsFormValue(RaceResultData(raceId = "r1")))
    }

    @Test
    fun `il totale esplicito si conserva salvo modifiche per pilota`() {
        val state = ResultUiState(totalPitStops = "40")
        assertEquals(40, totalPitStopsForSave(state))
        assertEquals(null, totalPitStopsForSave(state.copy(pitStopsEdited = true)))
    }
}
