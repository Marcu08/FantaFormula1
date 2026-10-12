package com.fantaformula1.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test del costruttore della classifica: verifica che l'aggregazione dei dati
 * (membri + gare + esiti + pronostici) produca i totali e l'ordine giusti.
 */
class LeagueStandingsTest {

    private val rules = Rules.default()

    private fun race(id: String, round: Int, name: String) = Race(
        id = id,
        seasonId = "s1",
        leagueId = "l1",
        round = round,
        name = name,
    )

    /** Gara vinta da VER, podio VER/NOR/LEC, giro veloce NOR. */
    private fun raceResult(raceId: String) = RaceResultData(
        raceId = raceId,
        drivers = listOf(
            DriverRaceResult(driverId = "VER", driverName = "Max Verstappen", grid = 1, position = 1),
            DriverRaceResult(driverId = "NOR", driverName = "Lando Norris", grid = 2, position = 2, fastestLap = true),
            DriverRaceResult(driverId = "LEC", driverName = "Charles Leclerc", grid = 3, position = 3),
        ),
        laps = 58,
    )

    private fun winnerPrediction(raceId: String, driverId: String) = RacePrediction(
        raceId = raceId,
        entries = listOf(PredictionEntry(type = PredictionType.WINNER, driverIds = listOf(driverId))),
        submittedAtEpochMs = 1L,
    )

    @Test
    fun `classifica con due gare somma i punteggi`() {
        val races = listOf(race("r1", 1, "GP A"), race("r2", 2, "GP B"))
        val results = mapOf("r1" to raceResult("r1"), "r2" to raceResult("r2"))

        val predictionsByUser = mapOf(
            "u1" to listOf(winnerPrediction("r1", "VER"), winnerPrediction("r2", "VER")),
            "u2" to listOf(winnerPrediction("r1", "NOR"), winnerPrediction("r2", "LEC")),
        )

        val snapshot = LeagueStandings.build(
            rules = rules,
            memberNames = mapOf("u1" to "Alice", "u2" to "Bob"),
            races = races,
            results = results,
            predictionsByUser = predictionsByUser,
        )

        val alice = snapshot.rows.first { it.userId == "u1" }
        val bob = snapshot.rows.first { it.userId == "u2" }

        assertEquals(50.0, alice.totalPoints, 0.001)
        assertEquals(0.0, bob.totalPoints, 0.001)
        assertEquals("Alice", snapshot.rows.first().displayName)
        assertEquals(2, alice.racesPredicted)
        assertEquals(2, alice.raceWins)
    }

    @Test
    fun `le gare senza risultato non entrano nei totali`() {
        val races = listOf(race("r1", 1, "GP A"), race("r2", 2, "GP B"))
        val results = mapOf("r1" to raceResult("r1"))

        val predictionsByUser = mapOf(
            "u1" to listOf(winnerPrediction("r1", "VER"), winnerPrediction("r2", "VER")),
        )

        val snapshot = LeagueStandings.build(
            rules = rules,
            memberNames = mapOf("u1" to "Alice"),
            races = races,
            results = results,
            predictionsByUser = predictionsByUser,
        )

        assertEquals(25.0, snapshot.rows.single().totalPoints, 0.001)
        assertEquals(listOf("r1"), snapshot.completedRaceIds)
        assertEquals(setOf("r1"), snapshot.scoresByRace.keys)
    }

    @Test
    fun `le righe sono ordinate per punti totali decrescenti`() {
        val races = listOf(race("r1", 1, "GP A"), race("r2", 2, "GP B"))
        val results = mapOf("r1" to raceResult("r1"), "r2" to raceResult("r2"))

        // Alice vince una gara (25), Bob indovina il podio completo nella seconda (58)
        val podioCompleto = RacePrediction(
            raceId = "r2",
            entries = listOf(
                PredictionEntry(type = PredictionType.PODIUM, driverIds = listOf("VER", "NOR", "LEC")),
            ),
            submittedAtEpochMs = 1L,
        )
        val predictionsByUser = mapOf(
            "u1" to listOf(winnerPrediction("r1", "VER"), winnerPrediction("r2", "NOR")),
            "u2" to listOf(winnerPrediction("r1", "NOR"), podioCompleto),
        )

        val snapshot = LeagueStandings.build(
            rules = rules,
            memberNames = mapOf("u1" to "Alice", "u2" to "Bob"),
            races = races,
            results = results,
            predictionsByUser = predictionsByUser,
        )

        assertEquals("Bob", snapshot.rows.first().displayName)
        assertEquals(58.0, snapshot.rows.first().totalPoints, 0.001)
        assertEquals(25.0, snapshot.rows.last().totalPoints, 0.001)
        // la classifica e' in ordine decrescente di punti
        assertTrue(snapshot.rows.zipWithNext().all { (a, b) -> a.totalPoints >= b.totalPoints })
    }

    @Test
    fun `punteggi per gara ordinati dal più alto con i nomi`() {
        val races = listOf(race("r1", 1, "GP A"))
        val results = mapOf("r1" to raceResult("r1"))
        val predictionsByUser = mapOf(
            "u1" to listOf(winnerPrediction("r1", "VER")),
            "u2" to listOf(winnerPrediction("r1", "NOR")),
        )

        val snapshot = LeagueStandings.build(
            rules = rules,
            memberNames = mapOf("u1" to "Alice", "u2" to "Bob"),
            races = races,
            results = results,
            predictionsByUser = predictionsByUser,
        )

        val scores = snapshot.scoresByRace.getValue("r1")
        assertEquals(listOf("u1", "u2"), scores.map { it.userId })
        assertEquals(25.0, scores.first().points, 0.001)
        assertEquals(0.0, scores.last().points, 0.001)
        assertTrue(snapshot.rows.isNotEmpty())
    }
}
