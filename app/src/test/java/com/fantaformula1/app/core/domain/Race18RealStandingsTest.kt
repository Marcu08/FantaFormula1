package com.fantaformula1.app.core.domain

import com.fantaformula1.app.data.util.AppJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DIAGNOSTICO/REGRESSIONE: calcola i punti reali della gara di Austin 2026
 * dai dati esportati dal database di produzione (risultato, pronostici e
 * regole della lega). Serve a confrontare il calcolo del motore con quello
 * che mostra l'app.
 */
class Race18RealStandingsTest {

    private val raceId = "a6841881-6443-4294-a10b-b7bf4ac7a867"

    private fun realResult(): RaceResultData = AppJson.decodeFromString(
        RaceResultData.serializer(),
        File("src/test/resources/race18_real.json").readText(),
    )

    private fun realRules(): Rules = AppJson.decodeFromString(
        Rules.serializer(),
        File("src/test/resources/league_rules_real.json").readText(),
    )

    private fun realPredictions(): Map<String, List<RacePrediction>> {
        val rows = AppJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(PredictionRow.serializer()),
            File("src/test/resources/predictions18_real.json").readText(),
        )
        return rows.associate { row ->
            row.uid to listOf(
                AppJson.decodeFromString(RacePrediction.serializer(), row.content)
                    .copy(raceId = raceId),
            )
        }
    }

    @kotlinx.serialization.Serializable
    private data class PredictionRow(val uid: String, val content: String)

    @Test
    fun `punti reali di ogni giocatore nella gara di Austin`() {
        val result = realResult()
        val rules = realRules()
        val predictions = realPredictions()

        println("regole: enabled=${rules.enabledTypes.size} punti=${rules.points}")
        predictions.forEach { (userId, preds) ->
            val prediction = preds.single()
            val lines = ScoringEngine.explain(prediction, result, rules)
            val total = ScoringEngine.score(prediction, result, rules)
            println("== UTENTE $userId — TOTALE $total")
            lines.forEach { println("   ${it.type}: ${it.points} | ${it.detail}") }
        }

        val standings = ScoringEngine.computeStandings(
            predictions = predictions,
            results = mapOf(raceId to result),
            rules = rules,
        )
        println("== CLASSIFICA (solo Austin)")
        standings.forEach { println("   ${it.userId}: ${it.totalPoints}") }

        // Il totale della classifica coincide con la somma delle righe.
        standings.forEach { row ->
            val prediction = predictions.getValue(row.userId).single()
            val expected = ScoringEngine.score(prediction, result, rules)
            assertEquals(row.userId, expected, row.totalPoints, 0.001)
        }
    }
}
