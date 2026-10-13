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
     * Pronostici dell'utente corrente per tutte le gare della lega
     * (raceId -> pronostico). Una sola query: serve a dashboard e calendario
     * per mostrare lo stato "pronostico salvato" senza N richieste.
     */
    suspend fun myPredictions(leagueId: String): Map<String, RacePrediction>

    /**
     * Numero TOTALE di pronostici per la gara (senza identita'): serve alla
     * dashboard per "3 su 4 hanno gia' pronosticato". Null se il server non
     * espone ancora la funzione (migrazione non applicata): la UI nasconde
     * il conteggio.
     */
    suspend fun predictionCount(raceId: String): Int?

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
