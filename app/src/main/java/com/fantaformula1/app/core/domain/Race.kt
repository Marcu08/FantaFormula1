package com.fantaformula1.app.core.domain

import java.time.Instant

/** Stato di una gara nel calendario. */
enum class RaceStatus(val stored: String) {
    SCHEDULED("scheduled"),
    OPEN("open"),
    CLOSED("closed"),
    COMPLETED("completed"),
    ;

    companion object {
        fun fromStored(value: String?): RaceStatus =
            entries.firstOrNull { it.stored == value } ?: SCHEDULED
    }
}

/**
 * Gara del calendario. Corrisponde alla tabella `races`.
 * `deadline` e' il momento di chiusura pronostici (NULL = sempre aperta).
 */
data class Race(
    val id: String,
    val seasonId: String,
    val leagueId: String,
    val round: Int,
    val name: String,
    val country: String? = null,
    val isSprint: Boolean = false,
    val deadline: Instant? = null,
    val status: RaceStatus = RaceStatus.SCHEDULED,
) {
    /** True se la deadline e' nota ed e' gia' superata. */
    fun isDeadlinePassed(now: Instant = Instant.now()): Boolean =
        deadline != null && !now.isBefore(deadline)

    /**
     * True se i pronostici possono ancora essere inviati/modificati.
     *
     * B10: oltre alla deadline considera lo status della gara: una gara
     * chiusa o completata non accetta pronostici, anche se la deadline non
     * e' stata ancora superata.
     */
    fun isPredictionOpen(now: Instant = Instant.now()): Boolean =
        status != RaceStatus.CLOSED && status != RaceStatus.COMPLETED && !isDeadlinePassed(now)

    /**
     * Stato effettivo della gara, derivato da `status`, deadline e presenza
     * di un risultato (B20). Usato ovunque in UI per mostrare uno stato
     * coerente tra calendario, pronostici e classifica.
     */
    fun effectiveStatus(
        now: Instant = Instant.now(),
        hasResult: Boolean = false,
    ): RaceStatus = when {
        status == RaceStatus.COMPLETED || hasResult -> RaceStatus.COMPLETED
        status == RaceStatus.CLOSED -> RaceStatus.CLOSED
        status == RaceStatus.OPEN -> RaceStatus.OPEN
        isDeadlinePassed(now) -> RaceStatus.CLOSED
        else -> RaceStatus.SCHEDULED
    }
}
