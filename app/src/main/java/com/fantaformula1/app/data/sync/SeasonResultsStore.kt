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
 * cache. I risultati di una gara disputata non cambiano mai, quindi la cache
 * e' buida per sempre; round futuri e senza esito si saltano. Pura e testata su JVM.
 */
internal fun refreshPlan(cache: SeasonResultsCache?, calendar: List<F1Race>): List<Int> {
    val cachedRounds = cache?.results?.map { it.round }?.toSet() ?: emptySet()
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
 */
internal suspend fun downloadMissingRounds(
    remote: SeasonResultsRemote,
    season: Int,
    rounds: List<Int>,
    timeoutMs: Long,
): List<RealSeasonResult> {
    val results = mutableListOf<RealSeasonResult>()
    rounds.forEach { round ->
        val payload = runCatching {
            withTimeoutOrNull(timeoutMs) { remote.raceResults(season, round) }
        }.getOrNull()
        if (payload.isNullOrEmpty()) return@forEach // fallito/non ancora disponibile: solo questo salta
        val data = F1Mapper.toRaceResultData(payload, emptyList(), emptyList())
        if (data.drivers.isNotEmpty()) results += RealSeasonResult(round = round, result = data)
    }
    return results
}

/**
 * Unisce cache esistente e dati appena scarichati: la cache si PRESERVA,
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
 * - `ensureFresh(), lanciato in background dal ViewModel, rinfresca a ogni
 *   apertura dei pronostici al massimo UNA volta ogni [FRESH_MS] = 6 ore;
 * - download INCREMENTALE: solo i round disputati mancanti in cache;
 * - un fallback su una richiesta NON tocca le altre: la cache si salva con
 *   tutto cio' che e' riuscito, non si butta nulla;
 * - se anche il calendario fallisce: si usa la cache, chi la chiama fa
 *   fallback sul solo prior. La quota resta congelata nel pronostico:
 *   questo store alimenta solo il momento della scrittura.
 */
class SeasonResultsStore(
    private val context: Context,
    private val remote: SeasonResultsRemote,
) {

    /** Cache attuale, null se mai scaricata o illeggibile. */
    suspend fun current(): SeasonResultsCache? {
        val prefs = context.seasonResultsDataStore.data.first()
        val payload = prefs[KEY_CACHE] ?: return null
        return runCatching {
            AppJson.decodeFromString(SeasonResultsCache.serializer(), payload)
        }.getOrNull()
    }

    /**
     * Rinfresca se e solo se la cache e' stanca (o [force]).
     * - Cache fresca: ZERO richieste (nemmeno il calendario).
     * - Cache stanca: calendario (timeout [NETWORK_TIMEOUT_MS]) e poi SOLO i
     *   round mancanti, uno per volta con timeout individuale.
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
        val calendar = runCatching {
            withTimeoutOrNull(NETWORK_TIMEOUT_MS) { remote.calendar(SEASON) }
        }.getOrNull()
            ?: return cached // rete giu' o timeout: la cache resta com'era

        if (refreshPlan(cached, calendar).isEmpty()) {
            // Tutto i round dispositati sono gia' in cache: solo il timestamp
            // avanza, cosi' la prossima apertura resta senza richieste.
            val bumped = (cached ?: SeasonResultsCache(
                season = SEASON,
                fetchedAtEpochMs = nowEpochMs,
                results = emptyList(),
            )).copy(season = SEASON, fetchedAtEpochMs = nowEpochMs)
            save(bumped)
            return bumped
        }

        val fetched = downloadMissingRounds(remote, SEASON, refreshPlan(cached, calendar), NETWORK_TIMEOUT_MS)
        val updated = mergeFetched(cached, fetched, SEASON, nowEpochMs)
        // Salva SEMPRE, anche se qualche round e' fallito: la cache non perde
        // mai cio' che ha gia' scaricato.
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
