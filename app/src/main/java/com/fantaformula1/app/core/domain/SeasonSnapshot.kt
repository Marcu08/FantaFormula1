package com.fantaformula1.app.core.domain

import kotlinx.serialization.Serializable

/**
 * F2 — Stagioni passate: classifica finale di una stagione chiusa, congelata
 * nello snapshot `season_snapshots.standings`.
 *
 * Il formato e' stabile (le righe archivate devono restare leggibili): i
 * campi allineano quelli di [ScoringEngine.StandingsRow] piu' la posizione,
 * che viene salvata in modo esplicito per non doverla ricalcolare.
 */
@Serializable
data class SeasonStandingsRow(
    val position: Int,
    val userId: String,
    val displayName: String,
    val totalPoints: Double,
    val raceWins: Int,
    val bestRacePoints: Double,
)

/** Contenuto JSON di uno snapshot di stagione. */
@Serializable
data class SeasonStandingsSnapshot(
    val rows: List<SeasonStandingsRow>,
)

/**
 * F3 — riga della classifica calcolata -> riga archiviabile/cache.
 * La posizione e' esplicita (come nello snapshot di stagione).
 */
fun ScoringEngine.StandingsRow.toCached(position: Int): SeasonStandingsRow =
    SeasonStandingsRow(
        position = position,
        userId = userId,
        displayName = displayName,
        totalPoints = totalPoints,
        raceWins = raceWins,
        bestRacePoints = bestRacePoints,
    )

/**
 * Inverso di [toCached], per ripristinare la classifica dalla cache offline.
 * `racesPredicted` non fa parte dello snapshot (formato stabile F2) e
 * degrada a 0 nella schermata offline.
 */
fun SeasonStandingsRow.toDomainRow(): ScoringEngine.StandingsRow =
    ScoringEngine.StandingsRow(
        userId = userId,
        displayName = displayName,
        totalPoints = totalPoints,
        raceWins = raceWins,
        bestRacePoints = bestRacePoints,
        racesPredicted = 0,
    )
