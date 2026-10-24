package com.fantaformula1.app.ui.standings

import com.fantaformula1.app.core.domain.DriverRaceResult
import com.fantaformula1.app.core.domain.PredictionEntry
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.core.domain.RaceResultData
import com.fantaformula1.app.core.domain.Rules
import com.fantaformula1.app.core.domain.ScoringEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U8: variazione di posizione dopo l'ultima gara con risultato.
 * La funzione e' pura: riceve i dati gia' calcolati dal ViewModel.
 */
class RankDeltasTest {

    // Solo i tipi usati dagli scenari: winner e podio.
    private val rules = Rules.default().copy(
        enabledTypes = setOf(PredictionType.WINNER, PredictionType.PODIUM),
    )

    private val names = mapOf("userA" to "Alice", "userB" to "Bob")

    private fun race(id: String, round: Int) = Race(
        id = id,
        seasonId = "season1",
        leagueId = "league1",
        round = round,
        name = "Gara $round",
    )

    /** Podio VER 1º, RUS 2º, LEC 3º (identico per ogni gara degli scenari). */
    private fun podiumResult() = RaceResultData(
        drivers = listOf(
            DriverRaceResult(driverId = "VER", position = 1),
            DriverRaceResult(driverId = "RUS", position = 2),
            DriverRaceResult(driverId = "LEC", position = 3),
        ),
    )

    private fun prediction(raceId: String, vararg entries: PredictionEntry) =
        RacePrediction(raceId = raceId, submittedAtEpochMs = 0L, entries = entries.toList())

    private fun winner(driverId: String) =
        PredictionEntry(type = PredictionType.WINNER, driverIds = listOf(driverId))

    private fun podium(vararg driverIds: String) =
        PredictionEntry(type = PredictionType.PODIUM, driverIds = driverIds.toList())

    private fun deltas(
        races: List<Race>,
        results: Map<String, RaceResultData>,
        predictionsByUser: Map<String, List<RacePrediction>>,
    ): Map<String, Int> {
        // La classifica "corrente" e' quella che il ViewModel mostra a video.
        val currentRows = ScoringEngine.computeStandings(
            predictions = predictionsByUser,
            results = results,
            rules = rules,
            displayNames = names,
        )
        return computeRankDeltas(
            rules = rules,
            memberNames = names,
            races = races,
            results = results,
            predictionsByUser = predictionsByUser,
            currentRows = currentRows,
        )
    }

    @Test
    fun `scambio di posizione dopo l'ultima gara produce delta piu uno e meno uno`() {
        // Alice: zero nelle prime due, poi podio completo (58) nella terza.
        // Bob: vince le prime due (50), poi sbaglia la terza.
        val races = listOf(race("r1", 1), race("r2", 2), race("r3", 3))
        val results = races.associate { it.id to podiumResult() }
        val predictions = mapOf(
            "userA" to listOf(
                prediction("r1", winner("LEC")),
                prediction("r2", winner("LEC")),
                prediction("r3", podium("VER", "RUS", "LEC")),
            ),
            "userB" to listOf(
                prediction("r1", winner("VER")),
                prediction("r2", winner("VER")),
                prediction("r3", winner("LEC")),
            ),
        )

        val deltas = deltas(races, results, predictions)

        // Prima di r3: Bob 50, Alice 0. Dopo: Alice 58, Bob 50.
        assertEquals(mapOf("userA" to 1, "userB" to -1), deltas)
    }

    @Test
    fun `con meno di due gare concluse la mappa e' vuota`() {
        val races = listOf(race("r1", 1), race("r2", 2))
        val results = mapOf("r1" to podiumResult()) // r2 senza risultato
        val predictions = mapOf(
            "userA" to listOf(prediction("r1", winner("LEC"))),
            "userB" to listOf(prediction("r1", winner("VER"))),
        )

        assertTrue(deltas(races, results, predictions).isEmpty())
    }

    @Test
    fun `chi non cambia posizione non compare nella mappa`() {
        // Alice vince entrambe, Bob nessuna: l'ordine non si muove.
        val races = listOf(race("r1", 1), race("r2", 2))
        val results = races.associate { it.id to podiumResult() }
        val predictions = mapOf(
            "userA" to listOf(
                prediction("r1", winner("VER")),
                prediction("r2", winner("VER")),
            ),
            "userB" to listOf(
                prediction("r1", winner("LEC")),
                prediction("r2", winner("LEC")),
            ),
        )

        assertTrue(deltas(races, results, predictions).isEmpty())
    }

    @Test
    fun `l'ultima gara e' quella CON risultato anche se ce ne sono altre nel calendario`() {
        // r3 esiste ma non ha risultato: il "prima" e' calcolato su r1.
        val races = listOf(race("r1", 1), race("r2", 2), race("r3", 3))
        val results = mapOf(
            "r1" to podiumResult(),
            "r2" to podiumResult(),
            // r3 assente da results.
        )
        val predictions = mapOf(
            "userA" to listOf(
                prediction("r1", winner("LEC")),
                prediction("r2", podium("VER", "RUS", "LEC")),
            ),
            "userB" to listOf(
                prediction("r1", winner("VER")),
                prediction("r2", winner("LEC")),
            ),
        )

        // Prima di r2: Bob 25, Alice 0. Dopo r2: Alice 58, Bob 25.
        assertEquals(mapOf("userA" to 1, "userB" to -1), deltas(races, results, predictions))
    }
}
