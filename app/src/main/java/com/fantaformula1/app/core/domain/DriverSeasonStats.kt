package com.fantaformula1.app.core.domain

import kotlin.math.round

/**
 * Statistiche REALI di stagione derivate dai risultati inseriti (race_results),
 * usate per soglie e quote dei pronostici "punti/DNF" e per le fasce del testa
 * a testa. Insieme a [DriverPointsEstimates] (che resta il FALLBACK e il PRIOR):
 * non si rimuove il prior perche' con poche gare l'empirica sfidabile.
 *
 * Puro Kotlin: nessuna dipendenza Android, testato su JVM in
 * `DriverSeasonStatsTest`.
 *
 * NOTA vitale: queste statistiche NON devono mai entrare nel ricalcolo dei
 * punteggi gia' salvati — il motore ricalcola a ogni lettura, quindi i valori
 * variabili nel tempo (una media, una soglia, una quota) vengono congelati nel
 * pronostico alla scrittura (campo `quota` su PredictionEntry). Il ricalcolo
 * "live" e' VIETATO: altererebbe retroattivamente punteggi storici.
 */
data class DriverSeasonStats(
    private val perDriver: Map<String, DriverStat>,
) {

    /**
     * Risultato etichettato con il round della stagione (null = gare speciali
     * del calendario lega senza round). Serve all'unione lega/stagione reale
     * per NON contare due volte la stessa gara: stessa gara = stesso round.
     */
    data class StampedResult(
        val round: Int?,
        val result: RaceResultData,
    )

    /** Statistiche empiriche di un pilota + il prior pre-stagione. */
    data class DriverStat(
        /** Gare con risultato che includono il pilota (assenza = non disputata). */
        val racesRun: Int,
        /** Punti campionato per gara (solo GARA: lo sprint vive in sprintPosition e non entra). */
        val pointsList: List<Double>,
        /** Numero di ritiri (campo `dnf`, la stessa definizione usata dal punteggio DNF). */
        val dnfs: Int,
        /** Stima pre-stagione usata come prior (DriverPointsEstimates). */
        val prior: Double,
    ) {
        val totalPoints: Double get() = pointsList.sum()
    }

    companion object {
        /** Forza dello shrinkage verso il prior: media = (somma + K*prior)/(n+K). */
        const val SHRINK_K = 3

        /** Prior del tasso di ritiro (un pilota ritira ~10% delle gare). */
        const val DNF_PRIOR = 0.10

        /** Soglia minima/massima dei punti over/under (coerente con la validazione). */
        const val MIN_THRESHOLD = 0.5
        const val MAX_THRESHOLD = 25.0

        val empty: DriverSeasonStats = DriverSeasonStats(emptyMap())

        /**
         * Costruzione dai risultati gia' caricati (es. LeagueDataSnapshot):
         * - ogni pilota del catalogo parte con n=0 (solo prior);
         * - i piloti storici/sostituti presenti nei risultati ma fuori catalogo
         *   vengono aggiunti (prior di default 2.0) senza eccezioni;
         * - un pilota assente da un risultato semplicemente non conta quella
         *   gara (nessuna gara "inventata").
         * I risultati sono SOLO gare di gara: lo sprint non e' un RaceResultData
         * separato e `effectivePoints` usa position/pointsScored, non sprint.
         */
        fun fromResults(results: List<RaceResultData>): DriverSeasonStats {
            class Acc {
                val points = mutableListOf<Double>()
                var dnfs = 0
            }

            val acc = HashMap<String, Acc>()
            fun accOf(id: String): Acc = acc.getOrPut(id) { Acc() }
            DriversCatalog.all.forEach { accOf(it.id) }
            results.forEach { result ->
                result.drivers.forEach { driver ->
                    val a = accOf(driver.driverId)
                    a.points += result.effectivePoints(driver)
                    if (driver.dnf) a.dnfs += 1
                }
            }
            val stats = acc.mapValues { (id, a) ->
                DriverStat(
                    racesRun = a.points.size,
                    pointsList = a.points.toList(),
                    dnfs = a.dnfs,
                    // Fuori catalogo (sostituti/legacy): prior di default 2.0.
                    prior = DriverPointsEstimates.forDriver(id),
                )
            }
            return DriverSeasonStats(stats)
        }

        /**
         * Unisce i risultati della lega e quelli della STAGIONE REALE
         * (Jolpica/Ergast, cache locale) senza contarli due volte: stessa gara
         * = stesso round. Prevale la copia della LEGA (l'admin e' l'autorita'
         * per le gare del suo calendario). I gare-round NULL (gare d'esercizi
         * senza round) vengono sempre contate.
         */
        fun fromMergedResults(
            league: List<StampedResult>,
            realSeason: List<StampedResult>,
        ): DriverSeasonStats {
            val byRound = LinkedHashMap<Int, RaceResultData>()
            val noRound = mutableListOf<RaceResultData>()
            league.forEach { stamped ->
                val round = stamped.round
                val result = stamped.result
                if (round == null) {
                    noRound += result
                } else {
                    byRound.putIfAbsent(round, result)
                }
            }
            realSeason.forEach { stamped ->
                val round = stamped.round
                val result = stamped.result
                if (round == null) {
                    noRound += result
                } else if (!byRound.containsKey(round)) {
                    byRound[round] = result
                }
            }
            // in ordine di round (cronologia) per una lista punti leggibile;
            // le gare lega senza round vanno in coda (cronologia ignota).
            val merged = byRound.entries
                .sortedBy { it.key }
                .map { it.value } + noRound
            return fromResults(merged)
        }
    }

    private fun stat(driverId: String): DriverStat {
        val key = driverId.uppercase()
        return perDriver[key] ?: DriverStat(
            racesRun = 0,
            pointsList = emptyList(),
            dnfs = 0,
            prior = DriverPointsEstimates.forDriver(key),
        )
    }

    fun racesRun(driverId: String): Int = stat(driverId).racesRun

    fun dnfs(driverId: String): Int = stat(driverId).dnfs

    fun pointsList(driverId: String): List<Double> = stat(driverId).pointsList

    /** Quante gare (>= 3) la soglia e' empirica; sotto e' "stima" (prior). */
    fun isEmpirical(driverId: String): Boolean = racesRun(driverId) >= 3

    /**
     * Media punti/gara shrinkata verso il prior:
     * (somma + K*prior)/(n + K), K = 3. Con n = 0 esattamente il prior.
     */
    fun meanPointsShrunk(driverId: String): Double {
        val s = stat(driverId)
        return (s.totalPoints + SHRINK_K * s.prior) / (s.racesRun + SHRINK_K)
    }

    /** Quante volte il pilota ha superato (strettamente) [threshold] punti. */
    fun countOverPoints(driverId: String, threshold: Double): Int =
        stat(driverId).pointsList.count { it > threshold }

    /**
     * Soglia over/under dei punti di gara (multiplo di 0.5):
     * - n >= 3: la soglia x.5 PIU' PICCOLA tale che la frequenza "punti > t" sia
     *   <= 0.5 (mediana empirica). Per chi segna raramente esce 0.5, cioe' la
     *   domanda "va a punti?".
     * - n < 3: prior arrotondato a x.5; se prior < 4 esce 0.5.
     * Tavolo massimo [MAX_THRESHOLD]: sopra non esistono soglie valide.
     */
    fun thresholdFor(driverId: String): Double {
        val s = stat(driverId)
        if (s.racesRun < 3) {
            val prior = s.prior
            return if (prior < 4.0) 0.5 else (round(prior * 2) / 2).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        }
        var t = MIN_THRESHOLD
        while (t <= MAX_THRESHOLD) {
            val over = s.pointsList.count { it > t }
            if (over <= s.racesRun / 2.0) return t
            t += 0.5
        }
        return MAX_THRESHOLD
    }

    /**
     * Frequenza di ritiro shrinkata: (dnf + 3*0.10)/(n + 3), clamp [0.03, 0.50].
     * Il clamp superiore 0.50 e' voluto: garantisce che "il pilota NON ritira"
     * non paghi mai piu' di "il pilota ritira".
     */
    fun dnfRate(driverId: String): Double {
        val s = stat(driverId)
        val rate = (s.dnfs + Companion.DNF_PRIOR * SHRINK_K) / (s.racesRun + SHRINK_K)
        return rate.coerceIn(0.03, 0.50)
    }

    /**
     * Fascia testa a testa dai DATI REALI: ALTA = i 7 con media shrinkata piu'
     * alta, MEDIA = i successivi 8, BASSA = gli ultimi (schema 7/8/7 del prior).
     * A pari media si ordina per id: tavolo deterministico.
     */
    fun tierOf(driverId: String): Int {
        val index = rankedIds.indexOf(driverId)
        if (index < 0) return 1
        return when {
            index < 7 -> 0
            index < 15 -> 1
            else -> 2
        }
    }

    private val rankedIds: List<String> by lazy {
        perDriver.entries
            .map { (id, s) ->
                val mean = (s.totalPoints + SHRINK_K * s.prior) / (s.racesRun + SHRINK_K)
                id to mean
            }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .map { it.first }
    }
}

