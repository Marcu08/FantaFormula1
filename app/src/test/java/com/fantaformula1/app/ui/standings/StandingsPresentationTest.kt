package com.fantaformula1.app.ui.standings

import com.fantaformula1.app.core.domain.ScoringEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class StandingsPresentationTest {

    private fun row(
        userId: String,
        name: String,
        points: Double,
        wins: Int = 0,
        best: Double = points,
        races: Int = 0,
    ) = ScoringEngine.StandingsRow(
        userId = userId,
        displayName = name,
        totalPoints = points,
        raceWins = wins,
        bestRacePoints = best,
        racesPredicted = races,
    )

    @Test
    fun `iniziali da nome e cognome`() {
        assertEquals("MR", initialsFor("Mario Rossi"))
        assertEquals("MC", initialsFor("mcselli"))
        assertEquals("?", initialsFor("   "))
    }

    @Test
    fun `distacco dal leader`() {
        val rows = listOf(
            row("a", "Alice", 50.0),
            row("b", "Bob", 32.5),
            row("c", "Carla", 20.0),
        )
        assertEquals(0.0, gapToLeader(rows, 0), 0.001)
        assertEquals(17.5, gapToLeader(rows, 1), 0.001)
        assertEquals(30.0, gapToLeader(rows, 2), 0.001)
        assertEquals(0.0, gapToLeader(emptyList(), 0), 0.001)
    }

    @Test
    fun `classifica ultima gara include chi ha zero punti e ordina per punti`() {
        val rows = listOf(
            row("a", "Alice", 50.0),
            row("b", "Bob", 40.0),
            row("c", "Carla", 30.0),
        )
        val ranking = lastRaceRanking(
            rows = rows,
            pointsByUser = mapOf("a" to 12.0, "b" to 0.0),
        )
        assertEquals(listOf("Alice", "Bob", "Carla"), ranking.map { it.row.displayName })
        assertEquals(listOf(12.0, 0.0, 0.0), ranking.map { it.points })
    }

    @Test
    fun `classifica ultima gara a parita ordina per nome`() {
        val rows = listOf(
            row("z", "Zoe", 10.0),
            row("a", "Anna", 20.0),
        )
        val ranking = lastRaceRanking(
            rows = rows,
            pointsByUser = mapOf("z" to 5.0, "a" to 5.0),
        )
        assertEquals(listOf("Anna", "Zoe"), ranking.map { it.row.displayName })
    }
}
