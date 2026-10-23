package com.fantaformula1.app.data.notification

import com.fantaformula1.app.core.domain.PredictionEntry
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.core.domain.Rules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1: il promemoria di scadenza deve partire SOLO se il pronostico manca o
 * e' incompleto (mancano tipi attivi dalle regole). Chi ha compilato tutto
 * non riceve notifiche.
 */
class DeadlineReminderLogicTest {

    private val rules = Rules.default()

    private fun predictionOf(vararg types: PredictionType) = RacePrediction(
        raceId = "r1",
        entries = types.map { PredictionEntry(type = it) },
    )

    @Test
    fun `pronostico assente e' incompleto`() {
        assertTrue(isPredictionIncomplete(null, rules))
    }

    @Test
    fun `pronostico vuoto e' incompleto`() {
        assertTrue(isPredictionIncomplete(RacePrediction(raceId = "r1"), rules))
    }

    @Test
    fun `pronostico parziale e' incompleto`() {
        val partial = predictionOf(PredictionType.WINNER, PredictionType.PODIUM)
        assertTrue(isPredictionIncomplete(partial, rules))
    }

    @Test
    fun `pronostico con tutti i tipi attivi e' completo`() {
        // I default attivi sono tutti meno exact position, safety car e pit
        // stop (F6): coprirli tutti significa "completo".
        val full = predictionOf(*rules.enabledTypes.toTypedArray())
        assertFalse(isPredictionIncomplete(full, rules))
    }

    @Test
    fun `safety car e pit stop non richiesti se disattivati`() {
        // F6: i due tipi non attivi non rendono incompleto il pronostico.
        val withoutOverUnder = rules.enabledTypes
            .filterNot {
                it == PredictionType.SAFETY_CAR_COUNT_OVER_UNDER ||
                    it == PredictionType.PIT_STOPS_OVER_UNDER
            }
        val full = predictionOf(*withoutOverUnder.toTypedArray())
        assertFalse(isPredictionIncomplete(full, rules))
    }

    @Test
    fun `senza regole nota il richiamo degrada sulle entry`() {
        // Regole non caricabili (errore di rete): un pronostico con almeno
        // una voce non viene marcato incompleto, uno vuoto si.
        assertFalse(isPredictionIncomplete(predictionOf(PredictionType.WINNER), null))
        assertTrue(isPredictionIncomplete(RacePrediction(raceId = "r1"), null))
    }
}
