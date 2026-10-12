package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.core.domain.RaceResultData
import com.fantaformula1.app.data.model.PredictionRowDto
import com.fantaformula1.app.data.model.RaceResultRowDto
import com.fantaformula1.app.data.supabase.SupabaseHolder
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.Json

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Implementazione Supabase di [LeagueDataRepository].
 *
 * Carica in due query tutte le righe di `race_results` e `predictions` della
 * lega: con leghe piccole (pochi giocatori x ~24 gare) il volume e' trascurabile.
 */
class SupabaseLeagueDataRepository(
    private val holder: SupabaseHolder,
) : LeagueDataRepository {

    override suspend fun raceData(leagueId: String): List<LeagueRaceData> {
        val client = requireClient()

        val resultRows = client.from("race_results").select {
            filter { eq("league_id", leagueId) }
        }.decodeList<RaceResultRowDto>()

        val predictionRows = client.from("predictions").select {
            filter { eq("league_id", leagueId) }
        }.decodeList<PredictionRowDto>()

        val results = resultRows.mapNotNull { row ->
            runCatching {
                row.raceId to json.decodeFromJsonElement(RaceResultData.serializer(), row.result)
            }.getOrNull()
        }.toMap()

        val predictionsByRace = predictionRows.mapNotNull { row ->
            runCatching {
                row.raceId to (row.userId to
                    json.decodeFromJsonElement(RacePrediction.serializer(), row.content))
            }.getOrNull()
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, pairs) -> pairs.toMap() }

        val raceIds = results.keys + predictionsByRace.keys
        return raceIds.map { raceId ->
            LeagueRaceData(
                raceId = raceId,
                result = results[raceId],
                predictions = predictionsByRace[raceId].orEmpty(),
            )
        }
    }

    private fun requireClient() = holder.get()
        ?: throw UserException("Configurazione Supabase mancante: apri le impostazioni e inserisci URL e chiave.")
}
