package com.fantaformula1.app.data.quotes

import com.fantaformula1.app.core.domain.DriverSeasonStats
import com.fantaformula1.app.core.domain.DriversCatalog
import com.fantaformula1.app.core.domain.OverUnderQuotes
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.QuoteKeys
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RaceQuote
import java.time.Instant

/**
 * Prossima gara APERTA ai pronostici della stagione corrente (round piu'
 * basso tra quelle con deadline futura e status aperto).
 *
 * La pubblicazione e' pigra perche' le statistiche cambiano gara dopo gara:
 * pubblicare in blocco tutte le gare future userebbe numeri vecchi.
 */
internal fun nextOpenRaceForQuotes(races: List<Race>, now: Instant = Instant.now()): Race? =
    races.filter { it.isPredictionOpen(now) }.minByOrNull { it.round }

/**
 * Decide SE pubblicare le quote di una gara:
 *  - la gara deve essere ancora aperta;
 *  - non devono esistere pronostici (dopo il primo il server e' immutabile);
 *  - [requireMissingQuotes] = true quando la pubblicazione e' innescata
 *    dall'apertura della gara (solo se mancano le quote);
 *    false quando e' innescata dai risultati (ripubblica per aggiornare le
 *    statistiche finche' nessuno ha ancora pronosticato).
 */
internal fun shouldPublishQuotes(
    race: Race,
    now: Instant,
    hasPredictions: Boolean,
    hasQuotes: Boolean,
    requireMissingQuotes: Boolean,
): Boolean =
    race.isPredictionOpen(now) &&
        !hasPredictions &&
        (!requireMissingQuotes || !hasQuotes)

/**
 * Insieme COMPLETO delle quote della gara, calcolato con il codice esistente
 * ([OverUnderQuotes] + [DriverSeasonStats]): due lati per il DNF, due lati per
 * i punti (stessa soglia) e tutte le coppie ordinate del testa a testa.
 * Funzione pura, testata su JVM.
 */
internal fun buildRaceQuotes(
    stats: DriverSeasonStats,
    drivers: List<String> = DriversCatalog.all.map { it.id },
): List<RaceQuote> {
    val quotes = mutableListOf<RaceQuote>()

    drivers.forEach { driverId ->
        val (quotaRetires, quotaFinishes) = OverUnderQuotes.dnfQuotas(stats.dnfRate(driverId))
        quotes += RaceQuote(
            type = PredictionType.DRIVER_DNF,
            key = QuoteKeys.dnf(driverId, true),
            threshold = null,
            quota = quotaRetires,
        )
        quotes += RaceQuote(
            type = PredictionType.DRIVER_DNF,
            key = QuoteKeys.dnf(driverId, false),
            threshold = null,
            quota = quotaFinishes,
        )

        val threshold = stats.thresholdFor(driverId)
        val (quotaOver, quotaUnder) = OverUnderQuotes.pointsOverUnderQuotas(
            overCount = stats.countOverPoints(driverId, threshold),
            n = stats.racesRun(driverId),
        )
        quotes += RaceQuote(
            type = PredictionType.DRIVER_POINTS_OVER_UNDER,
            key = QuoteKeys.points(driverId, true),
            threshold = threshold,
            quota = quotaOver,
        )
        quotes += RaceQuote(
            type = PredictionType.DRIVER_POINTS_OVER_UNDER,
            key = QuoteKeys.points(driverId, false),
            threshold = threshold,
            quota = quotaUnder,
        )
    }

    // Testa a testa: ogni scelta possibile (pilota scelto vs avversario).
    drivers.forEach { picked ->
        drivers.filter { it != picked }.forEach { opponent ->
            val factor = OverUnderQuotes.headToHeadFactor(
                stats.tierOf(picked),
                stats.tierOf(opponent),
            )
            quotes += RaceQuote(
                type = PredictionType.HEAD_TO_HEAD,
                key = QuoteKeys.headToHead(picked, opponent),
                threshold = null,
                quota = factor,
            )
        }
    }

    return quotes
}
