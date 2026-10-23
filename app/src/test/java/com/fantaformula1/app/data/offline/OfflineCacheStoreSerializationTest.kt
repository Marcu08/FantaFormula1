package com.fantaformula1.app.data.offline

import com.fantaformula1.app.core.domain.PredictionEntry
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.core.domain.RaceStatus
import com.fantaformula1.app.core.domain.Rules
import com.fantaformula1.app.core.domain.SeasonStandingsRow
import com.fantaformula1.app.core.domain.SeasonStandingsSnapshot
import com.fantaformula1.app.data.repository.League
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F3 — round-trip JSON della cache offline, testati senza Android Context:
 * la parte serializzabile vive in [OfflineCacheJson].
 */
class OfflineCacheStoreSerializationTest {

    @Test
    fun `leghe con regole round trip`() {
        val rules = Rules.default().copy(revealOthersAfterDeadline = false)
        val leagues = listOf(
            League(
                id = "l1",
                name = "Amici del Gran Premio",
                inviteCode = "ABC123",
                rules = rules,
                createdBy = "u1",
            ),
            League(
                id = "l2",
                name = "Lega senza admin",
                inviteCode = "ZZ9999",
                rules = Rules(),
                createdBy = null,
            ),
        )
        val decoded = OfflineCacheJson.decodeLeagues(OfflineCacheJson.encodeLeagues(leagues))
        assertEquals(leagues, decoded)
    }

    @Test
    fun `gare round trip con deadline istant`() {
        val races = listOf(
            Race(
                id = "r1",
                seasonId = "s1",
                leagueId = "l1",
                round = 3,
                name = "Gran Premio d'Italia",
                country = "Italy",
                isSprint = false,
                deadline = Instant.parse("2026-03-15T13:00:00Z"),
                status = RaceStatus.OPEN,
            ),
            Race(
                id = "r2",
                seasonId = "s1",
                leagueId = "l1",
                round = 4,
                name = "Gran Premio di Monaco",
                country = null,
                isSprint = true,
                deadline = null,
                status = RaceStatus.SCHEDULED,
            ),
        )
        val decoded = OfflineCacheJson.decodeRaces(OfflineCacheJson.encodeRaces(races))
        assertEquals(races, decoded)
    }

    @Test
    fun `pronostici round trip con quota congelata`() {
        val predictions = mapOf(
            "r1" to RacePrediction(
                raceId = "r1",
                entries = listOf(
                    PredictionEntry(type = PredictionType.WINNER, driverIds = listOf("VER")),
                    PredictionEntry(type = PredictionType.PODIUM, driverIds = listOf("VER", "NOR", "LEC")),
                    PredictionEntry(type = PredictionType.DRIVER_DNF, driverIds = listOf("HAM"), booleanValue = false, quota = 1.8),
                ),
                submittedAtEpochMs = 1_773_000_000_000L,
            ),
            "r2" to RacePrediction(raceId = "r2"),
        )
        val decoded = OfflineCacheJson.decodePredictions(
            OfflineCacheJson.encodePredictions(predictions),
        )
        assertEquals(predictions, decoded)
    }

    @Test
    fun `classifica snapshot round trip`() {
        val snapshot = SeasonStandingsSnapshot(
            rows = listOf(
                SeasonStandingsRow(1, "u1", "Mario", 42.5, 3, 18.0),
                SeasonStandingsRow(2, "u2", "Luigi", 42.5, 2, 18.0),
            ),
        )
        val decoded = OfflineCacheJson.decodeStandings(
            OfflineCacheJson.encodeStandings(snapshot),
        )
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `json rotto degrada a null su tutte le categorie`() {
        assertNull(OfflineCacheJson.decodeLeagues("{non json"))
        assertNull(OfflineCacheJson.decodeRaces("{non json"))
        assertNull(OfflineCacheJson.decodePredictions("{non json"))
        assertNull(OfflineCacheJson.decodeStandings("{non json"))
        // Forma sbagliata ma JSON valido: stessa degradazione senza crash.
        assertNull(OfflineCacheJson.decodeStandings("""{"rows": 5}"""))
    }
}
