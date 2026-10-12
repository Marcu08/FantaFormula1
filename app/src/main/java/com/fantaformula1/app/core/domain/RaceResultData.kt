package com.fantaformula1.app.core.domain

import kotlinx.serialization.Serializable

/** Esito di un singolo pilota in una gara — tutti i parametri inseriti dall'admin. */
@Serializable
data class DriverRaceResult(
    val driverId: String,
    val driverName: String = "",
    /** Posizione in griglia (1..20). */
    val grid: Int? = null,
    /** Posizione finale in classifica; null se non classificato. */
    val position: Int? = null,
    /** Posizione nello sprint (solo gare con sprint). */
    val sprintPosition: Int? = null,
    val fastestLap: Boolean = false,
    val dnf: Boolean = false,
    val dnfReason: String? = null,
    /** Punti di penalita' inflitti al pilota. */
    val penaltyPoints: Int = 0,
    val pitStops: Int = 0,
    /** Punti campionato effettivamente assegnati (serve per over/under). */
    val pointsScored: Double? = null,
)

/**
 * Risultato completo della gara: il jsonb salvato nella colonna `race_results.result`.
 * Questa classe e' la sorgente di verita' del formato.
 */
@Serializable
data class RaceResultData(
    val raceId: String = "",
    val drivers: List<DriverRaceResult> = emptyList(),
    val safetyCars: Int = 0,
    val redFlags: Int = 0,
    val laps: Int = 0,
    val weather: String? = null,
    val notes: String? = null,
) {
    fun driver(driverId: String): DriverRaceResult? =
        drivers.firstOrNull { it.driverId == driverId }

    /** Piloti classificati (hanno una posizione) ordinati per posizione. */
    fun classifiedOrder(): List<DriverRaceResult> =
        drivers.filter { it.position != null }
            .sortedBy { it.position!! }

    /**
     * Posizione di gara (1-based) usata per gli spareggi e lo sfida-a-testa.
     * I piloti non classificati vengono posizionati dopo i classificati,
     * ordinati per griglia come da consuetudine F1. Restituisce null se il
     * pilota non e' presente in gara.
     */
    fun classificationRank(driverId: String): Int? {
        if (driver(driverId) == null) return null
        val classified = classifiedOrder()
        val classifiedIdx = classified.indexOfFirst { it.driverId == driverId }
        if (classifiedIdx >= 0) return classifiedIdx + 1

        val notClassified = drivers.filter { it.position == null }
            .sortedBy { it.grid ?: Int.MAX_VALUE }
        val idx = notClassified.indexOfFirst { it.driverId == driverId }
        return if (idx >= 0) classified.size + idx + 1 else null
    }

    fun positionOf(driverId: String): Int? = driver(driverId)?.position

    fun winner(): DriverRaceResult? = classifiedOrder().firstOrNull()

    fun sprintOrder(): List<DriverRaceResult> =
        drivers.filter { it.sprintPosition != null }
            .sortedBy { it.sprintPosition!! }

    fun sprintWinner(): DriverRaceResult? = sprintOrder().firstOrNull()

    /** Pilota partito in prima posizione; in assenza di `grid`, il minimo disponibile. */
    fun poleDriver(): DriverRaceResult? =
        drivers.firstOrNull { it.grid == 1 }
            ?: drivers.filter { it.grid != null }.minByOrNull { it.grid!! }

    fun fastestLapDriver(): DriverRaceResult? =
        drivers.firstOrNull { it.fastestLap }

    /** Totale pit stop di tutti i piloti. */
    val totalPitStops: Int get() = drivers.sumOf { it.pitStops }
}
