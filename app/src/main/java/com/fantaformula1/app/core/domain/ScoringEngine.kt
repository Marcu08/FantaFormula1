package com.fantaformula1.app.core.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Motore di punteggio — codice puro Kotlin, nessuna dipendenza Android,
 * interamente coperto da unit test JVM (`ScoringEngineTest`).
 *
 * I punteggi vengono ricalcolati a ogni lettura: con leghe piccole (pochi
 * giocatori x ~24 gare) il costo e' trascurabile e non serve nessun server.
 */
object ScoringEngine {

    /**
     * Punteggio di una singola voce di pronostico.
     * Restituisce sempre un valore >= 0 (0 = non indovinato / tipo non attivo).
     * Il clamp protegge anche da regole jsonb malformate (punti negativi non
     * ammessi nemmeno come dato in ingresso: Rules.validate li vieta, ma il
     * jsonb e' scritto dall'admin e resta fuori dal controllo del motore).
     */
    fun score(
        entry: PredictionEntry,
        result: RaceResultData,
        rules: Rules,
    ): Double {
        // Tipo disattivato dalle regole della lega: zero punti.
        if (entry.type !in rules.enabledTypes) return 0.0
        return baseScore(entry, result, rules).coerceAtLeast(0.0).roundTo2()
    }

    /** Somma dei punteggi di tutte le voci di un pronostico per una gara. */
    fun score(
        prediction: RacePrediction,
        result: RaceResultData,
        rules: Rules,
    ): Double =
        prediction.entries.sumOf { score(it, result, rules) }.roundTo2()

    // ---------------------------------------------------------------------
    // Punteggio per tipo
    // ---------------------------------------------------------------------

