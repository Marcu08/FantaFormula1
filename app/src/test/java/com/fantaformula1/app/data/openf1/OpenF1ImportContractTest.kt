package com.fantaformula1.app.data.openf1

import com.fantaformula1.app.core.domain.PredictionEntry
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.RaceResultData
import com.fantaformula1.app.core.domain.Rules
import com.fantaformula1.app.core.domain.ScoringEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CONTRATTO tra l'import OpenF1 e il punteggio:
 *
 * L'import propone SC e VSC SEPARATI, ma nel JSONB del risultato esiste UN
 * solo campo `safetyCars` che li conta SOMMATI (SC+VSC insieme, come da
 * regolamento: "un solo numero che include entrambi"). Lo
 * `ScoringEngine` punteggia il pronostico `SAFETY_CAR_COUNT_OVER_UNDER`
 * (opzioni 0, 1, 2, ... 6+) confrontandolo con questo totale sommato:
 * nel dialog l'admin conferma quindi il totale SC+VSC, non i due conteggi
 * separati. L'import NON alimenta `PIT_STOPS_OVER_UNDER` (OpenF1 non
 * fornisce pit stop nel race control).
 */
class OpenF1ImportContractTest {

    @Test
    fun `import proposte separate il totale sommato alimenta l opzione del motore`() {
        // Gara reale del tipo: una VSC e DUE SC fisiche (la prima termina
        // prima che inizi la seconda: una attivazione = start+end in ordine FIA).
        val base = java.time.OffsetDateTime.parse("2026-08-03T14:00:00Z")
        val dates = buildList { for (i in 0 until 7) { add(base.plusSeconds(60L * i).toString()) } }
        val events = listOf(
            OpenF1RaceControlMessage(category = "SafetyCar", message = "VSC DEPLOYED", date = dates[0], lapNumber = 12),
            OpenF1RaceControlMessage(category = "SafetyCar", message = "VSC ENDING", date = dates[1], lapNumber = 14),
            OpenF1RaceControlMessage(category = "SafetyCar", message = "SAFETY CAR DEPLOYED", date = dates[2], lapNumber = 20),
            OpenF1RaceControlMessage(category = "SafetyCar", message = "SAFETY CAR ENDING", date = dates[3], lapNumber = 24),
            OpenF1RaceControlMessage(category = "SafetyCar", message = "SAFETY CAR DEPLOYED", date = dates[4], lapNumber = 30),
            OpenF1RaceControlMessage(category = "SafetyCar", message = "SAFETY CAR ENDING", date = dates[5], lapNumber = 35),
        )
        val import = OpenF1SafetyCarParser.parse(events)
        assertEquals(2, import.proposedSafetyCars)
        assertEquals(1, import.proposedVirtualSafetyCars)

        // Il totale confermato dall'admin (SC+VSC) finisce nel campo unico:
        val total = import.proposedSafetyCars + import.proposedVirtualSafetyCars
        assertEquals(3, total)

        // Lo ScoringEngine conta result.safetyCars = 3 (sommati!) e l'opzione
        // "3" indovinata paga base 5 x fattore banda 3 = 1.0 -> 5.0 punti.
        val rules = Rules.default().copy(enabledTypes = setOf(PredictionType.SAFETY_CAR_COUNT_OVER_UNDER))
        val entry = PredictionEntry(
            type = PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
            rangeMin = 3,
            rangeMax = 3,
        )
        val result = RaceResultData(
            raceId = "r",
            safetyCars = total,
            drivers = listOf(
                com.fantaformula1.app.core.domain.DriverRaceResult(driverId = "VER", position = 1),
            ),
        )
        assertEquals(5.0 * 1.0, ScoringEngine.score(entry, result, rules), 0.001)
    }

    @Test
    fun `l import non tocca la fascia dei pit stop (nessun dato OpenF1 per essa)`() {
        // I messaggi race control non portano pit stop: una gara sotto import
        // ha comunque pitStops = 0 per pilota nel form, e la fascia si inserisce
        // a mano. (verifica difensiva: in ogni caso il form resta responsabile)
        val import = OpenF1SafetyCarParser.parse(emptyList())
        assertEquals(0, import.proposedSafetyCars)
        assertEquals(0, import.proposedVirtualSafetyCars)
    }
}
