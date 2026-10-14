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

    // Tutti i tipi attivi: la suite copre la semantica di punteggio per ogni
    // tipo, indipendente dal default delle nuove leghe (che da oggi esclude
    // DRIVER_EXACT_POSITION).
    private val rules = Rules.default().copy(
        enabledTypes = PredictionType.entries.toSet(),
    )

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
        val e = PredictionEntry(type = PredictionType.PODIUM, driverIds = listOf("HAM", "PER", "ALO"))
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `bonus podio senza ordine assegnato se il set e' giusto ma l'ordine no`() {
        // RUS-LEC-VER: set identico al podio reale [VER, RUS, LEC] ma zero
        // slot correcti -> bonus.
        val expected = rules.copy(podiumNoOrderPoints = 10.0)
        val e = PredictionEntry(type = PredictionType.PODIUM, driverIds = listOf("RUS", "LEC", "VER"))
        assertEquals(10.0, ScoringEngine.score(e, sampleResult(), expected), 0.001)
    }

    @Test
    fun `bonus podio senza ordine non applicato slot corretti`() {
        // ORDINE esatto: prende i punti per slot (somma), NON il bonus.
        val expected = rules.copy(podiumNoOrderPoints = 10.0)
        val e = PredictionEntry(type = PredictionType.PODIUM, driverIds = listOf("VER", "RUS", "LEC"))
        assertEquals(25.0 + 18.0 + 15.0, ScoringEngine.score(e, sampleResult(), expected), 0.001)
    }

    @Test
    fun `bonus podio senza ordine disattivato (default) non assegna nulla`() {
        // Default = 0: comportamento invariato rispetto alla storia.
        val e = PredictionEntry(type = PredictionType.PODIUM, driverIds = listOf("RUS", "LEC", "VER"))
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `bonus podio non applicato se ai piloti manca uno del podio`() {
        val expected = rules.copy(podiumNoOrderPoints = 10.0)
        val e = PredictionEntry(type = PredictionType.PODIUM, driverIds = listOf("NOR", "VER", "HAM"))
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), expected), 0.001)
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
    fun `fascia +-2 assegna il fattore ridotto quando attivo`() {
        // VER arriva 1º, pronostico sulla 3ª: distanza 2, fascia attiva a 0.25.
        val banded = rules.copy(exactPositionMissFactor2 = 0.25)
        val e = PredictionEntry(type = PredictionType.DRIVER_EXACT_POSITION, driverIds = listOf("VER"), position = 3)
        assertEquals(12.0 * 0.25, ScoringEngine.score(e, sampleResult(), banded), 0.001)
    }

    @Test
    fun `fascia +-2 disattivata di default resta zero`() {
        // Default 0.0: nessuna modifica retroattiva dei punteggi storici.
        val e = PredictionEntry(type = PredictionType.DRIVER_EXACT_POSITION, driverIds = listOf("VER"), position = 3)
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), rules), 0.001)
    }

    @Test
    fun `fascia +-2 non puo' pagare piu' della fascia +-1`() {
        val invalid = rules.copy(
            exactPositionNearMissFactor = 0.3,
            exactPositionMissFactor2 = 0.5,
        )
        assertTrue(invalid.validate().isNotEmpty())
    }

    @Test
    fun `fascia +-2 uguale alla +-1 e' ammessa dalla validazione`() {
        val ok = rules.copy(
            exactPositionNearMissFactor = 0.5,
            exactPositionMissFactor2 = 0.5,
        )
        assertEquals(emptyList<String>(), ok.validate())
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
    fun `pilota ritirato ma classificato non subisce piu' lo sconto dnf`() {
        // B11: dnfMultiplier e' deprecato e non viene piu' applicato. Un pilota
        // che ha dato ritiro ma e' classificato (90% dei giri) prende i punti
        // del podio per intero.
        val dnfRace = RaceResultData(
            raceId = "r3",
            drivers = listOf(
                driver("VER", position = 1, grid = 1),
                driver("RUS", position = 2, grid = 2, dnf = true),
                driver("LEC", position = 3, grid = 3),
            ),
        )
        val e = entryOf(PredictionType.PODIUM, "VER", "RUS", "LEC")
        val expected = 25.0 + 18.0 + 15.0
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
    fun `i punti totali vincono sempre, lo spareggio solo a parita' reale`() {
        // B1: lo spareggio configurato NON puo' far passare davanti chi ha
        // totalizzato meno punti. carlo ha 165 punti, alice 116 (anche con piu'
        // vittorie): deve vincere carlo con OGNI spareggio.
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

        // ...con RACE_WINS vince ugualmente carlo: 165 > 116 nonostante le 2
        // vittorie di alice (il bug B1 faceva vincere alice).
        val byWins = ScoringEngine.computeStandings(
            predictions, results, rules.copy(tieBreaker = TieBreaker.RACE_WINS),
        )
        assertEquals("carlo", byWins.first().userId)
        assertEquals(165.0, byWins.first().totalPoints, 0.001)
        assertEquals(2, byWins[1].raceWins)
        assertEquals(116.0, byWins[1].totalPoints, 0.001)
    }

    @Test
    fun `a parita' di punti lo spareggio sceglie il vincitore`() {
        // Stessi 58 punti per entrambi, ma alice ha vinto 2 gare e carlo 1:
        // con RACE_WINS va prima alice. Con BEST_SINGLE_RACE va prima alice
        // (25 contro 20). Con TOTAL_POINTS la catena deterministica decide.
        val results = mapOf(
            "r1" to sampleResult(),
            "r2" to sampleResult().copy(raceId = "r2"),
            "r3" to sampleResult().copy(raceId = "r3"),
        )
        // alice: 25 (vincitore r1) + 25 (vincitore r2) + 8 (gv r3) = 58, 2 vittorie
        // carlo: (gv+esatta 20) + (gv+esatta 20) + (pole+gv 18)    = 58, 1 vittoria
        val twenty = listOf(
            entryOf(PredictionType.FASTEST_LAP, "VER"),
            PredictionEntry(
                type = PredictionType.DRIVER_EXACT_POSITION,
                driverIds = listOf("LEC"),
                position = 3,
            ),
        )
        val predictions = mapOf(
            "alice" to listOf(
                RacePrediction("r1", listOf(entryOf(PredictionType.WINNER, "VER"))),
                RacePrediction("r2", listOf(entryOf(PredictionType.WINNER, "VER"))),
                RacePrediction("r3", listOf(entryOf(PredictionType.FASTEST_LAP, "VER"))),
            ),
            "carlo" to listOf(
                RacePrediction("r1", twenty),
                RacePrediction("r2", twenty),
                RacePrediction(
                    "r3",
                    listOf(
                        entryOf(PredictionType.POLE, "RUS"),
                        entryOf(PredictionType.FASTEST_LAP, "VER"),
                    ),
                ),
            ),
        )

        val byWins = ScoringEngine.computeStandings(
            predictions, results, rules.copy(tieBreaker = TieBreaker.RACE_WINS),
        )
        assertEquals(58.0, byWins[0].totalPoints, 0.001)
        assertEquals(58.0, byWins[1].totalPoints, 0.001)
        assertEquals("alice", byWins.first().userId)
        assertEquals(2, byWins.first().raceWins)

        val byBest = ScoringEngine.computeStandings(
            predictions, results, rules.copy(tieBreaker = TieBreaker.BEST_SINGLE_RACE),
        )
        assertEquals("alice", byBest.first().userId)
        assertEquals(25.0, byBest.first().bestRacePoints, 0.001)
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

    // -- 14b. Sicurezza raceId e membri (B4/B6) -------------------------------

    @Test
    fun `raceId vuoto non viene conteggiato (B4)`() {
        // Un pronostico senza raceId (corrotto o manomesso) non deve entrare
        // nel calcolo: i repository forzano il raceId dalla colonna.
        val forged = RacePrediction(
            raceId = "",
            entries = listOf(entryOf(PredictionType.WINNER, "VER")),
        )
        val predictions = mapOf("alice" to listOf(forged))
        val scores = ScoringEngine.computeRaceScores(predictions, mapOf("r1" to sampleResult()), rules)
        assertTrue(scores.isEmpty())
    }

    @Test
    fun `un solo pronostico per coppia utente-gara anche con duplicati (B4)`() {
        // Se per errore arrivano due pronostici per la stessa gara, il motore
        // ne conta uno solo (il primo incontrato) e non somma due volte.
        val predictions = mapOf(
            "alice" to listOf(
                predictionFor("alice", "r1", "VER"),
                predictionFor("alice", "r1", "LEC"),
            ),
        )
        val scores = ScoringEngine.computeRaceScores(predictions, mapOf("r1" to sampleResult()), rules)
        assertEquals(1, scores.size)
        assertEquals(25.0, scores[0].points, 0.001)
    }

    @Test
    fun `membro senza pronostici compare a 0 punti in classifica (B6)`() {
        val predictions = mapOf(
            "alice" to listOf(predictionFor("alice", "r1", "VER")),
        )
        val standings = ScoringEngine.computeStandings(
            predictions = predictions,
            results = mapOf("r1" to sampleResult()),
            rules = rules,
            displayNames = mapOf("alice" to "Alice", "bob" to "Bob"),
        )
        assertEquals(listOf("alice", "bob"), standings.map { it.userId })
        assertEquals(25.0, standings[0].totalPoints, 0.001)
        assertEquals(0.0, standings[1].totalPoints, 0.001)
        assertEquals("Bob", standings[1].displayName)
        assertEquals(0, standings[1].racesPredicted)
    }

    @Test
    fun `ex membro con pronostici non mostra l'UUID (B6)`() {
        val predictions = mapOf(
            "alice" to listOf(predictionFor("alice", "r1", "VER")),
            "ex-uuid-1234" to listOf(predictionFor("ex-uuid-1234", "r1", "LEC")),
        )
        val standings = ScoringEngine.computeStandings(
            predictions = predictions,
            results = mapOf("r1" to sampleResult()),
            rules = rules,
            displayNames = mapOf("alice" to "Alice"),
        )
        assertEquals(2, standings.size)
        val ex = standings.first { it.userId == "ex-uuid-1234" }
        assertEquals(ScoringEngine.EX_MEMBER_NAME, ex.displayName)
    }

    // -- 15. Deadline (Race.isPredictionOpen, B9/B10) ---------------------------
    //
    // Le vecchie canSubmit/isClosed/canRevealOthers di ScoringEngine sono state
    // rimosse: la logica vive in Race.isPredictionOpen, che considera sia la
    // deadline sia lo status della gara.

    private fun raceWith(
        deadline: java.time.Instant? = null,
        status: RaceStatus = RaceStatus.SCHEDULED,
    ) = Race(
        id = "r1",
        seasonId = "s1",
        leagueId = "l1",
        round = 1,
        name = "Gara",
        deadline = deadline,
        status = status,
    )

    @Test
    fun `pronostico inviabile prima della deadline`() {
        val now = java.time.Instant.parse("2026-06-01T00:00:00Z")
        val deadline = java.time.Instant.parse("2026-06-01T01:00:00Z")
        assertTrue(raceWith(deadline = deadline).isPredictionOpen(now))
    }

    @Test
    fun `pronostico bloccato esattamente alla deadline`() {
        val now = java.time.Instant.parse("2026-06-01T00:00:00Z")
        assertFalse(raceWith(deadline = now).isPredictionOpen(now))
    }

    @Test
    fun `pronostico bloccato dopo la deadline`() {
        val now = java.time.Instant.parse("2026-06-01T02:00:00Z")
        val deadline = java.time.Instant.parse("2026-06-01T01:00:00Z")
        assertFalse(raceWith(deadline = deadline).isPredictionOpen(now))
    }

    @Test
    fun `senza deadline si puo' sempre inviare`() {
        val now = java.time.Instant.parse("2099-01-01T00:00:00Z")
        assertTrue(raceWith(deadline = null).isPredictionOpen(now))
    }

    @Test
    fun `gara chiusa non accetta pronostici anche senza deadline`() {
        // B10: lo status CLOSED blocca l'invio anche se la deadline non e' passata.
        val now = java.time.Instant.parse("2026-06-01T00:00:00Z")
        assertFalse(raceWith(deadline = null, status = RaceStatus.CLOSED).isPredictionOpen(now))
        assertFalse(raceWith(deadline = null, status = RaceStatus.COMPLETED).isPredictionOpen(now))
        assertTrue(raceWith(deadline = null, status = RaceStatus.OPEN).isPredictionOpen(now))
        assertTrue(raceWith(deadline = null, status = RaceStatus.SCHEDULED).isPredictionOpen(now))
    }

    // -- Regole malformate: il motore non produce mai punteggi negativi -------

    @Test
    fun `punti negativi nelle regole non generano punteggi negativi`() {
        // Il jsonb delle regole e' scritto dall'admin: Rules.validate vieta i
        // valori negativi, ma il motore si difende anche da dati non validi.
        val negativeRules = rules.copy(
            points = rules.points + (PredictionType.WINNER to -25.0),
            penaltyMultiplier = -1.0,
        )
        val e = entryOf(PredictionType.WINNER, "VER")
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), negativeRules), 0.001)
    }

    @Test
    fun `punti podio negativi non generano punteggi negativi`() {
        val negativeRules = rules.copy(
            podiumPoints = listOf(-25.0, -18.0, -15.0),
        )
        val e = PredictionEntry(
            type = PredictionType.PODIUM,
            driverIds = listOf("VER", "RUS", "LEC"),
        )
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), negativeRules), 0.001)
    }

    @Test
    fun `fattore near miss negativo non genera punteggi negativi`() {
        val negativeRules = rules.copy(exactPositionNearMissFactor = -0.5)
        // VER arriva 1º, pronostico sulla 2ª posizione: near miss con fattore negativo.
        val e = PredictionEntry(
            type = PredictionType.DRIVER_EXACT_POSITION,
            driverIds = listOf("VER"),
            position = 2,
        )
        assertEquals(0.0, ScoringEngine.score(e, sampleResult(), negativeRules), 0.001)
    }
}
