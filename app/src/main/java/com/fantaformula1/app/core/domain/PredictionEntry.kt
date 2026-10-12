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
 *  - HEAD_TO_HEAD                                              -> [driverIds] (2)
 *  - DRIVER_POINTS_OVER_UNDER                                 -> [driverIds] (1) + [threshold] + [over]
 *  - SAFETY_CAR/PIT_STOPS_OVER_UNDER                          -> [threshold] + [over]
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
