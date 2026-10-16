package com.fantaformula1.app.core.domain

import kotlinx.serialization.Serializable

/**
 * Singola voce di pronostico.
 *
 * Non tutti i campi sono usati da ogni [PredictionType]:
 *  - WINNER / POLE / FASTEST_LAP / SPRINT_WINNER / DRIVER_DNF -> [driverIds] (1)
 *  - PODIUM                                                    -> [driverIds] (3, in ordine)
 *  - DRIVER_EXACT_POSITION                                     -> [driverIds] (1) + [position]
 *  - DRIVER_POSITION_RANGE                                     -> [driverIds] (1) + [rangeMin]/[rangeMax]
 *  - HEAD_TO_HEAD                                              -> [driverIds] (2) + [quota]
 *  - DRIVER_POINTS_OVER_UNDER                                 -> [driverIds] (1) + [threshold] + [over] + [quota]
 *  - SAFETY_CAR/PIT_STOPS_OVER_UNDER                          -> [rangeMin]/[rangeMax] (fasce)
 *
 * [quota] (2026-10): payout CONGELATO alla scrittura per DNF / punti over-under
 * / testa a testa. Il motore ricalcola i punteggi a ogni lettura: se il payout
 * dipendesse da statistiche live (medie, tasso di ritiro, fasce di forza),
 * cambiare i dati cambierebbe retroattivamente i punteggi gia' assegnati.
 * La quota si congela invece nel jsonb: scrivere "quota = 2.0" oggi resta
 * "quota = 2.0" domani, qualunque cosa accada alle statistiche. Le voci
 * vecchie (quota null) restano compatibili e vengono punteggiate come prima.
 */
@Serializable
data class PredictionEntry(
    val type: PredictionType,
    val driverIds: List<String> = emptyList(),
    val position: Int? = null,
    val rangeMin: Int? = null,
    val rangeMax: Int? = null,
    /** Soglia numerica per gli "over/under". */
    val threshold: Double? = null,
    /** true = "piu' di", false = "meno di (o pari a)". */
    val over: Boolean? = null,
    /** Valore booleano atteso (usato da DRIVER_DNF). */
    val booleanValue: Boolean? = null,
    /** Payout congelato (multiplicatore dei punti base) per i tipi a quota. */
    val quota: Double? = null,
)

/**
 * Pronostico completo di un utente per una gara — il jsonb di `predictions.content`.
 */
@Serializable
data class RacePrediction(
    val raceId: String = "",
    val entries: List<PredictionEntry> = emptyList(),
    val submittedAtEpochMs: Long? = null,
)
