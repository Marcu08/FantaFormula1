package com.fantaformula1.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test del motore di punteggio: happy path, DNF, penalita', sprint, tie-break,
 * regole custom e preset. Ogni caso corrisponde a uno scenario reale di gioco.
 */
class ScoringEngineTest {

    private val rules = Rules.default()

    // -- Fixtures ------------------------------------------------------------

    private fun driver(
        id: String,
        position: Int? = null,
        grid: Int? = null,
        sprintPosition: Int? = null,
        fastestLap: Boolean = false,
        dnf: Boolean = false,
        dnfReason: String? = null,
        penaltyPoints: Int = 0,
        pitStops: Int = 0,
        pointsScored: Double? = null,
    ) = DriverRaceResult(
        driverId = id,
        driverName = id,
        grid = grid,
        position = position,
        sprintPosition = sprintPosition,
        fastestLap = fastestLap,
        dnf = dnf,
        dnfReason = dnfReason,
        penaltyPoints = penaltyPoints,
        pitStops = pitStops,
        pointsScored = pointsScored,
    )

    /**
     * Gara di esempio: 6 piloti, sprint attivo, safety car, pit stop.
     * VER finisce 1º, RUS 2º, LEC 3º, HAM 4º, PER 5º ritirato,
     * ALO non classificato. NESSUNA penalita' (vedi [penalizedResult]).
     */
    private fun sampleResult() = RaceResultData(
        raceId = "r1",
        drivers = listOf(
            driver("VER", position = 1, grid = 2, sprintPosition = 1, fastestLap = true,
                pitStops = 2, pointsScored = 25.0),
            driver("RUS", position = 2, grid = 1, sprintPosition = 3, pitStops = 1, pointsScored = 18.0),
            driver("LEC", position = 3, grid = 3, sprintPosition = 2, pitStops = 2, pointsScored = 15.0),
            driver("HAM", position = 4, grid = 4, sprintPosition = 4, pitStops = 1, pointsScored = 12.0),
            driver("PER", position = null, grid = 5, dnf = true, dnfReason = "Motore", pitStops = 0),
            driver("ALO", position = null, grid = 6, dnf = false, pitStops = 1),
        ),
        safetyCars = 2,
        redFlags = 0,
        laps = 58,
    )

    /** Identica a [sampleResult] ma con VER penalizzato di 5 punti. */
    private fun penalizedResult() = sampleResult().copy(
        drivers = sampleResult().drivers.map {
            if (it.driverId == "VER") it.copy(penaltyPoints = 5, pointsScored = 20.0) else it
        },
    )

    private fun entryOf(type: PredictionType, vararg driverIds: String) =
        PredictionEntry(type = type, driverIds = driverIds.toList())

    // -- 1. Vincitore --------------------------------------------------------

