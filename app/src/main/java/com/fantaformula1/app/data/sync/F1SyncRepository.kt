package com.fantaformula1.app.data.sync

import com.fantaformula1.app.core.domain.RaceResultValidation
import com.fantaformula1.app.core.domain.RaceStatus
import com.fantaformula1.app.data.f1api.F1ApiClient
import com.fantaformula1.app.data.f1api.F1Mapper
import com.fantaformula1.app.data.f1api.RaceMapping
import com.fantaformula1.app.data.repository.RaceRepository
import com.fantaformula1.app.data.repository.UserException
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Sincronizza il calendario e i risultati dal mondiale reale (Jolpica/Ergast)
 * con il database Supabase della lega.
 */
class F1SyncRepository(
    private val f1ApiClient: F1ApiClient,
    private val raceRepository: RaceRepository,
) {

    /**
     * Scarica il calendario della stagione corrente e crea le gare mancanti
     * per la lega. Le gare esistenti (stesso round) non vengono sovrascritte,
     * cosi' l'admin puo' averle modificate a mano.
     */
    suspend fun syncCalendar(leagueId: String): SyncResult {
        return runCatching {
            val calendar = f1ApiClient.seasonCalendar(season())
            val existingRounds = raceRepository.races(leagueId).map { it.round }.toSet()
            var created = 0
            var skipped = 0
            var pastSkipped = 0

            calendar.forEach { race ->
                val round = race.round.toIntOrNull() ?: return@forEach
                if (round in existingRounds) {
                    skipped++
                    return@forEach
                }
                // Le gare gia' disputate non creano alcunche': sarebbero
                // immediatamente chiuse ai pronostici e intaserebbero il
                // calendario (richiesta "leva le gare gia' fatte"). Il conto
                // e' separato per comunicarlo all'utente (N6).
                if (F1Mapper.isPast(race)) {
                    pastSkipped++
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
            SyncResult.Success(created = created, skipped = skipped, pastSkipped = pastSkipped)
        }.getOrElse { failure ->
            SyncResult.Failure(failure.message ?: "Impossibile sincronizzare il calendario")
        }
    }

    /**
     * Scarica i risultati per le gare della lega gia' disputate e non ancora
     * completate. Solo le gare con deadline superata vengono interrogate
     * (N6): per le gare future l'API non ha ancora nulla e ogni richiesta e'
     * sprecata. Se i risultati non sono ancora disponibili, la gara viene
     * saltata senza errori.
     */
    suspend fun syncResults(leagueId: String): SyncResult {
        return runCatching {
            val season = season()
            val races = raceRepository.races(leagueId)
                .filter { it.status != RaceStatus.COMPLETED && it.isDeadlinePassed() }
            var completed = 0
            var skipped = 0
            val unknownDrivers = mutableListOf<String>()

            races.forEach { race ->
                val results = f1ApiClient.raceResults(season, race.round)
                if (results.isEmpty()) {
                    skipped++
                    return@forEach
                }
                val qualifying = f1ApiClient.qualifyingResults(season, race.round)
                val sprint = if (race.isSprint) {
                    f1ApiClient.sprintResults(season, race.round)
                } else {
                    emptyList()
                }
                val mapping = F1Mapper.toRaceResultData(results, qualifying, sprint)
                // Pilota fuori catalogo (N2): il risultato NON viene salvato e
                // la gara resta in attesa, con un errore esplicito per l'admin.
                if (mapping is RaceMapping.UnknownDriver) {
                    unknownDrivers += "Pilota sconosciuto: ${mapping.driverId} (${race.name})"
                    skipped++
                    return@forEach
                }
                val resultData = (mapping as RaceMapping.Success).data

                // Dati di gara reali dai pit stops: senza questo, le previsioni
                // sui pit stop giocherebbero su valori sempre a 0. Un fallimento
                // dell'endpoint non blocca la sincronizzazione della gara, ma il
                // totale resta null ("da confermare", N3).
                val pitStops = runCatching {
                    f1ApiClient.pitStops(season, race.round)
                }.getOrNull()
                val pitStopsByDriver = pitStops?.let { F1Mapper.pitStopsByDriver(it) }.orEmpty()
                val totalLaps = F1Mapper.raceLaps(results)

                val enriched = resultData.copy(
                    drivers = resultData.drivers.map { driver ->
                        driver.copy(pitStops = pitStopsByDriver[driver.driverId] ?: driver.pitStops)
                    },
                    laps = totalLaps ?: resultData.laps,
                    // Totale noto solo se l'endpoint ha risposto davvero.
                    totalPitStops = pitStops?.let { stops ->
                        F1Mapper.pitStopsByDriver(stops).values.sum()
                    },
                )

                // Non salvare dati non conformi alle invarianti del motore di
                // punteggio: la gara resta senza esito e si riprovera' dopo.
                val errors = RaceResultValidation.validate(enriched, race.isSprint)
                if (errors.isNotEmpty()) {
                    skipped++
                    return@forEach
                }
                raceRepository.saveResult(race.id, race.leagueId, enriched)
                completed++
            }
            // Nessun risultato salvato e almeno un pilota sconosciuto:
            // l'errore va all'admin, non un successo silenzioso.
            if (completed == 0 && unknownDrivers.isNotEmpty()) {
                SyncResult.Failure(unknownDrivers.first())
            } else {
                SyncResult.Success(completed = completed, skipped = skipped)
            }
        }.getOrElse { failure ->
            when (failure) {
                is UserException -> SyncResult.Failure(failure.message)
                else -> SyncResult.Failure(failure.message ?: "Impossibile sincronizzare i risultati")
            }
        }
    }

    /**
     * Stagione di riferimento: l'anno corrente (le leghe seguono il
     * calendario della stagione in corso, N6). Nessun valore fisso nel codice.
     */
    private fun season(): Int = LocalDate.now(ZoneOffset.UTC).year
}

sealed interface SyncResult {
    val isSuccess: Boolean

    data class Success(
        val created: Int = 0,
        val skipped: Int = 0,
        val completed: Int = 0,
        /** Gare del calendario saltate perche' gia' disputate (N6). */
        val pastSkipped: Int = 0,
    ) : SyncResult {
        override val isSuccess: Boolean = true
    }

    data class Failure(val message: String) : SyncResult {
        override val isSuccess: Boolean = false
    }
}
