package com.fantaformula1.app.ui.standings

import com.fantaformula1.app.core.domain.ScoringEngine
import java.util.Locale

/**
 * Logica pura della presentazione della classifica, testabile su JVM.
 *
 * Tenerla fuori dalla composable evita ricerche ripetute e permette di
 * verificare con unit test iniziali, distacchi e ordinamento dell'ultima gara.
 */

/** Iniziali compatte per l'avatar testuale: "Mario Rossi" -> "MR". */
internal fun initialsFor(name: String): String {
    val words = name.trim()
        .split(Regex("\\s+"))
        .filter { word -> word.any { it.isLetter() } }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words.first()
            .filter { it.isLetter() }
            .take(2)
            .uppercase(Locale.ITALY)
        else -> {
            val first = words.first().first { it.isLetter() }
            val last = words.last().first { it.isLetter() }
            "$first$last".uppercase(Locale.ITALY)
        }
    }.ifBlank { "?" }
}

/** Distacco dal leader; il leader (o una lista vuota) vale zero. */
internal fun gapToLeader(
    rows: List<ScoringEngine.StandingsRow>,
    index: Int,
): Double {
    if (rows.isEmpty() || index !in rows.indices) return 0.0
    return (rows.first().totalPoints - rows[index].totalPoints).coerceAtLeast(0.0)
}

/** Riga della classifica dell'ultima gara: include anche chi ha fatto 0 punti. */
internal data class LastRaceStanding(
    val row: ScoringEngine.StandingsRow,
    val points: Double,
)

/**
 * Classifica dell'ultima gara conclusa: tutti i partecipanti, ordinati per
 * punti ottenuti nella gara e poi per nome. Non inventa posizioni: chi non
 * compare nella mappa degli score ha semplicemente 0 punti.
 */
internal fun lastRaceRanking(
    rows: List<ScoringEngine.StandingsRow>,
    pointsByUser: Map<String, Double>,
): List<LastRaceStanding> =
    rows.map { row -> LastRaceStanding(row, pointsByUser[row.userId] ?: 0.0) }
        .sortedWith(
            compareByDescending<LastRaceStanding> { it.points }
                .thenBy { it.row.displayName },
        )
