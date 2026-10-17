package com.fantaformula1.app.data.sync

import com.fantaformula1.app.core.domain.RaceResultData
import com.fantaformula1.app.data.f1api.model.F1Race
import com.fantaformula1.app.data.f1api.model.F1Result
import kotlinx.serialization.Serializable

/**
 * Un risultato di gara della stagione REALE (Jolpica/Ergast) con il suo round.
 * Il round e' la chiave di unione con i risultati della lega: stessa gara =
 * stesso round, e le due copie NON vanno mai contate due volte nelle
 * statistiche (vedi [com.fantaformula1.app.core.domain.DriverSeasonStats]).
 */
@Serializable
data class RealSeasonResult(
    val round: Int,
    val result: RaceResultData,
)

/** Cache persistente dei risultati reali della stagione. */
@Serializable
data class SeasonResultsCache(
    val season: Int,
    val fetchedAtEpochMs: Long,
    val results: List<RealSeasonResult>,
)

/**
 * True se la cache e' assente o piu' vecchia di [maxAgeMs].
 * Funzione pura (testata su JVM in SeasonResultsCacheStaleTest).
 */
internal fun isStaleSeasonCache(
    cache: SeasonResultsCache?,
    nowEpochMs: Long,
    maxAgeMs: Long,
): Boolean = cache == null || nowEpochMs - cache.fetchedAtEpochMs >= maxAgeMs

/** Serve un rinfresco? Solo se forzato o se la cache e' stanca (max 6h). */
internal fun shouldRefreshSeasonCache(
    cache: SeasonResultsCache?,
    force: Boolean,
    nowEpochMs: Long,
    maxAgeMs: Long,
): Boolean = force || isStaleSeasonCache(cache, nowEpochMs, maxAgeMs)

/**
 * La cache e' utilizzabile SOLO se e' della stagione corrente: una cache di
 * un'annata diversa (es. restata sul telefono al cambio stagione) viene
 * ignorata del tutto come se non esistesse. Pura e testata su JVM.
 */
internal fun usableSeasonCache(cache: SeasonResultsCache?, season: Int): SeasonResultsCache? =
    cache?.takeIf { it.season == season }

/**
 * Fonte remota astratta della stagione reale: l'unica superficie di rete del
 * rinfresco. Esiste per rendere testabili su JVM il piano di download e la
 * gestione parziale dei fallback (l'implementazione reale e' F1ApiClient).
 */
interface SeasonResultsRemote {
    suspend fun calendar(season: Int): List<F1Race>
    suspend fun raceResults(season: Int, round: Int): List<F1Result>
}