    @Test
    fun `vincitore indovinato prende i punti base`() {
        val e = entryOf(PredictionType.WINNER, "VER")
        assertEquals(25.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `vincitore sbagliato prende zero`() {
        val e = entryOf(PredictionType.WINNER, "LEC")
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `vincitore senza piloti nel pronostico prende zero`() {
        val e = PredictionEntry(type = PredictionType.WINNER)
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `gara senza classificati non assegna punti al vincitore`() {
        val empty = RaceResultData(raceId = "r0", drivers = emptyList())
        val e = entryOf(PredictionType.WINNER, "VER")
        assertEquals(0.0, ScoringEngine.score(e, empty, rules), 0.001)
    }

    // -- 2. Podio ------------------------------------------------------------

    @Test
    fun `podio completo nell'ordine esatto somma i tre valori`() {
        val e = entryOf(PredictionType.PODIUM, "VER", "RUS", "LEC")
        assertEquals(25.0 + 18.0 + 15.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `podio parzialmente indovinato somma solo i piloti giusti`() {
        // 2º e 3º corretti, 1º sbagliato
        val e = entryOf(PredictionType.PODIUM, "LEC", "RUS", "LEC")
        assertEquals(18.0 + 15.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `podio sbagliato del tutto prende zero`() {
        val e = entryOf(PredictionType.PODIUM, "ALO", "PER", "HAM")
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `podio con meno di tre classificati non assegna punti`() {
        val almost = RaceResultData(
            raceId = "r2",
            drivers = listOf(
                driver("VER", position = 1),
                driver("RUS", position = 2),
            ),
        )
        val e = entryOf(PredictionType.PODIUM, "VER", "RUS", "LEC")
        assertEquals(0.0, ScoringEngine.score(e, almost, rules), 0.001)
    }

    // -- 3. Pole e giro veloce ----------------------------------------------

    @Test
    fun `pole indovinata assegna i punti`() {
        // RUS ha grid == 1
        val e = entryOf(PredictionType.POLE, "RUS")
        assertEquals(10.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `pole sbagliata prende zero`() {
        val e = entryOf(PredictionType.POLE, "VER")
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `giro veloce indovinato assegna i punti`() {
        val e = entryOf(PredictionType.FASTEST_LAP, "VER")
        assertEquals(8.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `nessun giro veloce assegnato non assegna punti`() {
        val noFastest = sampleResult().copy(
            drivers = sampleResult().drivers.map { it.copy(fastestLap = false) },
        )
        val e = entryOf(PredictionType.FASTEST_LAP, "VER")
        assertEquals(0.0, ScoringEngine.score(e, noFastest, rules), 0.001)
    }

    // -- 4. Posizione esatta e fascia ---------------------------------------

    @Test
    fun `posizione esatta indovinata assegna i punti`() {
        val e = PredictionEntry(type = PredictionType.DRIVER_EXACT_POSITION, driverIds = listOf("LEC"), position = 3)
        assertEquals(12.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `posizione mancata di uno applica il fattore di tolleranza`() {
        val e = PredictionEntry(type = PredictionType.DRIVER_EXACT_POSITION, driverIds = listOf("LEC"), position = 2)
        assertEquals(12.0 * 0.5, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `posizione mancata di due non assegna punti`() {
        val e = PredictionEntry(type = PredictionType.DRIVER_EXACT_POSITION, driverIds = listOf("LEC"), position = 5)
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `posizione esatta su pilota ritirato restituisce zero`() {
        val e = PredictionEntry(type = PredictionType.DRIVER_EXACT_POSITION, driverIds = listOf("PER"), position = 5)
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `fascia di posizione contenuta assegna i punti`() {
        val e = PredictionEntry(type = PredictionType.DRIVER_POSITION_RANGE, driverIds = listOf("HAM"), rangeMin = 3, rangeMax = 5)
        assertEquals(6.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `fascia di posizione non contenuta assegna zero`() {
        val e = PredictionEntry(type = PredictionType.DRIVER_POSITION_RANGE, driverIds = listOf("HAM"), rangeMin = 1, rangeMax = 2)
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    // -- 5. Testa a testa ----------------------------------------------------

    @Test
    fun `testa a testa vinto dal pilota scelto assegna i punti`() {
        val e = entryOf(PredictionType.HEAD_TO_HEAD, "RUS", "HAM")
        assertEquals(8.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `testa a testa perso assegna zero`() {
        val e = entryOf(PredictionType.HEAD_TO_HEAD, "HAM", "RUS")
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `testa a testa vinto per ritiro dell'avversario assegna punti pieni`() {
        // PER ha ritirato, quindi HAM vince la sfida
        val e = entryOf(PredictionType.HEAD_TO_HEAD, "HAM", "PER")
        assertEquals(8.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `testa a testa tra due ritirati e' pari`() {
        val e = entryOf(PredictionType.HEAD_TO_HEAD, "PER", "ALO")
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    // -- 6. DNF ---------------------------------------------------------------

    @Test
    fun `dnf indovinato assegna i punti`() {
        val e = PredictionEntry(type = PredictionType.DRIVER_DNF, driverIds = listOf("PER"), booleanValue = true)
        assertEquals(5.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `dnf sbagliato assegna zero`() {
        val e = PredictionEntry(type = PredictionType.DRIVER_DNF, driverIds = listOf("VER"), booleanValue = true)
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    // -- 7. Punti pilota over/under ------------------------------------------

    @Test
    fun `punti pilota over indovinato assegna i punti`() {
        val e = PredictionEntry(type = PredictionType.DRIVER_POINTS_OVER_UNDER, driverIds = listOf("VER"), threshold = 20.0, over = true)
        assertEquals(7.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `punti pilota sotto soglia con over sbagliato assegna zero`() {
        val e = PredictionEntry(type = PredictionType.DRIVER_POINTS_OVER_UNDER, driverIds = listOf("HAM"), threshold = 20.0, over = true)
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `punti pilota under indovinato assegna i punti`() {
        val e = PredictionEntry(type = PredictionType.DRIVER_POINTS_OVER_UNDER, driverIds = listOf("HAM"), threshold = 20.0, over = false)
        assertEquals(7.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    // -- 8. Sprint -------------------------------------------------------------

    @Test
    fun `vincitore sprint indovinato con moltiplicatore`() {
        val e = entryOf(PredictionType.SPRINT_WINNER, "VER")
        assertEquals(10.0 * 0.5, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `gara senza sprint non assegna punti sprint`() {
        val noSprint = sampleResult().copy(
            drivers = sampleResult().drivers.map { it.copy(sprintPosition = null) },
        )
        val e = entryOf(PredictionType.SPRINT_WINNER, "VER")
        assertEquals(0.0, ScoringEngine.score(e, noSprint, rules), 0.001)
    }

    // -- 9. Safety car e pit stop ---------------------------------------------

    @Test
    fun `safety car over indovinato assegna i punti`() {
        val e = PredictionEntry(type = PredictionType.SAFETY_CAR_COUNT_OVER_UNDER, threshold = 1.0, over = true)
        assertEquals(5.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `safety car under sbagliato assegna zero`() {
        val e = PredictionEntry(type = PredictionType.SAFETY_CAR_COUNT_OVER_UNDER, threshold = 1.0, over = false)
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `pit stop totali over indovinato assegna i punti`() {
        // 2 + 1 + 2 + 1 + 0 + 1 = 7 pit stop
        val e = PredictionEntry(type = PredictionType.PIT_STOPS_OVER_UNDER, threshold = 5.0, over = true)
        assertEquals(5.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `pit stop totali sotto soglia con over perde`() {
        val e = PredictionEntry(type = PredictionType.PIT_STOPS_OVER_UNDER, threshold = 10.0, over = true)
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    // -- 10. Tipo disattivato --------------------------------------------------

    @Test
    fun `tipo di pronostico disattivato non assegna punti`() {
        val custom = rules.copy(enabledTypes = setOf(PredictionType.WINNER))
        val e = entryOf(PredictionType.POLE, "RUS")
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), custom), 0.001)
    }

    // -- 11. Moltiplicatori DNF / penalita' ------------------------------------

    @Test
    fun `punti parziali su pilota con penalita' sono ridotti`() {
        // VER ha vinto ma ha preso 5 punti di penalita': nel podio il suo posto
        // e' un punto parziale, quindi subisce il moltiplicatore penalita'.
        val e = entryOf(PredictionType.PODIUM, "VER", "RUS", "LEC")
        val expected = (25.0 * rules.penaltyMultiplier) + 18.0 + 15.0
        assertEquals(expected, ScoringEngine.score(e, penalizedResult(), rules), 0.001)
    }

    @Test
    fun `punti parziali su pilota ritirato sono ridotti del moltiplicatore dnf`() {
        // RUS e' classificato 2º ma ha dato ritiro (caso reale: completa il 90%
        // dei giri e viene classificato): i punti parziali del podio subiscono
        // il moltiplicatore DNF.
        val dnfRace = RaceResultData(
            raceId = "r3",
            drivers = listOf(
                driver("VER", position = 1, grid = 1),
                driver("RUS", position = 2, grid = 2, dnf = true),
                driver("LEC", position = 3, grid = 3),
            ),
        )
        // RUS e' 2º in classifica ma con DNF: il podio gli assegna 18 * dnfMultiplier
        val e = entryOf(PredictionType.PODIUM, "VER", "RUS", "LEC")
        val expected = 25.0 + (18.0 * rules.dnfMultiplier) + 15.0
        assertEquals(expected, ScoringEngine.score(e, dnfRace, rules), 0.001)
    }

    @Test
    fun `vincitore con penalita' prende i punti pieni della vittoria`() {
        // Il moltiplicatore penalita' non deve azzerare una vittoria: si applica
        // solo ai punti parziali. Qui VER vince -> 25 punti pieni.
        val e = entryOf(PredictionType.WINNER, "VER")
        assertEquals(25.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    // -- 12. Regole custom e preset ---------------------------------------------

    @Test
    fun `regole custom cambiano i punti assegnati`() {
        val custom = rules.copy(
            points = rules.points + (PredictionType.WINNER to 50.0),
        )
        val e = entryOf(PredictionType.WINNER, "VER")
        assertEquals(50.0, ScoringEngine.score(e, sampleResult(), custom), 0.001)
    }

    @Test
    fun `preset arcade usa i suoi punti e il moltiplicatore sprint pieno`() {
        val arcade = Rules.presetArcade()
        val e = entryOf(PredictionType.SPRINT_WINNER, "VER")
        assertEquals(5.0, ScoringEngine.score(e, sampleResult(), arcade), 0.001)
    }

    @Test
    fun `preset arcade non supporta la posizione esatta`() {
        val arcade = Rules.presetArcade()
        val e = PredictionEntry(type = PredictionType.DRIVER_EXACT_POSITION, driverIds = listOf("LEC"), position = 3)
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), arcade), 0.001)
    }

    @Test
    fun `preset f1 ufficiale coincide con le regole di default`() {
        val preset = Rules.presetF1Official()
        val e = entryOf(PredictionType.WINNER, "VER")
        assertEquals(
            ScoringEngine.score(e, sampleResult(), Rules.default()),
            ScoringEngine.score(e, sampleResult(), preset),
            0.001,
        )
    }

    // -- 13. Pronostico completo -----------------------------------------------

    @Test
    fun `pronostico completo somma tutte le voci`() {
        val prediction = RacePrediction(
            raceId = "r1",
            entries = listOf(
                entryOf(PredictionType.WINNER, "VER"),                              // 25
                entryOf(PredictionType.PODIUM, "VER", "RUS", "LEC"),                // 58
                entryOf(PredictionType.POLE, "RUS"),                                // 10
                entryOf(PredictionType.FASTEST_LAP, "VER"),                         // 8
                PredictionEntry(
                    type = PredictionType.DRIVER_EXACT_POSITION,
                    driverIds = listOf("LEC"),
                    position = 3,
                ),                                                                  // 12
            ),
        )
        assertEquals(25.0 + 58.0 + 10.0 + 8.0 + 12.0, ScoringEngine.score(prediction, sampleResult(), rules), 0.001)
    }

    // -- 14. Classifica e tie-break ---------------------------------------------

    private fun predictionFor(user: String, race: String, winner: String) =
        RacePrediction(raceId = race, entries = listOf(entryOf(PredictionType.WINNER, winner)))

    @Test
    fun `classifica ordina per punti totali decrescenti`() {
        val predictions = mapOf(
            "alice" to listOf(predictionFor("alice", "r1", "VER"), predictionFor("alice", "r2", "RUS")),
            "bob" to listOf(predictionFor("bob", "r1", "VER")),
        )
        val results = mapOf(
            "r1" to sampleResult(),
            "r2" to RaceResultData(
                raceId = "r2",
                drivers = listOf(
                    driver("RUS", position = 1, grid = 1),
                    driver("VER", position = 2, grid = 2),
                ),
            ),
        )
        val standings = ScoringEngine.computeStandings(
            predictions = predictions,
            results = results,
            rules = rules,
            displayNames = mapOf("alice" to "Alice", "bob" to "Bob"),
        )

        assertEquals(listOf("alice", "bob"), standings.map { it.userId })
        assertEquals(50.0, standings[0].totalPoints, 0.001)
        assertEquals(2, standings[0].racesPredicted)
        assertEquals(25.0, standings[1].totalPoints, 0.001)
    }

    @Test
    fun `parita' di punti risolta dal numero di gare vinte`() {
        // Gara identica a sampleResult per tutte e tre le round.
        val results = mapOf(
            "r1" to sampleResult(),
            "r2" to sampleResult().copy(raceId = "r2"),
            "r3" to sampleResult().copy(raceId = "r3"),
        )

        // alice: podio pieno (58) in r1 e r2, zero in r3 -> 116 punti, 2 vittorie
        // carlo: 55 punti in tutte e tre -> 165 punti, 1 vittoria
        val carloEntries = listOf(
            entryOf(PredictionType.WINNER, "VER"),
            entryOf(PredictionType.POLE, "RUS"),
            entryOf(PredictionType.FASTEST_LAP, "VER"),
            PredictionEntry(
                type = PredictionType.DRIVER_EXACT_POSITION,
                driverIds = listOf("LEC"),
                position = 3,
            ),
        )
        val predictions = mapOf(
            "alice" to listOf(
                RacePrediction("r1", listOf(entryOf(PredictionType.PODIUM, "VER", "RUS", "LEC"))),
                RacePrediction("r2", listOf(entryOf(PredictionType.PODIUM, "VER", "RUS", "LEC"))),
                RacePrediction("r3", listOf(entryOf(PredictionType.PODIUM, "ALO", "PER", "HAM"))),
            ),
            "carlo" to listOf(
                RacePrediction("r1", carloEntries),
                RacePrediction("r2", carloEntries),
                RacePrediction("r3", carloEntries),
            ),
        )

        // Con TOTAL_POINTS vince carlo (165 > 116)...
        val byPoints = ScoringEngine.computeStandings(
            predictions, results, rules.copy(tieBreaker = TieBreaker.TOTAL_POINTS),
        )
        assertEquals("carlo", byPoints.first().userId)

        // ...con RACE_WINS vince alice (2 vittorie contro 1).
        val byWins = ScoringEngine.computeStandings(
            predictions, results, rules.copy(tieBreaker = TieBreaker.RACE_WINS),
        )
        assertEquals("alice", byWins.first().userId)
        assertEquals(2, byWins.first().raceWins)
        assertEquals(116.0, byWins.first().totalPoints, 0.001)
    }

    @Test
    fun `miglior punteggio in gara come spareggio`() {
        val results = mapOf(
            "r1" to sampleResult(),
            "r2" to RaceResultData(
                raceId = "r2",
                drivers = listOf(
                    driver("RUS", position = 1, grid = 1),
                    driver("VER", position = 2, grid = 2),
                ),
            ),
        )
        val predictions = mapOf(
            "dario" to listOf(
                // 25 (vincitore) + 25 (posizione esatta VER 1º) = 50 in r1
                RacePrediction(
                    raceId = "r1",
                    entries = listOf(
                        entryOf(PredictionType.WINNER, "VER"),
                        PredictionEntry(
                            type = PredictionType.DRIVER_EXACT_POSITION,
                            driverIds = listOf("VER"),
                            position = 1,
                        ),
                    ),
                ),
                // 0 in r2
                predictionFor("dario", "r2", "LEC"),
            ),
            "eno" to listOf(
                RacePrediction(
                    raceId = "r1",
                    entries = listOf(
                        PredictionEntry(
                            type = PredictionType.DRIVER_EXACT_POSITION,
                            driverIds = listOf("VER"),
                            position = 1,
                        ),
                    ),
                ),
                predictionFor("eno", "r2", "RUS"),
            ),
        )
        // dario: 37 in r1 (vincitore + posizione esatta), 0 in r2 -> totale 37, best 37
        // eno:   12 in r1, 25 in r2                        -> totale 37, best 25
        val custom = rules.copy(tieBreaker = TieBreaker.BEST_SINGLE_RACE)
        val standings = ScoringEngine.computeStandings(predictions, results, custom)
        assertEquals("dario", standings.first().userId)
        assertEquals(37.0, standings.first().bestRacePoints, 0.001)
        assertEquals(37.0, standings.first().totalPoints, 0.001)
    }

    @Test
    fun `gara senza risultato non viene conteggiata`() {
        val predictions = mapOf(
            "alice" to listOf(predictionFor("alice", "r1", "VER"), predictionFor("alice", "rX", "VER")),
        )
        val results = mapOf("r1" to sampleResult())
        val scores = ScoringEngine.computeRaceScores(predictions, results, rules)
        assertEquals(1, scores.size)
        assertEquals("r1", scores[0].raceId)
    }

    @Test
    fun `punteggio gara per ogni giocatore e' calcolato separatamente`() {
        val predictions = mapOf(
            "alice" to listOf(predictionFor("alice", "r1", "VER")),
            "bob" to listOf(predictionFor("bob", "r1", "LEC")),
        )
        val scores = ScoringEngine.computeRaceScores(predictions, mapOf("r1" to sampleResult()), rules)
        val byUser = scores.associate { it.userId to it.points }
        assertEquals(25.0, byUser["alice"]!!, 0.001)
        assertEquals(0.0, byUser["bob"]!!, 0.001)
    }

    // -- 15. Deadline -------------------------------------------------------------

    @Test
    fun `pronostico inviabile prima della deadline`() {
        assertTrue(ScoringEngine.canSubmit(deadlineEpochMs = 1_000L, nowEpochMs = 999L, rules = rules))
    }

    @Test
    fun `pronostico bloccato esattamente alla deadline`() {
        assertFalse(ScoringEngine.canSubmit(deadlineEpochMs = 1_000L, nowEpochMs = 1_000L, rules = rules))
    }

    @Test
    fun `pronostico bloccato dopo la deadline`() {
        assertFalse(ScoringEngine.canSubmit(deadlineEpochMs = 1_000L, nowEpochMs = 1_001L, rules = rules))
    }

    @Test
    fun `senza deadline si puo' sempre inviare`() {
        assertTrue(ScoringEngine.canSubmit(deadlineEpochMs = null, nowEpochMs = Long.MAX_VALUE, rules = rules))
    }

    @Test
    fun `modifiche bloccate quando allowEditUntilDeadline e' falso`() {
        val strict = rules.copy(allowEditUntilDeadline = false)
        assertFalse(ScoringEngine.canSubmit(deadlineEpochMs = 1_000L, nowEpochMs = 10L, rules = strict))
    }

    @Test
    fun `altri pronostici visibili solo dopo la deadline`() {
        assertFalse(ScoringEngine.canRevealOthers(deadlineEpochMs = 1_000L, nowEpochMs = 999L, rules = rules))
        assertTrue(ScoringEngine.canRevealOthers(deadlineEpochMs = 1_000L, nowEpochMs = 1_000L, rules = rules))
    }

    @Test
    fun `nessuna rivelazione se revealOthersAfterDeadline e' falso`() {
        val hidden = rules.copy(revealOthersAfterDeadline = false)
        assertFalse(ScoringEngine.canRevealOthers(deadlineEpochMs = 1_000L, nowEpochMs = 5_000L, rules = hidden))
    }
}
