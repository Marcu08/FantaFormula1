package com.fantaformula1.app.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fantaformula1.app.data.f1api.F1ApiClient
import com.fantaformula1.app.data.f1api.F1Mapper
import com.fantaformula1.app.data.f1api.model.F1Race
import com.fantaformula1.app.data.f1api.model.F1Result
import com.fantaformula1.app.data.util.AppJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private val Context.seasonResultsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fantaformula1_season_results",
)

/** Adapter dell'implementazione reale: delega al client con throttle/retry. */
class F1ApiSeasonResultsRemote(private val api: F1ApiClient) : SeasonResultsRemote {
    override suspend fun calendar(season: Int): List<F1Race> = api.seasonCalendar(season)
    override suspend fun raceResults(season: Int, round: Int): List<F1Result> =
        api.raceResults(season, round)
}

/**
 * Piano di download INCREMENTALE: solo i round gia' DISPUTATI che mancano in
 * cache. I risultati di una gara disputata non cambiano mai, quindi round futuri
 * e senza esito si saltano. Una cache di un'ALTRA stagione viene ignorata
 * del tutto ([usableSeasonCache]). Pura e testata su JVM.
 */
internal fun refreshPlan(cache: SeasonResultsCache?, calendar: List<F1Race>, season: Int): List<Int> {
    val cachedRounds = usableSeasonCache(cache, season)
        ?.results?.map { it.round }?.toSet() ?: emptySet()
    return calendar
        .filter { F1Mapper.isPast(it) }
        .mapNotNull { it.round.toIntOrNull() }
        .filter { it !in cachedRounds }
        .sorted()
}

/**
 * Scarica SOLO i round del piano, UNO per volta: il fallimento di un round
 * NON tocca nessun altro (quelli riusciti restano nel risultato, non tutto
 * viene buttato). Ogni richiesta ha un timeout proprio [timeoutMs].
 * Dopo OGNI round riuscito viene invocata [onProgress]: il chiamante lo usa
 * per salvare la cache in tempo reale — un'annullamento della coroutine
 * (l'utente esce dalla schermata) non perde i round gia' scaricati.
 * L'annullamento delle coroutine NON viene mai catturato: si propaga subito.
 */
