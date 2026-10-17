package com.fantaformula1.app.data.sync

import com.fantaformula1.app.core.domain.RaceResultData
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
 * Sema fornitura: la cache e' STANCA se assente o piu' vecchia di [maxAgeMs].
 * Funzione pura (testata su JVM in SeasonResultsCacheStaleTest).
 */
internal fun isStaleSeasonCache(
    cache: SeasonResultsCache?,
    nowEpochMs: Long,
    maxAgeMs: Long,
): Boolean = cache == null || nowEpochMs - cache.fetchedAtEpochMs >= maxAgeMs
