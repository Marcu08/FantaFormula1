package com.fantaformula1.app.core.domain

/**
 * Stima pre-stagione dei punti-campionato per gara di ogni pilota (griglia
 * 2026), usata come **soglia fissa** del pronostico
 * [PredictionType.DRIVER_POINTS_OVER_UNDER]: il giocatore sceglie solo
 * sopra/sotto, il valore non e' modificabile (richiesta: la media non deve
 * variare). I valori sono stime di forza di griglia, non statistiche
 * ufficiali; aggiornabili in una prossima versione se arriva un dato reale.
 * Piloti non in mappa (legacy) -> default prudente.
 */
object DriverPointsEstimates {

    private val byId: Map<String, Double> = mapOf(
        "NOR" to 14.0,
        "VER" to 14.0,
        "PIA" to 12.0,
        "LEC" to 11.0,
        "HAM" to 11.0,
        "RUS" to 11.0,
        "ANT" to 8.0,
        "HAD" to 7.0,
        "ALO" to 6.0,
        "SAI" to 5.0,
        "GAS" to 5.0,
        "OCO" to 4.0,
        "BEA" to 4.0,
        "ALB" to 4.0,
        "LAW" to 4.0,
        "HUL" to 3.0,
        "STR" to 3.0,
        "LIN" to 3.0,
        "BOR" to 3.0,
        "COL" to 2.0,
        "BOT" to 2.0,
        "PER" to 2.0,
    )

    /** Stima punti/gara del pilota; default per id fuori catalogo. */
    fun forDriver(driverId: String): Double =
        byId[driverId.uppercase()] ?: 2.0

    /**
     * Livello di forza del pilota: 0 = Top, 1 = Mid, 2 = Bottom. Derivato
     * dall'ordine di stima (7-8-7 piloti), usato dal testa a testa equilibrato.
     */
    fun tierOf(driverId: String): Int {
        val ranked = all.entries.sortedByDescending { it.value }.map { it.key }
        val index = ranked.indexOf(driverId.uppercase())
        if (index < 0) return 2
        return when {
            index < 7 -> 0
            index < 15 -> 1
            else -> 2
        }
    }

    val all: Map<String, Double> get() = byId
}