package com.fantaformula1.app.data.sync

import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RaceResultValidation
import com.fantaformula1.app.core.domain.RaceStatus
import com.fantaformula1.app.data.f1api.F1ApiClient
import com.fantaformula1.app.data.f1api.F1Mapper
import com.fantaformula1.app.data.f1api.RaceMapping
import com.fantaformula1.app.data.f1api.f1FailureMessage
import com.fantaformula1.app.data.repository.RaceRepository
import com.fantaformula1.app.data.repository.UserException
import io.ktor.client.plugins.ResponseException
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException

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
        return try {
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
        } catch (cancellation: CancellationException) {
            // Regola 6: il cancel del chiamante non va mangiato.
            throw cancellation
        } catch (failure: Exception) {
            SyncResult.Failure(syncFailureMessage(failure))
        }
    }

    /**
     * Scarica i risultati per le gare della lega gia' disputate. Solo le
     * gare con deadline superata vengono interrogate (N6): per le gare
     * future l'API non ha ancora nulla e ogni richiesta e' sprecata. Se i
     * risultati non sono ancora disponibili, la gara viene saltata senza
     * errori.
     *
     * Le gare GIA' completate rientrano solo se manca il totale pit stop:
     * in quel caso [completeMissingPitStops] completa quel solo campo,
     * senza mai rigenerare il resto del risultato.
     */
    suspend fun syncResults(leagueId: String): SyncResult {
        return try {
            val season = season()
            val races = raceRepository.races(leagueId)
                .filter { it.isDeadlinePassed() }
                .filter { race ->
                    race.status != RaceStatus.COMPLETED ||
                        raceRepository.result(race.id)?.confirmedTotalPitStops() == null
                }
            var completed = 0
            var skipped = 0
            val unknownDrivers = mutableListOf<String>()

            races.forEach { race ->
                // Gara gia' completata: completo SOLO il pit stop mancante
                // (mai rigenerare il risultato: si perderebbero meteo, note,
                // safety car e ogni altro campo dell'admin).
                if (race.status == RaceStatus.COMPLETED) {
                    if (completeMissingPitStops(season, race)) completed++
                    return@forEach
                }
                val results = f1ApiClient.raceResults(season, race.round)
                if (results.isEmpty()) {
                    skipped++
                    return@forEach
                }
                // Endpoint SECONDARI: se Jolpica non ha il dato per questo
                // round (404 — sprint non previsto o non ancora pubblicato)
                // si prosegue SENZA, come i pit stop: la griglia cade su
                // result.grid e lo sprint resta "da completare". Senza questo
                // la mancanza di UN endpoint bloccava l'intera gara.
                val qualifying = optionalOn404 {
                    f1ApiClient.qualifyingResults(season, race.round)
                }
                val sprint = if (race.isSprint) {
                    optionalOn404 { f1ApiClient.sprintResults(season, race.round) }
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
                val pitStops = try {
                    f1ApiClient.pitStops(season, race.round)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
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
        } catch (cancellation: CancellationException) {
            // Regola 6: il cancel del chiamante non va mangiato.
            throw cancellation
        } catch (failure: Exception) {
            SyncResult.Failure(syncFailureMessage(failure))
        }
    }

    /**
     * Gara gia' completata con totale pit stop non confermato: completa SOLO
     * quel totale (e le fermate per pilota, solo se l'admin non le ha mai
     * compilate a mano — tutte a 0 = mai toccate). Nessun altro campo viene
     * toccato: posizioni, safety car, meteo e note restano come li ha
     * lasciati l'admin. Ritorna true se ha aggiornato qualcosa.
     */
    private suspend fun completeMissingPitStops(season: Int, race: Race): Boolean {
        val existing = raceRepository.result(race.id) ?: return false
        // Già confermato (totale esplicito o somma delle fermate a mano).
        if (existing.confirmedTotalPitStops() != null) return false
        val stops = try {
            f1ApiClient.pitStops(season, race.round)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Nessun dato: la gara resta come e' e si riprovera' alla sync
            // successiva (l'errore rete non deve abortire le altre gare).
            return false
        }
        if (stops.isEmpty()) return false
        val byDriver = F1Mapper.pitStopsByDriver(stops)
        val total = byDriver.values.sum()
        if (total <= 0) return false
        val untouched = existing.drivers.all { it.pitStops == 0 }
        val updated = existing.copy(
            totalPitStops = total,
            drivers = if (untouched) {
                existing.drivers.map { driver ->
                    driver.copy(pitStops = byDriver[driver.driverId] ?: driver.pitStops)
                }
            } else {
                existing.drivers
            },
        )
        raceRepository.saveResult(race.id, race.leagueId, updated)
        return true
    }

    /**
     * Messaggio per [SyncResult.Failure]: un [UserException] di dominio
     * conserva il proprio testo, le risposte HTTP vengono tradotte dallo
     * status (404/429/5xx) e il resto passa com'e'.
     */
    private fun syncFailureMessage(failure: Exception): String {
        if (failure is UserException) {
            return failure.message ?: "Operazione non riuscita"
        }
        val status = (failure as? ResponseException)?.response?.status?.value
        return f1FailureMessage(status, failure.message)
    }

    /**
     * Endpoint secondario: 404 = Jolpica non ha quel dato per questo round
     * (es. la gara della lega e' marcata sprint ma il round non prevede lo
     * sprint reale) -> lista vuota e si continua. Gli altri errori (5xx,
     * rate-limit, rete) si propagano: non e' la mancanza del dato.
     */
    private suspend fun <T> optionalOn404(call: suspend () -> List<T>): List<T> =
        try {
            call()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: ResponseException) {
            if (e.response.status.value == 404) emptyList() else throw e
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
