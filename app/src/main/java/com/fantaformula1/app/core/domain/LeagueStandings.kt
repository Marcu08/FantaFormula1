package com.fantaformula1.app.core.domain

/**
 * Costruttore puro della classifica di una lega: aggrega i dati grezzi
 * (membri, gare, esiti, pronostici) e li passa allo
 * [ScoringEngine], che resta l'unico responsabile del calcolo dei punti.
 *
 * Tenere questa logica nel core (senza Android) permette di testarla con i
 * soli unit test JVM.
 */
object LeagueStandings {

    /** Riga di sintesi usata dall'UI della classifica. */
    data class Snapshot(
        val rows: List<ScoringEngine.StandingsRow>,
        /** raceId -> punteggi per giocatore, ordinati dal più alto. */
        val scoresByRace: Map<String, List<ScoringEngine.RaceScore>>,
        /** Gare con esito registrato, ordinate per round. */
        val completedRaceIds: List<String>,
    )

    fun build(
        rules: Rules,
        memberNames: Map<String, String>,
        races: List<Race>,
        results: Map<String, RaceResultData>,
        /** userId -> pronostici di tutti gli autori leggibili. */
        predictionsByUser: Map<String, List<RacePrediction>>,
    ): Snapshot {
        val orderedRaces = races.sortedBy { it.round }
        val orderedRaceIds = orderedRaces.map { it.id }

        val raceScores = ScoringEngine.computeRaceScores(
            predictions = predictionsByUser,
            results = results,
            rules = rules,
        )
        val scoresByRace = raceScores
            .groupBy({ it.raceId }, { it })
            .mapValues { (_, scores) ->
                scores.sortedWith(
                    compareByDescending<ScoringEngine.RaceScore> { it.points }
                        .thenBy { memberNames[it.userId].orEmpty() },
                )
            }
            .filterKeys { it in orderedRaceIds || results.containsKey(it) }

        val rows = ScoringEngine.computeStandings(
            predictions = predictionsByUser,
            results = results,
            rules = rules,
            displayNames = memberNames,
        )

        val completed = orderedRaces.filter { results.containsKey(it.id) }.map { it.id }

        return Snapshot(
            rows = rows,
            scoresByRace = scoresByRace,
            completedRaceIds = completed,
        )
    }
}
