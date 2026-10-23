package com.fantaformula1.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F3 — mapping classifica calcolata <-> snapshot per la cache offline.
 * `racesPredicted` non e' nello snapshot (formato stabile F2) e degrada a 0.
 */
class SeasonSnapshotMappingTest {

    @Test
    fun `toCached mappa tutti i campi con la posizione esplicita`() {
        val row = ScoringEngine.StandingsRow(
            userId = "u1",
            displayName = "Mario",
            totalPoints = 42.5,
            raceWins = 3,
            bestRacePoints = 18.0,
            racesPredicted = 5,
        )
        assertEquals(
            SeasonStandingsRow(
                position = 2,
                userId = "u1",
                displayName = "Mario",
                totalPoints = 42.5,
                raceWins = 3,
                bestRacePoints = 18.0,
            ),
            row.toCached(position = 2),
        )
    }

    @Test
    fun `round trip perde solo racesPredicted`() {
        val original = ScoringEngine.StandingsRow(
            userId = "u2",
            displayName = "Luigi",
            totalPoints = 12.0,
            raceWins = 1,
            bestRacePoints = 12.0,
            racesPredicted = 4,
        )
        val restored = original.toCached(position = 1).toDomainRow()
        assertEquals(original.userId, restored.userId)
        assertEquals(original.displayName, restored.displayName)
        assertEquals(original.totalPoints, restored.totalPoints, 0.0)
        assertEquals(original.raceWins, restored.raceWins)
        assertEquals(original.bestRacePoints, restored.bestRacePoints, 0.0)
        assertEquals(0, restored.racesPredicted)
    }
}
