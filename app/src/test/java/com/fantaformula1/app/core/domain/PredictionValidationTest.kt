package com.fantaformula1.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionValidationTest {

    private val rules = Rules.default()

    private fun entry(
        type: PredictionType,
        driverIds: List<String> = emptyList(),
        position: Int? = null,
        rangeMin: Int? = null,
        rangeMax: Int? = null,
        threshold: Double? = null,
        over: Boolean? = null,
        booleanValue: Boolean? = null,
    ) = PredictionEntry(
        type = type,
        driverIds = driverIds,
        position = position,
        rangeMin = rangeMin,
        rangeMax = rangeMax,
        threshold = threshold,
        over = over,
        booleanValue = booleanValue,
    )

    private fun completeSprintEntries(): List<PredictionEntry> = listOf(
        entry(PredictionType.WINNER, listOf("VER")),
        entry(PredictionType.PODIUM, listOf("VER", "NOR", "LEC")),
        entry(PredictionType.POLE, listOf("VER")),
        entry(PredictionType.FASTEST_LAP, listOf("NOR")),
        entry(PredictionType.DRIVER_EXACT_POSITION, listOf("LEC"), position = 3),
        entry(PredictionType.DRIVER_POSITION_RANGE, listOf("HAM"), rangeMin = 4, rangeMax = 6),
        entry(PredictionType.HEAD_TO_HEAD, listOf("VER", "LEC")),
        entry(PredictionType.DRIVER_DNF, listOf("STR"), booleanValue = true),
        entry(
            PredictionType.DRIVER_POINTS_OVER_UNDER,
            listOf("VER"),
            threshold = 18.5,
            over = true,
        ),
        entry(PredictionType.SPRINT_WINNER, listOf("NOR")),
        entry(
            PredictionType.SAFETY_CAR_COUNT_OVER_UNDER,
            threshold = 1.5,
            over = false,
        ),
        entry(PredictionType.PIT_STOPS_OVER_UNDER, threshold = 42.5, over = true),
    )

    @Test
    fun `pronostico completo e valido`() {
        val prediction = RacePrediction(
            raceId = "r1",
            entries = completeSprintEntries(),
            submittedAtEpochMs = 1_000L,
        )
        val errors = PredictionValidation.validate(
            prediction,
            rules,
            isSprintRace = true,
            nowEpochMs = 2_000L,
        )
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun `pronostico parziale ammesso (B15)`() {
        // B15: i tipi attivi non pronosticati non generano errore: basta un
        // solo tipo compilato. Resta il controllo sui singoli entry.
        val prediction = RacePrediction(entries = listOf(entry(PredictionType.WINNER, listOf("VER"))))
        val errors = PredictionValidation.validate(prediction, rules, isSprintRace = true)
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun `pronostico vuoto ammesso ma inutile (B15)`() {
        // Anche nessun entry e' formalmente valido: il giocatore puo' decidere
        // di non giocare la gara. Verra' contato a 0 punti.
        val prediction = RacePrediction(entries = emptyList())
        val errors = PredictionValidation.validate(prediction, rules, isSprintRace = true)
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun `sprint_winner su gara senza sprint e' un errore`() {
        val prediction = RacePrediction(entries = completeSprintEntries())
        val errors = PredictionValidation.validate(prediction, rules, isSprintRace = false)
        assertTrue(errors.any { it.contains("la gara non ha lo sprint") })
    }

    @Test
    fun `numero di piloti errato`() {
        val prediction = RacePrediction(
            entries = completeSprintEntries().map {
                if (it.type == PredictionType.PODIUM) it.copy(driverIds = listOf("VER", "NOR")) else it
            },
        )
        val errors = PredictionValidation.validate(prediction, rules, isSprintRace = true)
        assertTrue(errors.any { it.contains("servono 3 pilota/i") })
    }

    @Test
    fun `piloti ripetuti nello stesso pronostico`() {
        val prediction = RacePrediction(
            entries = completeSprintEntries().map {
                if (it.type == PredictionType.HEAD_TO_HEAD) {
                    it.copy(driverIds = listOf("VER", "VER"))
                } else {
                    it
                }
            },
        )
        val errors = PredictionValidation.validate(prediction, rules, isSprintRace = true)
        assertTrue(errors.any { it.contains("devono essere diversi") })
    }

    @Test
    fun `posizione esatta fuori range`() {
        val prediction = RacePrediction(
            entries = completeSprintEntries().map {
                if (it.type == PredictionType.DRIVER_EXACT_POSITION) it.copy(position = 25) else it
            },
        )
        val errors = PredictionValidation.validate(prediction, rules, isSprintRace = true)
        assertTrue(errors.any { it.contains("posizione tra 1 e ${PredictionValidation.MAX_POSITION}") })
    }

    @Test
    fun `posizione 21 e 22 valida con la griglia 2026 (B16)`() {
        // La griglia 2026 ha 22 piloti: le posizioni 21 e 22 sono valide.
        val prediction = RacePrediction(
            entries = listOf(
                entry(PredictionType.DRIVER_EXACT_POSITION, listOf("BOT"), position = 22),
            ),
        )
        assertEquals(
            emptyList<String>(),
            PredictionValidation.validate(prediction, rules, isSprintRace = false),
        )
    }

    @Test
    fun `fascia invertita o fuori range`() {
        val prediction = RacePrediction(
            entries = completeSprintEntries().map {
                if (it.type == PredictionType.DRIVER_POSITION_RANGE) {
                    it.copy(rangeMin = 6, rangeMax = 4)
                } else {
                    it
                }
            },
        )
        val errors = PredictionValidation.validate(prediction, rules, isSprintRace = true)
        assertTrue(
            errors.any {
                it.contains("fascia tra 1 e ${PredictionValidation.MAX_POSITION}")
            },
        )
    }

    @Test
    fun `dnf senza valore atteso`() {
        val prediction = RacePrediction(
            entries = completeSprintEntries().map {
                if (it.type == PredictionType.DRIVER_DNF) it.copy(booleanValue = null) else it
            },
        )
        val errors = PredictionValidation.validate(prediction, rules, isSprintRace = true)
        assertTrue(errors.any { it.contains("indica se il pilota ritira") })
    }

    @Test
    fun `over under senza soglia o direzione`() {
        val prediction = RacePrediction(
            entries = completeSprintEntries().map {
                when (it.type) {
                    PredictionType.DRIVER_POINTS_OVER_UNDER ->
                        it.copy(threshold = null, over = null)
                    PredictionType.SAFETY_CAR_COUNT_OVER_UNDER -> it.copy(over = null)
                    else -> it
                }
            },
        )
        val errors = PredictionValidation.validate(prediction, rules, isSprintRace = true)
        assertTrue(errors.any { it.contains("soglia di punti non valida") })
        assertTrue(errors.any { it.contains("sopra o sotto la soglia") })
        assertTrue(errors.any { it.contains("Safety car over/under") })
    }

    @Test
    fun `tipo non attivo per la lega`() {
        val custom = rules.copy(enabledTypes = setOf(PredictionType.WINNER))
        val prediction = RacePrediction(
            entries = listOf(
                entry(PredictionType.WINNER, listOf("VER")),
                entry(PredictionType.POLE, listOf("LEC")),
            ),
        )
        val errors = PredictionValidation.validate(prediction, custom, isSprintRace = false)
        assertTrue(errors.any { it.contains("non è attivo per questa lega") })
        // l'unico tipo attivo (Vincitore) e' presente: niente "manca"
        assertTrue(errors.none { it.contains("Manca il pronostico") })
    }

    @Test
    fun `due pronostici dello stesso tipo`() {
        val prediction = RacePrediction(
            entries = completeSprintEntries() + entry(PredictionType.WINNER, listOf("NOR")),
        )
        val errors = PredictionValidation.validate(prediction, rules, isSprintRace = true)
        assertTrue(errors.any { it.contains("solo un pronostico per tipo") })
    }
}
