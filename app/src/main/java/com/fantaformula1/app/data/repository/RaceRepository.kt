package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RaceResultData
import com.fantaformula1.app.core.domain.RaceStatus

/**
 * Calendario gare ed esiti. La scrittura (gare, risultati) e' riservata
 * all'amministratore della lega: l'authorization vera e' nella RLS di Supabase.
 */
interface RaceRepository {

    /** Gare della lega ordinate per round. */
    suspend fun races(leagueId: String): List<Race>

    suspend fun race(raceId: String): Race?

    /**
     * Crea una gara. Se la lega non ha ancora una stagione attiva ne crea una
     * con nome = anno corrente (serve solo a soddisfare il vincolo di schema).
     */
    suspend fun createRace(
        leagueId: String,
        name: String,
        round: Int,
        country: String?,
        isSprint: Boolean,
        deadline: String?,
    ): Race

    suspend fun updateRace(
        raceId: String,
        name: String,
        round: Int,
        country: String?,
        isSprint: Boolean,
        deadline: String?,
    )

    suspend fun updateStatus(raceId: String, status: RaceStatus)

    suspend fun deleteRace(raceId: String)

    /** Esito salvato per la gara, oppure null se non ancora inserito. */
    suspend fun result(raceId: String): RaceResultData?

    /**
     * Salva (inserisci o aggiorna) l'esito della gara e porta lo stato a
     * [RaceStatus.COMPLETED].
     */
    suspend fun saveResult(raceId: String, leagueId: String, result: RaceResultData)
}
