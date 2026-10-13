package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.core.domain.RaceResultData
import com.fantaformula1.app.data.model.PredictionRowDto
import com.fantaformula1.app.data.model.RaceResultRowDto
import com.fantaformula1.app.data.supabase.SupabaseHolder
import com.fantaformula1.app.data.util.AppJson
import io.github.jan.supabase.postgrest.from

/**
 * Implementazione Supabase di [LeagueDataRepository].
 *
 * Carica in due query tutte le righe di `race_results` e `predictions` della
 * lega: con leghe piccole (pochi giocatori x ~24 gare) il volume e' trascurabile.
 */
class SupabaseLeagueDataRepository(
    private val holder: SupabaseHolder,
) : LeagueDataRepository {

    override suspend fun raceData(leagueId: String): LeagueDataSnapshot {
        val client = requireClient()

        val resultRows = client.from("race_results").select {
            filter { eq("league_id", leagueId) }
        }.decodeList<RaceResultRowDto>()

        val predictionRows = client.from("predictions").select {
            filter { eq("league_id", leagueId) }
        }.decodeList<PredictionRowDto>()

        var unreadableResults = 0
        val results = resultRows.mapNotNull { row ->
            runCatching {
                row.raceId to AppJson.decodeFromJsonElement(RaceResultData.serializer(), row.result)
            }.onFailure { unreadableResults++ }
                .getOrNull()
        }.toMap()

        var unreadablePredictions = 0
        val predictionsByRace = predictionRows.mapNotNull { row ->
            runCatching {
                // Il raceId usato per la classifica e' SEMPRE la colonna `race_id`:
                // il `raceId` dentro il contenuto JSON e' scritto dal client e non
                // e' affidabile (potrebbe puntare a una gara gia' conclusa).
                val prediction =
                    AppJson.decodeFromJsonElement(RacePrediction.serializer(), row.content)
                        .copy(raceId = row.raceId)
                row.raceId to (row.userId to prediction)
            }.onFailure { unreadablePredictions++ }
                .getOrNull()
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, pairs) -> pairs.toMap() }

        val raceIds = results.keys + predictionsByRace.keys
        val races = raceIds.map { raceId ->
            LeagueRaceData(
                raceId = raceId,
                result = results[raceId],
                predictions = predictionsByRace[raceId].orEmpty(),
            )
        }
        return LeagueDataSnapshot(
            races = races,
            unreadablePredictions = unreadablePredictions,
            unreadableResults = unreadableResults,
        )
    }

    private fun requireClient() = holder.get()
        ?: throw UserException("Configurazione Supabase mancante: apri le impostazioni e inserisci URL e chiave.")
}
