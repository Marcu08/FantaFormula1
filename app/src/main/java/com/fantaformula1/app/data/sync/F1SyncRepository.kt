package com.fantaformula1.app.data.sync

import com.fantaformula1.app.core.domain.RaceResultValidation
import com.fantaformula1.app.core.domain.RaceStatus
import com.fantaformula1.app.data.f1api.F1ApiClient
import com.fantaformula1.app.data.f1api.F1Mapper
import com.fantaformula1.app.data.repository.RaceRepository
import com.fantaformula1.app.data.repository.UserException

/**
 * Sincronizza il calendario e i risultati dal mondiale reale (Jolpica/Ergast)
 * con il database Supabase della lega.
 */
class F1SyncRepository(
    private val f1ApiClient: F1ApiClient,
    private val raceRepository: RaceRepository,
) {

    /**
     * Scarica il calendario 2026 e crea le gare mancanti per la lega.
     * Le gare esistenti (stesso round) non vengono sovrascritte, cosi' l'admin
     * puo' averle modificate a mano.
     */
    suspend fun syncCalendar(leagueId: String): SyncResult {
        return runCatching {
            val calendar = f1ApiClient.seasonCalendar()
            val existingRounds = raceRepository.races(leagueId).map { it.round }.toSet()
            var created = 0
            var skipped = 0

            calendar.forEach { race ->
                val round = race.round.toIntOrNull() ?: return@forEach
                if (round in existingRounds) {
                    skipped++
                    return@forEach
                }
                // Le gare gia' disputate non creano alcunche': sarebbero
                // immediatamente chiuse ai pronostici e intaserebbero il
                // calendario (richiesta "leva le gare gia' fatte").
                if (F1Mapper.isPast(race)) {
                    skipped++
                    return@forEach
                }
                val create = F1Mapper.toRaceCreate(race)
                raceRepository.createRace(
                    leagueId = leagueId,
                    name = create.name,
                    round = create.round,
                    country = create.country,
                    isSprint = create.isSprint,
                    deadline = create.deadline?.toString(),
                )
                created++
            }
            SyncResult.Success(created = created, skipped = skipped)
        }.getOrElse { failure ->
            SyncResult.Failure(failure.message ?: "Impossibile sincronizzare il calendario")
        }
    }

    /**
     * Scarica i risultati per tutte le gare della lega non ancora completate.
     * Se i risultati non sono ancora disponibili, la gara viene saltata senza
     * errori.
     */
    suspend fun syncResults(leagueId: String): SyncResult {
        return runCatching {
            val races = raceRepository.races(leagueId)
                .filter { it.status != RaceStatus.COMPLETED }
            var completed = 0
            var skipped = 0

            races.forEach { race ->
                val results = f1ApiClient.raceResults(SEASON, race.round)
                if (results.isEmpty()) {
                    skipped++
                    return@forEach
                }
                val qualifying = f1ApiClient.qualifyingResults(SEASON, race.round)
                val sprint = if (race.isSprint) {
                    f1ApiClient.sprintResults(SEASON, race.round)
                } else {
                    emptyList()
                }
                val resultData = F1Mapper.toRaceResultData(results, qualifying, sprint)
                // Non salvare dati non conformi alle invarianti del motore di
                // punteggio: la gara resta senza esito e si riprovera' dopo.
                val errors = RaceResultValidation.validate(resultData, race.isSprint)
                if (errors.isNotEmpty()) {
                    skipped++
                    return@forEach
                }
                raceRepository.saveResult(race.id, race.leagueId, resultData)
                completed++
            }
            SyncResult.Success(completed = completed, skipped = skipped)
        }.getOrElse { failure ->
            when (failure) {
                is UserException -> SyncResult.Failure(failure.message)
                else -> SyncResult.Failure(failure.message ?: "Impossibile sincronizzare i risultati")
            }
        }
    }

    companion object {
        private const val SEASON = 2026
    }
}

sealed interface SyncResult {
    val isSuccess: Boolean

    data class Success(
        val created: Int = 0,
        val skipped: Int = 0,
        val completed: Int = 0,
    ) : SyncResult {
        override val isSuccess: Boolean = true
    }

    data class Failure(val message: String) : SyncResult {
        override val isSuccess: Boolean = false
    }
}
