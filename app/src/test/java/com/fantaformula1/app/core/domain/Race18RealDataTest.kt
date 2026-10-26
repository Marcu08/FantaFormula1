package com.fantaformula1.app.core.domain

import com.fantaformula1.app.data.util.AppJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressione sui dati REALI della gara di Austin 2026 (fixture esportata
 * dal database di produzione, `race18_real.json`):
 * safety car = 2, 32 pit stop totali (dai pit per pilota, totale esplicito
 * assente) e posizioni reali.
 *
 * Copre il bug del dettaglio punti: le opzioni a FASCIA (rangeMin/rangeMax)
 * venivano ignorate e il testo diceva "dati della gara non disponibili"
 * anche quando il dato c'era e il punteggio era calcolato.
 */
class Race18RealDataTest {

    private fun realResult(): RaceResultData = AppJson.decodeFromString(
        RaceResultData.serializer(),
        File("src/test/resources/race18_real.json").readText(),
    )

    private val rules = Rules.default().copy(enabledTypes = PredictionType.entries.toSet())

    @Test
    fun `il risultato reale espone safety e pit stop`() {
        val result = realResult()
        assertEquals(2, result.safetyCars)
        assertEquals(null, result.totalPitStops)
        // Fallback confermato: somma delle fermate per pilota = 32.
        assertEquals(32, result.confirmedTotalPitStops())
        assertEquals(22, result.drivers.size)
        assertEquals(56, result.laps)
    }

    @Test
    fun `opzione a fascia safety 2-2 indovinata e spiegata`() {
        val line = ScoringEngine.explain(
            PredictionEntry(
                type = PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
                rangeMin = 2,
                rangeMax = 2,
            ),
            realResult(),
            rules,
        )
        println("SC: ${line.points} | ${line.detail}")
        assertTrue("safety 2 dentro la fascia 2-2", line.points > 0.0)
        assertFalse("niente dati-non-disponibili: il dato c'e'", line.detail.contains(ScoringEngine.NO_DATA_LABEL))
        assertTrue("la fascia va mostrata", line.detail.contains("fascia 2"))
    }

    @Test
    fun `opzione a fascia pit stop 46-55 sbagliata ma spiegata (32 non e' nella fascia)`() {
        val line = ScoringEngine.explain(
            PredictionEntry(
                type = PredictionType.PIT_STOPS_OVER_UNDER,
                rangeMin = 46,
                rangeMax = 55,
            ),
            realResult(),
            rules,
        )
        println("PIT: ${line.points} | ${line.detail}")
        assertEquals(0.0, line.points, 0.001)
        assertFalse("niente dati-non-disponibili: il dato c'e'", line.detail.contains(ScoringEngine.NO_DATA_LABEL))
        assertTrue("la fascia va mostrata", line.detail.contains("fascia 46-55"))
        assertTrue("il valore reale va mostrato", line.detail.contains("32"))
    }
}
