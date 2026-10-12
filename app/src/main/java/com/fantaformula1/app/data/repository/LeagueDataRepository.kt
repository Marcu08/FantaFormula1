package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.core.domain.RaceResultData

/** Riga aggregata di esito + pronostici per la classifica di una lega. */
data class LeagueRaceData(
    val raceId: String,
    val result: RaceResultData?,
    /** userId -> pronostico (solo pronostici leggibili dalla RLS). */
    val predictions: Map<String, RacePrediction>,
)

/**
 * Dati grezzi di una lega necessari a ricalcolare punteggi e classifica
 * con lo [com.fantaformula1.app.core.domain.ScoringEngine].
 */
interface LeagueDataRepository {

    /**
     * Un record per gara della lega: esito (se inserito) e pronostici leggibili.
     * La RLS espone i pronostici degli altri solo dopo la deadline: le gare
     * ancora aperte conterranno quindi solo il pronostico del giocatore.
     */
    suspend fun raceData(leagueId: String): List<LeagueRaceData>
}
