package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.DriverRaceResult
import com.fantaformula1.app.core.domain.LeagueStandings
import com.fantaformula1.app.core.domain.PredictionEntry
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.core.domain.RaceResultData
import com.fantaformula1.app.core.domain.RaceStatus
import com.fantaformula1.app.core.domain.Rules
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo snapshot di una stagione deve usare solo le gare di quella stagione.
 * La query di lega restituisce anche risultati e pronostici storici: senza
 * filtro finirebbero nei totali della stagione corrente.
 */
class LeagueDataRestrictionTest {

    private val user = "user-1"

    private fun race(id: String, seasonId: String, round: Int) = Race(
        id = id,
        seasonId = seasonId,
        leagueId = "league-1",
        round = round,
        name = "Race $id",
        deadline = Instant.parse("2026-01-01T12:00:00Z"),
        status = RaceStatus.COMPLETED,
    )

    private fun prediction(raceId: String) = RacePrediction(
        raceId = raceId,
        entries = listOf(
            PredictionEntry(
                type = PredictionType.WINNER,
                driverIds = listOf("VER"),
            ),
        ),
    )

    private fun result(raceId: String) = RaceResultData(
        raceId = raceId,
        drivers = listOf(DriverRaceResult(driverId = "VER", position = 1)),
        safetyCars = 0,
        totalPitStops = 40,
    )

    @Test
    fun `i dati di una stagione precedente non entrano nello snapshot corrente`() {
        val oldRace = race("old-race", "season-2025", round = 1)
        val newRace = race("new-race", "season-2026", round = 1)
        val snapshot = LeagueDataSnapshot(
            races = listOf(
                LeagueRaceData(
                    raceId = oldRace.id,
                    result = result(oldRace.id),
                    predictions = mapOf(user to prediction(oldRace.id)),
                ),
                LeagueRaceData(
                    raceId = newRace.id,
                    result = result(newRace.id),
                    predictions = mapOf(user to prediction(newRace.id)),
                ),
            ),
            unreadablePredictions = 0,
            unreadableResults = 0,
        )

        val currentData = snapshot.restrictToRaceIds(
            raceIds = setOf(newRace.id),
        )
        assertEquals(listOf(newRace.id), currentData.races.map { it.raceId })

        val results = currentData.races
            .mapNotNull { item -> item.result?.let { item.raceId to it } }
            .toMap()
        val predictions = currentData.races
            .flatMap { item -> item.predictions.map { (id, value) -> id to value } }
            .groupBy({ it.first }, { it.second })

        val standings = LeagueStandings.build(
            rules = Rules.default(),
            memberNames = mapOf(user to "Mario"),
            races = listOf(newRace),
            results = results,
            predictionsByUser = predictions,
        )

        assertEquals(1, standings.rows.size)
        // Una sola vittoria da 25 punti: il risultato della stagione 2025
        // non deve contribuire al totale.
        assertEquals(25.0, standings.rows.single().totalPoints, 0.001)
        assertEquals(1, standings.rows.single().raceWins)
    }

    @Test
    fun `un ex membro con pronostici solo nella stagione precedente non entra`() {
        val oldRace = race("old-race", "season-2025", round = 1)
        val newRace = race("new-race", "season-2026", round = 1)
        val exMember = "user-ex"
        val snapshot = LeagueDataSnapshot(
            races = listOf(
                LeagueRaceData(
                    raceId = oldRace.id,
                    result = result(oldRace.id),
                    predictions = mapOf(exMember to prediction(oldRace.id)),
                ),
                LeagueRaceData(
                    raceId = newRace.id,
                    result = result(newRace.id),
                    predictions = mapOf(user to prediction(newRace.id)),
                ),
            ),
            unreadablePredictions = 0,
            unreadableResults = 0,
        )

        val currentData = snapshot.restrictToRaceIds(setOf(newRace.id))
        val results = currentData.races
            .mapNotNull { item -> item.result?.let { item.raceId to it } }
            .toMap()
        val predictions = currentData.races
            .flatMap { item -> item.predictions.map { (id, value) -> id to value } }
            .groupBy({ it.first }, { it.second })

        val standings = LeagueStandings.build(
            rules = Rules.default(),
            // L'ex membro non fa piu' parte della lega: non compare tra i nomi.
            memberNames = mapOf(user to "Mario"),
            races = listOf(newRace),
            results = results,
            predictionsByUser = predictions,
        )

        assertEquals(listOf(user), standings.rows.map { it.userId })
        assertEquals(25.0, standings.rows.single().totalPoints, 0.001)
    }
}