internal suspend fun downloadMissingRounds(
    remote: SeasonResultsRemote,
    season: Int,
    rounds: List<Int>,
    timeoutMs: Long,
    onProgress: suspend (RealSeasonResult) -> Unit = {},
): List<RealSeasonResult> {
    val results = mutableListOf<RealSeasonResult>()
    rounds.forEach { round ->
        val payload = try {
            withTimeoutOrNull(timeoutMs) { remote.raceResults(season, round) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e // uscita dallo schermo: NON inghiottire l'annullamento
        } catch (e: Exception) {
            null // errore di rete del singolo round: solo questo salta
        }
        if (payload.isNullOrEmpty()) return@forEach
        val data = F1Mapper.toRaceResultData(payload, emptyList(), emptyList())
        if (data.drivers.isNotEmpty()) {
            val entry = RealSeasonResult(round = round, result = data)
            results += entry
            onProgress(entry)
        }
    }
    return results
}

/**
 * Unisce cache esistente e dati appena scaricati: la cache si PRESERVA,
 * i nuovi round si aggiungono (round gia' presente: vince il vecchio dato,
 * i risultati non cambiano). Ordina per round.
 */
internal fun mergeFetched(
    current: SeasonResultsCache?,
    fetched: List<RealSeasonResult>,
    season: Int,
    nowEpochMs: Long,
): SeasonResultsCache {
    val byRound = LinkedHashMap<Int, RealSeasonResult>()
    current?.results?.forEach { byRound[it.round] = it }
    fetched.forEach { byRound[it.round] = it }
    return SeasonResultsCache(
        season = season,
        fetchedAtEpochMs = nowEpochMs,
        results = byRound.values.sortedBy { it.round },
    )
}

/**
 * Statistiche della stagione REALE: i risultati di TUTTI i round gia'
 * disputati, scaricati da Jolpica/Ergast con lo stesso mapping di [F1Mapper]
 * usato dalla sincronizzazione (solo GARA: la qualifica e lo sprint non
 * entrano in una statistica punti/DNF).
 *
 * Motivazione: una lega creata a stagione in corso parte con n=0 (solo prior)
 * se guarda solo i risultati inseriti nella lega — i round PRECEDENTI alla
 * creazione della lega non esistono nel DB della lega perche' la sync NON
 * crea gare passate. Questi dati riempiono quel buco.
 *
 * Cache locale (DataStore):
 * - `current()` non tocca la rete: il chiamante mostra SUBITO le statistiche;
 * - le cache di stagioni diverse dalla corrente vengono ignorate;
 * - `ensureFresh()`, lanciato in background dal ViewModel, rinfresca a ogni
 *   apertura dei pronostici al massimo UNA volta ogni [FRESH_MS] = 6 ore;
 * - download INCREMENTALE: solo i round disputati mancanti in cache, e la
 *   cache viene salvata DOPO OGNI round scaricato (l'annullamento della
 *   coroutine non perde niente);
 * - un errore su una richiesta NON tocca le altre: non si butta nulla;
 * - se anche il calendario fallisce: si usa la cache, chi la chiama fa
 *   fallback sul solo prior. La quota resta congelata nel pronostico:
 *   questo store alimenta solo il momento della scrittura.
 */
class SeasonResultsStore(
    private val context: Context,
    private val remote: SeasonResultsRemote,
) {

    /**
     * Cache attuale, null se mai scaricata, illeggibile o DI UN'ALTRA
     * stagione (cambio anno: si riscarica tutto da zero).
     */
    suspend fun current(): SeasonResultsCache? {
        val prefs = context.seasonResultsDataStore.data.first()
        val payload = prefs[KEY_CACHE] ?: return null
        val decoded = runCatching {
            AppJson.decodeFromString(SeasonResultsCache.serializer(), payload)
        }.getOrNull()
        return usableSeasonCache(decoded, SEASON)
    }

    /**
     * Rinfresca se e solo se la cache e' stanca (o [force]).
     * - Cache fresca: ZERO richieste (nemmeno il calendario).
     * - Cache stanca o di stagione diversa: calendario (timeout dedicato) e
     *   poi SOLO i round mancanti, uno per volta con timeout individuale e
     *   salvataggio della cache DOPO OGNI round.
     * - Fallimenti: si RESTITUISCE la cache preservata (o null se mai
     *   scaricata), mai errori verso il chiamante.
     */
    suspend fun ensureFresh(
        force: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): SeasonResultsCache? {
        val cached = current()
        if (!shouldRefreshSeasonCache(cached, force, nowEpochMs, FRESH_MS)) {
            return cached // zero richieste
        }
        val calendar = try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) { remote.calendar(SEASON) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return cached // rete giu' o timeout: la cache resta com'era

        val plan = refreshPlan(cached, calendar, SEASON)
        if (plan.isEmpty()) {
            // Tutti i round disputati sono gia' in cache: solo il timestamp
            // avanza, cosi' la prossima apertura resta senza richieste.
            val bumped = (cached ?: SeasonResultsCache(
                season = SEASON,
                fetchedAtEpochMs = nowEpochMs,
                results = emptyList(),
            )).copy(season = SEASON, fetchedAtEpochMs = nowEpochMs)
            save(bumped)
            return bumped
        }

        // Persistenza INCREMENTALE: dopo ogni round la cache cresce. Il
        // timestamp dei salvataggi intermedi resta quello vecchio (0 se mai
        // scaricati) cosi', se l'utente abbandona a meta', i round mancanti
        // vengono ritentati presto; il salvataggio finale usa [nowEpochMs].
        val partialTimestamp = cached?.fetchedAtEpochMs ?: nowEpochMs
        var accumulated = cached
        val fetched = downloadMissingRounds(remote, SEASON, plan, NETWORK_TIMEOUT_MS) { result ->
            accumulated = mergeFetched(accumulated, listOf(result), SEASON, partialTimestamp)
            save(accumulated)
        }
        if (fetched.size < plan.size) {
            return accumulated // parziale: gia' salvato round per round sopra
        }
        // Completato: il timestamp avanza (prossima apertura senza richieste).
        val updated = (accumulated ?: SeasonResultsCache(
            season = SEASON,
            fetchedAtEpochMs = nowEpochMs,
            results = emptyList(),
        )).copy(season = SEASON, fetchedAtEpochMs = nowEpochMs)
        save(updated)
        return updated
    }

    private suspend fun save(cache: SeasonResultsCache) {
        context.seasonResultsDataStore.edit { prefs ->
            prefs[KEY_CACHE] = AppJson.encodeToString(SeasonResultsCache.serializer(), cache)
        }
    }

    companion object {
        /** Eta' massima della cache: al massimo un rinfresco ogni 6 ore. */
        const val FRESH_MS: Long = 6 * 60 * 60 * 1000L

        /** Timeout su ogni singola richiesta di rete (calendario e round). */
        const val NETWORK_TIMEOUT_MS: Long = 10_000L

        /** Stagione del mondiale reale (allineata a F1SyncRepository). */
        const val SEASON = 2026

        private val KEY_CACHE = stringPreferencesKey("season_results_cache")
    }
}
