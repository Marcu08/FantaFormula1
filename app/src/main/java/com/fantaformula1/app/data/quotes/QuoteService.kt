package com.fantaformula1.app.data.quotes

import com.fantaformula1.app.core.domain.DriverSeasonStats
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.data.repository.LeagueDataRepository
import com.fantaformula1.app.data.repository.LeagueRepository
import com.fantaformula1.app.data.repository.PredictionRepository
import com.fantaformula1.app.data.repository.QuotePublishOutcome
import com.fantaformula1.app.data.repository.RaceQuoteLoad
import com.fantaformula1.app.data.repository.RaceQuoteRepository
import com.fantaformula1.app.data.repository.RaceRepository
import com.fantaformula1.app.data.repository.restrictToRaceIds
import com.fantaformula1.app.data.sync.SeasonResultsStore
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * Orchestrazione della pubblicazione PIGRA delle quote:
 *  - (b) quando l'admin apre una gara aperta senza quote;
 *  - (a) dopo che i risultati sono stati salvati/sincronizzati, per la
 *    PROSSIMA gara aperta (le statistiche includono il risultato appena
 *    arrivato).
 *
 * Non pubblica mai in blocco le gare future (statistiche vecchie) e non
 * tocca quelle con pronostici gia' inviati (il server e' immutabile).
 */
class QuoteService(
    private val raceRepository: RaceRepository,
    private val leagueRepository: LeagueRepository,
    private val predictionRepository: PredictionRepository,
    private val leagueDataRepository: LeagueDataRepository,
    private val seasonResultsStore: SeasonResultsStore,
    private val quoteRepository: RaceQuoteRepository,
) {

    suspend fun loadQuotes(raceId: String): RaceQuoteLoad = quoteRepository.quotes(raceId)

    /**
     * (b) L'admin apre la gara: pubblica solo se mancano le quote e non ci
     * sono pronostici. [stats] arriva dal chiamante quando lo ha gia'
     * calcolato (schermata pronostici); altrimenti si ricalcola.
     */
    suspend fun publishForOpenedRace(
        race: Race,
        stats: DriverSeasonStats? = null,
    ): QuotePublishOutcome? {
        if (!leagueRepository.isAdmin(race.leagueId)) return null
        val load = quoteRepository.quotes(race.id)
        val hasQuotes = (load as? RaceQuoteLoad.Published)?.quotes?.isNotEmpty() == true
        val hasPredictions = hasPredictions(race.id)
        if (!shouldPublishQuotes(
                race = race,
                now = Instant.now(),
                hasPredictions = hasPredictions,
                hasQuotes = hasQuotes,
                requireMissingQuotes = true,
            )
        ) {
            return null
        }
        return quoteRepository.publish(race.id, buildRaceQuotes(stats ?: statsForLeague(race.leagueId)))
    }

    /**
     * (a) Dopo il salvataggio/sync dei risultati: ripubblica le quote della
     * prossima gara aperta (aggiornate con l'ultimo risultato) finche' non
     * esistono suoi pronostici.
     */
    suspend fun publishAfterResults(leagueId: String): QuotePublishOutcome? {
        if (!leagueRepository.isAdmin(leagueId)) return null
        val races = raceRepository.races(leagueId)
        val next = nextOpenRaceForQuotes(races) ?: return null
        if (!shouldPublishQuotes(
                race = next,
                now = Instant.now(),
                hasPredictions = hasPredictions(next.id),
                hasQuotes = false,
                requireMissingQuotes = false,
            )
        ) {
            return null
        }
        return quoteRepository.publish(next.id, buildRaceQuotes(statsForLeague(leagueId)))
    }

    /**
     * Statistiche usate per il calcolo delle quote: risultati della lega +
     * cache della stagione reale (stessa composizione della schermata
     * pronostici).
     */
    suspend fun statsForLeague(leagueId: String): DriverSeasonStats {
        val snapshot = leagueDataRepository.raceData(leagueId)
        val races = raceRepository.races(leagueId)
        val roundsById = races.associate { it.id to it.round }
        val leagueResults = snapshot.restrictToRaceIds(roundsById.keys).races.mapNotNull { data ->
            data.result?.let { result ->
                DriverSeasonStats.StampedResult(
                    round = roundsById[data.raceId],
                    result = result,
                )
            }
        }
        val cachedReal = try {
            seasonResultsStore.current()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }?.results?.map {
            DriverSeasonStats.StampedResult(round = it.round, result = it.result)
        }.orEmpty()
        return DriverSeasonStats.fromMergedResults(leagueResults, cachedReal)
    }

    /**
     * Conteggio server-side dei pronostici della gara (senza identita').
     * Se la funzione non esiste (migrazione non applicata) si assume 0: la
     * RPC resta comunque l'ultima difesa (immutabilita').
     */
    private suspend fun hasPredictions(raceId: String): Boolean =
        (predictionRepository.predictionCount(raceId) ?: 0) > 0
}
