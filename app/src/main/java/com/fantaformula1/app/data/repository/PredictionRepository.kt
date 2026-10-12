package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.RacePrediction

/**
 * Pronostici del giocatore. La RLS di Supabase garantisce che:
 *  - si leggano sempre i propri, quelli degli altri solo dopo la deadline;
 *  - si scriva/aggiorni/cancelli solo il proprio e solo entro la deadline.
 */
interface PredictionRepository {

    /** Pronostico dell'utente corrente per la gara, oppure null. */
    suspend fun myPrediction(raceId: String): RacePrediction?

    /**
     * Pronostici degli altri membri per la gara (userId -> pronostico).
     * Ritorna una mappa vuota se la deadline non e' ancora passata
     * (la RLS non restituisce comunque righe altrui).
     */
    suspend fun othersPredictions(raceId: String): Map<String, RacePrediction>

    /** Inserisce o aggiorna il pronostico dell'utente corrente. */
    suspend fun savePrediction(raceId: String, leagueId: String, prediction: RacePrediction)

    suspend fun deletePrediction(raceId: String)
}