    private fun baseScore(
        entry: PredictionEntry,
        result: RaceResultData,
        rules: Rules,
    ): Double = when (entry.type) {

        PredictionType.WINNER -> {
            val winner = result.winner()
            if (winner != null && entry.driverIds.firstOrNull() == winner.driverId) {
                rules.pointsFor(PredictionType.WINNER)
            } else 0.0
        }

        PredictionType.PODIUM -> {
            val order = result.classifiedOrder()
            if (order.size < 3) {
                0.0
            } else {
                val slotScore = entry.driverIds.take(3).mapIndexedNotNull { index, driverId ->
                    val slotPoints = rules.podiumPoints.getOrNull(index) ?: return@mapIndexedNotNull null
                    if (order[index].driverId == driverId) {
                        // Sconto applicato solo alla posizione occupata dal pilota
                        // penalizzato o ritirato, non all'intero podio.
                        adjust(driverId, slotPoints, result, rules)
                    } else null
                }.sum()
                if (slotScore > 0.0) {
                    slotScore
                } else if (rules.podiumNoOrderPoints > 0.0 &&
                    entry.driverIds.take(3).toSet() == order.take(3).map { it.driverId }.toSet()
                ) {
                    // Bonus opzionale: tutti e 3 i piloti nel podio ma ordine
                    // sbagliato (zero slot corretti).
                    rules.podiumNoOrderPoints
                } else {
                    0.0
                }
            }
        }

        PredictionType.POLE -> {
            val pole = result.poleDriver()
            if (pole != null && entry.driverIds.firstOrNull() == pole.driverId) {
                rules.pointsFor(PredictionType.POLE)
            } else 0.0
        }

        PredictionType.FASTEST_LAP -> {
            val fastest = result.fastestLapDriver()
            if (fastest != null && entry.driverIds.firstOrNull() == fastest.driverId) {
                rules.pointsFor(PredictionType.FASTEST_LAP)
            } else 0.0
        }

        PredictionType.DRIVER_EXACT_POSITION -> {
            val driverId = entry.driverIds.firstOrNull()
            val target = entry.position
            val actual = driverId?.let { result.positionOf(it) }
            if (driverId == null || target == null || actual == null) {
                0.0
                } else when {
                    actual == target -> rules.pointsFor(PredictionType.DRIVER_EXACT_POSITION)
                    // Fasce graduate: +-1 (fattore principale), +-2 (fascia
                    // piu' larga, opzionale e default disattivata).
                    abs(actual - target) == 1 ->
                        adjust(
                            driverId,
                            rules.pointsFor(PredictionType.DRIVER_EXACT_POSITION) * rules.exactPositionNearMissFactor,
                            result,
                            rules,
                        )
                    abs(actual - target) == 2 && rules.exactPositionMissFactor2 > 0.0 ->
                        adjust(
                            driverId,
                            rules.pointsFor(PredictionType.DRIVER_EXACT_POSITION) * rules.exactPositionMissFactor2,
                            result,
                            rules,
                        )
                    else -> 0.0
                }
        }

        PredictionType.DRIVER_POSITION_RANGE -> {
            val driverId = entry.driverIds.firstOrNull()
            val min = entry.rangeMin
            val max = entry.rangeMax
            val actual = driverId?.let { result.positionOf(it) }
            if (driverId == null || min == null || max == null || min > max || actual == null) {
                0.0
                } else if (actual in min..max) {
                    adjust(
                        driverId,
                        rules.pointsFor(PredictionType.DRIVER_POSITION_RANGE),
                        result,
                        rules,
                    )
                } else 0.0
        }

        PredictionType.HEAD_TO_HEAD -> {
            val first = entry.driverIds.getOrNull(0)
            val second = entry.driverIds.getOrNull(1)
            if (first == null || second == null || first == second) {
                0.0
            } else {
                val posA = result.positionOf(first)
                val posB = result.positionOf(second)
                // Regola "realistica": il testa a testa vale SOLO se entrambi i
                // piloti classificati. Se uno dei due ritira/squalifica il
                // pronostico vale 0: senza questa clausola si vincerebbe
                // quasi sempre scegliendo un favorito contro il coda.
                if (posA == null || posB == null) {
                    0.0
                } else if (posA < posB) {
                    // Payout: quota congelata nel pronostico (scritture 0009+),
                    // altrimenti fallback sulle fasce del PRIOR pre-stagione
                    // (i pronostici vecchi restano punteggiati come prima).
                    val quota = entry.quota ?: OverUnderQuotes.headToHeadFactor(first, second)
                    adjust(
                        first,
                        rules.pointsFor(PredictionType.HEAD_TO_HEAD) * quota,
                        result,
                        rules,
                    )
                } else {
                    0.0
                }
            }
        }

        PredictionType.DRIVER_DNF -> {
            val driverId = entry.driverIds.firstOrNull()
            val expected = entry.booleanValue
            val actual = driverId?.let { result.driver(it)?.dnf }
            if (driverId == null || expected == null || actual == null) {
                0.0
            } else if (actual == expected) {
                // Quota congelata (0009): "non ritira" non e' piu' gratis, paga
                // x0.5/pNo. Le voci vintage senza quota restano piatte.
                val quota = entry.quota ?: 1.0
                rules.pointsFor(PredictionType.DRIVER_DNF) * quota
            } else 0.0
        }

        PredictionType.DRIVER_POINTS_OVER_UNDER -> {
            val driverId = entry.driverIds.firstOrNull()
            val threshold = entry.threshold
            val over = entry.over
            // Punti effettivi: da pointsScored se inserito, altrimenti dalla
            // tabella F1 per i classificati (B3).
            val actual = driverId?.let { id ->
                result.driver(id)?.let { driver -> result.effectivePoints(driver) }
            }
            if (driverId == null || threshold == null || over == null || actual == null) {
                0.0
            } else if (isOver(actual, threshold) == over) {
                // Quota congelata (0009) se presente, altrimenti piatto.
                val quota = entry.quota ?: 1.0
                rules.pointsFor(PredictionType.DRIVER_POINTS_OVER_UNDER) * quota
            } else 0.0
        }

        PredictionType.SPRINT_WINNER -> {
            val winner = result.sprintWinner()
            if (winner != null && entry.driverIds.firstOrNull() == winner.driverId) {
                rules.pointsFor(PredictionType.SPRINT_WINNER) * rules.sprintPointsMultiplier
            } else 0.0
        }

                PredictionType.SAFETY_CAR_COUNT_OVER_UNDER -> {
            val min = entry.rangeMin
            val max = entry.rangeMax
            if (min != null && max != null) {
                // Semantica a opzioni (0008): si sceglie il numero esatto di
                // SC+VSC ("6 o piu'" = 6..99). Payout per rarita'.
                val factor = OverUnderQuotes.safetyBandFactor(min, max)
                if (result.safetyCars >= min && result.safetyCars <= max) {
                    rules.pointsFor(PredictionType.SAFETY_CAR_COUNT_OVER_UNDER) * factor
                } else 0.0
            } else {
                // Compatibilita' storica: vecchi pronostici sopra/sotto.
                val threshold = entry.threshold
                val over = entry.over
                if (threshold == null || over == null) {
                    0.0
                } else if (isOver(result.safetyCars.toDouble(), threshold) == over) {
                    rules.pointsFor(PredictionType.SAFETY_CAR_COUNT_OVER_UNDER) *
                        rules.overUnderFactor(PredictionType.SAFETY_CAR_COUNT_OVER_UNDER, threshold, over)
                } else 0.0
            }
        }

                PredictionType.PIT_STOPS_OVER_UNDER -> {
            val min = entry.rangeMin
            val max = entry.rangeMax
            if (min != null && max != null) {
                // Semantica a fasce (0008): pit stop TOTALI della griglia in
                // una delle bande predefinite. Payout per rarita'.
                val factor = OverUnderQuotes.pitBandFactor(min, max)
                if (result.totalPitStops >= min && result.totalPitStops <= max) {
                    rules.pointsFor(PredictionType.PIT_STOPS_OVER_UNDER) * factor
                } else 0.0
            } else {
                // Compatibilita' storica: vecchi pronostici sopra/sotto.
                val threshold = entry.threshold
                val over = entry.over
                if (threshold == null || over == null) {
                    0.0
                } else if (isOver(result.totalPitStops.toDouble(), threshold) == over) {
                    rules.pointsFor(PredictionType.PIT_STOPS_OVER_UNDER) *
                        rules.overUnderFactor(PredictionType.PIT_STOPS_OVER_UNDER, threshold, over)
                } else 0.0
            }
        }
    }

