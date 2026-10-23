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
 *
 * `safetyCars` e `totalPitStops` sono nullable: null = "dato non confermato"
 * (N3). Jolpica non fornisce le safety car e l'endpoint pit stop puo'
 * fallire: salvare uno zero in questi casi falserebbe i pronostici
 * over/under. I dati gia' salvati hanno sempre il numero (0 incluso), quindi
 * restano invariati. Per i risultati vecchi senza `totalPitStops` esplicito
 * si usa la somma dei pit stop dei piloti, se almeno uno e' compilato.
 */
@Serializable
data class RaceResultData(
    val raceId: String = "",
    val drivers: List<DriverRaceResult> = emptyList(),
    /** Safety car (SC+VSC sommate); null = in attesa di conferma admin. */
    val safetyCars: Int? = null,
    val redFlags: Int = 0,
    val laps: Int = 0,
    val weather: String? = null,
    val notes: String? = null,
    /** Pit stop totali della griglia; null = in attesa di conferma admin. */
    val totalPitStops: Int? = null,
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

    /**
     * Pit stop totali effettivi, usati dagli over/under: il valore esplicito
     * se presente, altrimenti la somma dei pit stop dei piloti (risultati
     * vecchi scritti prima del campo). Null se non c'e' nessuna fonte.
     */
    fun confirmedTotalPitStops(): Int? =
        totalPitStops ?: drivers.takeIf { list -> list.any { it.pitStops > 0 } }
            ?.sumOf { it.pitStops }

    /**
     * Punti campionato effettivi di un pilota, usati dagli over/under.
     *
     * Se l'admin ha inserito esplicitamente i punti (`pointsScored`) si usa
     * quello; altrimenti, per un pilota classificato, si usa la tabella F1
     * standard (cosi' anche i risultati salvati prima del campo continuano a
     * funzionare). Un pilota non classificato vale 0.
     */
    fun effectivePoints(driver: DriverRaceResult): Double {
        driver.pointsScored?.let { return it }
        val position = driver.position ?: return 0.0
        return officialPointsFor(position)
    }

    private fun officialPointsFor(position: Int): Double =
        OFFICIAL_POINTS.getOrNull(position - 1) ?: 0.0

    companion object {
        /** Punti F1 ufficiali per posizione 1..10 (poi 0). */
        val OFFICIAL_POINTS: List<Double> =
            listOf(25.0, 18.0, 15.0, 12.0, 10.0, 8.0, 6.0, 4.0, 2.0, 1.0)
    }
}
