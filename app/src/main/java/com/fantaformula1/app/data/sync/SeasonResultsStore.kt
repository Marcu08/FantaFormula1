package com.fantaformula1.app.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fantaformula1.app.data.f1api.F1ApiClient
import com.fantaformula1.app.data.f1api.F1Mapper
import com.fantaformula1.app.data.util.AppJson
import kotlinx.coroutines.flow.first
import kotlinx.serialization.serializer

private val Context.seasonResultsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fantaformula1_season_results",
)

/**
 * Statistiche della stagione REALE: i risultati di TUTTI i round gia'
 * disputati, scaricati da Jolpica/Ergast con lo stesso mapping di [F1Mapper]
 * usato dalla sincronizzazione (solo GARA: la qualifica e lo sprint non
 * entrano in una statistica punti/DNF).
 *
 * Motivazione: una lega creata a stagione in corso parte con n=0 (solo prior)
 * se guarda solo i risultati inseriti nella lega - i round PRECEDENTI alla
 * creazione della lega non esistono nel DB della lega perche' la sync NON
 * crea gare passate. Questi dati riempiono quel buco.
 *
 * Cache locale (DataStore):
 * - rinfrescata a ogni apertura dei pronostici con `ensureFresh()`;
 * - al massimo UNA volta ogni [FRESH_MS] = 6 ore (la stagione non cambia
 *   cosi' in fretta e l'API Jolpica ha rate-limit);
 * - se la rete fallisce: si usa la cache; se manca anche quella: la chiamera'
 *   riceve null e il chiamante fa fallback sul solo prior (comportamento
 *   pre-funzione). La quota resta congelata nel pronostico: questo store
 *   alimenta solo il momento della scrittura.
 */
class SeasonResultsStore(
    private val context: Context,
    private val f1ApiClient: F1ApiClient,
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
     * Rinfresca la cache se e solo se stanca (o [force]); in caso di fallimento
     * di rete restituisce comunque l'ultimo valore in cache (o null).
     */
    suspend fun ensureFresh(force: Boolean = false, nowEpochMs: Long = System.currentTimeMillis()): SeasonResultsCache? {
        val cached = current()
        if (!force && !isStaleSeasonCache(cached, nowEpochMs, FRESH_MS)) {
            return cached
        }
        val fresh = runCatching { fetch(SEASON, nowEpochMs) }.getOrNull()
        return if (fresh != null) {
            save(fresh)
            fresh
        } else {
            cached
        }
    }

    private suspend fun save(cache: SeasonResultsCache) {
        context.seasonResultsDataStore.edit { prefs ->
            prefs[KEY_CACHE] = AppJson.encodeToString(SeasonResultsCache.serializer(), cache)
        }
    }

    /**
     * Scarica calendario + risultati di ogni round pasato. Una gara senza
     * risultati (non ancora disputata o dato mancante) viene saltata senza
     * errori: con 24 round e il throttle del client la lentezza e' gestita.
     */
    private suspend fun fetch(season: Int, nowEpochMs: Long): SeasonResultsCache = runCatching {
        val calendar = f1ApiClient.seasonCalendar(season)
        val results = mutableListOf<RealSeasonResult>()
        calendar.forEach { race ->
            val round = race.round.toIntOrNull() ?: return@forEach
            if (!F1Mapper.isPast(race)) return@forEach
            val raceResults = f1ApiClient.raceResults(season, round)
            if (raceResults.isEmpty()) return@forEach
            // SOLO il risultato di GARA: niente qualifica (la griglia non
            // serve a una statistica di punti/DNF) e niente sprint.
            val data = F1Mapper.toRaceResultData(raceResults, emptyList(), emptyList())
            if (data.drivers.isEmpty()) return@forEach
            results += RealSeasonResult(round = round, result = data)
        }
        SeasonResultsCache(
            season = season,
            fetchedAtEpochMs = nowEpochMs,
            results = results.sortedBy { it.round },
        )
    }.getOrThrow()

    companion object {
        /** Eta' massima della cache: al massimo un rinfresco ogni 6 ore. */
        const val FRESH_MS: Long = 6 * 60 * 60 * 1000L

        /** Stagione del mondiale reale (allineata a F1SyncRepository). */
        const val SEASON = 2026

        private val KEY_CACHE = stringPreferencesKey("season_results_cache")
    }
}