    /**
     * Over = "piu' di N"; Under = "N o meno". La partizione e' complementare
     * cosi' un pronostico over e uno under sulla stessa soglia non possono
     * entrambi perdere.
     */
    private fun isOver(actual: Double, threshold: Double): Boolean = actual > threshold

    /**
     * Applica il moltiplicatore di penalita' a un **singolo** importo di
     * punti, e solo quando l'importo rappresenta un merito parziale (podio,
     * fascia di posizione, sfida a testa, posizione esatta mancata di un posto).
     *
     * Le coincidenze esatte — vincitore, pole, giro veloce, sprint, DNF sì/no,
     * posizione esatta, over/under — non vengono mai scontate: chi indovina in
     * pieno prende sempre i punti pieni.
     *
     * Nota: il vecchio `dnfMultiplier` e' deprecato (B11) e non viene piu'
     * applicato — un pilota ritirato non ha posizione e non classifica.
     */
    private fun adjust(
        driverId: String?,
        points: Double,
        result: RaceResultData,
        rules: Rules,
    ): Double {
        if (points == 0.0) return 0.0
        val driver = driverId?.let { result.driver(it) } ?: return points

        var value = points
        if (driver.penaltyPoints > 0) value *= rules.penaltyMultiplier
        return value
    }

    // ---------------------------------------------------------------------
    // Classifica
    // ---------------------------------------------------------------------

    /** Punteggio di un utente in una singola gara. */
    data class RaceScore(
        val raceId: String,
        val userId: String,
        val points: Double,
    )

    /** Riga della classifica di lega. */
    data class StandingsRow(
        val userId: String,
        val displayName: String,
        val totalPoints: Double,
        val raceWins: Int,
        val bestRacePoints: Double,
        val racesPredicted: Int,
    )

    /**
     * Calcola i punteggi gara per ogni utente, considerando solo le gare per cui
     * esiste un risultato. Una gara senza risultato non viene conteggiata.
     *
     * Al massimo un pronostico per coppia (utente, gara): eventuali duplicati
     * (raceId falsificati nel contenuto JSON) vengono conteggiati una sola volta.
     */
    fun computeRaceScores(
        predictions: Map<String, List<RacePrediction>>,
        results: Map<String, RaceResultData>,
        rules: Rules,
    ): List<RaceScore> {
        val scores = mutableListOf<RaceScore>()
        predictions.forEach { (userId, userPredictions) ->
            val seen = mutableSetOf<String>()
            userPredictions.forEach { prediction ->
                // Il raceId usato per il calcolo e' quello della riga (forzato a
                // monte dai repository): se manca o e' gia' stato contato, skip.
                val raceId = prediction.raceId
                if (raceId.isEmpty() || !seen.add(raceId)) return@forEach
                val result = results[raceId] ?: return@forEach
                scores += RaceScore(
                    raceId = raceId,
                    userId = userId,
                    points = score(prediction, result, rules),
                )
            }
        }
        return scores
    }