/**
 * Congela la QUOTA del lato scelto in una voce di pronostico (0009) usando le
 * [DriverSeasonStats] del momento della scrittura:
 *  - DRIVER_DNF: quote si/no dal tasso di ritiro reale;
 *  - DRIVER_POINTS_OVER_UNDER: la soglia viene SEMPRE riallineata alla soglia
 *    empirica corrente (o alla stima se n < 3) e la quota del lato scelto
 *    viene congelata;
 *  - HEAD_TO_HEAD: quota dalle fasce reali correnti.
 * Gli altri tipi restano senza quota (null). Le statistiche NON vengono mai
 * rilette dopo il salvataggio: il motore usa la quota congelata nel jsonb.
 */
internal fun PredictionEntry.withFrozenQuota(stats: DriverSeasonStats): PredictionEntry {
    val quota: Double? = when (type) {
        PredictionType.DRIVER_DNF -> {
            val rate = stats.dnfRate(driverIds.firstOrNull() ?: return this)
            val (qSi, qNo) = OverUnderQuotes.dnfQuotas(rate)
            if (booleanValue == true) qSi else qNo
        }
        PredictionType.DRIVER_POINTS_OVER_UNDER -> {
            val driverId = driverIds.firstOrNull() ?: return this
            val threshold = stats.thresholdFor(driverId)
            val (qOver, qUnder) = OverUnderQuotes.pointsOverUnderQuotas(
                stats.countOverPoints(driverId, threshold),
                stats.racesRun(driverId),
            )
            return copy(threshold = threshold, quota = if (over == true) qOver else qUnder)
        }
        PredictionType.HEAD_TO_HEAD -> {
            val first = driverIds.getOrNull(0) ?: return this
            val second = driverIds.getOrNull(1) ?: return this
            OverUnderQuotes.headToHeadFactor(stats.tierOf(first), stats.tierOf(second))
        }
        else -> null
    }
    return quota?.let { copy(quota = it) } ?: this
}