    /**
     * Classifica della lega con tie-break configurabile.
     * [displayNames] serve solo a ordinare in modo stabile e mostrare i nomi.
     */
    fun computeStandings(
        predictions: Map<String, List<RacePrediction>>,
        results: Map<String, RaceResultData>,
        rules: Rules,
        displayNames: Map<String, String> = emptyMap(),
    ): List<StandingsRow> {
        val scores = computeRaceScores(predictions, results, rules)

        val byUser = scores.groupBy { it.userId }
        // La classifica parte dall'elenco dei membri: chi non ha pronostici
        // resta visibile a 0 punti. Gli ex-membri che hanno ancora pronostici
        // non hanno un nome nota: al posto dell'UUID mostriamo "Ex giocatore".
        val knownNames = displayNames.isNotEmpty()
        val rows = (displayNames.keys + predictions.keys + byUser.keys).distinct().map { userId ->
            val userScores = byUser[userId].orEmpty()
            val best = userScores.maxOfOrNull { it.points } ?: 0.0
            // Vittoria di gara = miglior punteggio tra quelli ottenuti in una gara.
            // Con le gare ancora in corso molte righe hanno lo stesso "best":
            // il tie-break successivo (nome) garantisce un ordine stabile.
            val wins = if (userScores.isEmpty()) 0 else countRaceWins(userId, scores)
            StandingsRow(
                userId = userId,
                displayName = displayNames[userId]
                    ?: if (knownNames) EX_MEMBER_NAME else userId,
                totalPoints = userScores.sumOf { it.points }.roundTo2(),
                raceWins = wins,
                bestRacePoints = best.roundTo2(),
                racesPredicted = userScores.size,
            )
        }

        return rows.sortedWith(standingsComparator(rules, displayNames))
    }

    /** Nome mostrato al posto dell'UUID per chi ha pronostici ma non e' piu' membro. */
    const val EX_MEMBER_NAME = "Ex giocatore"

    /**
     * Numero di gare in cui l'utente ha ottenuto il punteggio piu' alto.
     * Con parita' nella gara, tutti i primi classificati ricevono la vittoria.
     */
    private fun countRaceWins(userId: String, scores: List<RaceScore>): Int {
        val byRace = scores.groupBy { it.raceId }
        var wins = 0
        byRace.forEach { (_, raceScores) ->
            val max = raceScores.maxOf { it.points }
            if (raceScores.any { it.userId == userId && it.points == max && max > 0.0 }) {
                wins++
            }
        }
        return wins
    }

    private fun standingsComparator(
        rules: Rules,
        displayNames: Map<String, String>,
    ): Comparator<StandingsRow> {
        // Regola non negoziabile: i punti totali vengono SEMPE per primi.
        // Lo spareggio configurato serve solo a sbloccare una parita' reale
        // di punti, mai a superare un giocatore che ha totalizzato di piu'.
        val tieBreak: Comparator<StandingsRow> = when (rules.tieBreaker) {
            TieBreaker.TOTAL_POINTS -> compareByDescending { _: StandingsRow -> 0 } // nessuno
            TieBreaker.RACE_WINS -> compareByDescending { it.raceWins }
            TieBreaker.BEST_SINGLE_RACE -> compareByDescending { it.bestRacePoints }
        }
        val base: Comparator<StandingsRow> = compareByDescending<StandingsRow> { it.totalPoints }
            .then(tieBreak)
        // Spareggi successivi sempre gli stessi, per un ordine deterministico:
        // l'ultimo criterio e' lo userId (stabile, irrepetibile nel DB), cosi'
        // due giocatori omonimi identici su OGNI metrica non dipendono
        // dall'ordine di ritorno dei membri dal database.
        return base
            .thenByDescending { it.raceWins }
            .thenByDescending { it.bestRacePoints }
            .thenBy { it.displayName }
            .thenBy { it.userId }
    }

    // ---------------------------------------------------------------------
    // Deadline
    // ---------------------------------------------------------------------
    // canSubmit/isClosed/canRevealOthers rimossi (B9): la logica di deadline
    // vive in Race.isDeadlinePassed/isPredictionOpen ed e' applicata dalle
    // view model. Le vecchie funzioni non erano chiamate da nessuna parte e
    // contenevano un hack (nowEpochMs == 0L) che disattivava il controllo.

    private fun Double.roundTo2(): Double = (this * 100).roundToInt() / 100.0
}
