package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.SeasonStandingsRow
import com.fantaformula1.app.core.domain.SeasonStandingsSnapshot
import com.fantaformula1.app.data.model.SeasonDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2 — Storico stagioni: le chiavi di `season_snapshots` devono restare in
 * snake_case (colonne Postgres) e la classifica congelata deve sopravvivere
 * al round-trip JSON senza perdere posizioni, nomi o punti.
 *
 * Usa lo stesso Json di AppJson (`encodeDefaults = true`).
 */
class SeasonHistoryDtoTest {

    private val json = Json { encodeDefaults = true }

    private val standings: JsonElement = json.parseToJsonElement(
        """
        {"rows":[{"position":1,"userId":"u1","displayName":"Mario",
                  "totalPoints":42.5,"raceWins":3,"bestRacePoints":18.0}]}
        """.trimIndent(),
    )

    private val sample = SeasonSnapshotDto(
        seasonId = "season-1",
        leagueId = "league-1",
        seasonName = "2026",
        standings = standings,
        archivedBy = "user-1",
        archivedAt = "2026-10-24T10:00:00+00:00",
    )

    @Test
    fun `le chiavi del JSON sono in snake_case`() {
        val element = json.encodeToJsonElement(SeasonSnapshotDto.serializer(), sample).jsonObject

        assertTrue("chiave season_id mancante: $element", element.containsKey("season_id"))
        assertTrue("chiave standings mancante: $element", element.containsKey("standings"))
        assertEquals("season-1", element["season_id"]?.jsonPrimitive?.content)
        assertEquals("league-1", element["league_id"]?.jsonPrimitive?.content)
        assertEquals("2026", element["season_name"]?.jsonPrimitive?.content)
        assertEquals("user-1", element["archived_by"]?.jsonPrimitive?.content)
        assertEquals("2026-10-24T10:00:00+00:00", element["archived_at"]?.jsonPrimitive?.content)
    }

    @Test
    fun `round trip dello snapshot senza perdere campi`() {
        val encoded = json.encodeToJsonElement(SeasonSnapshotDto.serializer(), sample)
        val decoded = json.decodeFromJsonElement(SeasonSnapshotDto.serializer(), encoded)

        assertEquals(sample, decoded)
    }

    @Test
    fun `round trip della classifica congelata tiene posizioni nomi e punti intatti`() {
        val snapshot = SeasonStandingsSnapshot(
            rows = listOf(
                SeasonStandingsRow(
                    position = 1,
                    userId = "u1",
                    displayName = "Mario",
                    totalPoints = 42.5,
                    raceWins = 3,
                    bestRacePoints = 18.0,
                ),
                SeasonStandingsRow(
                    position = 2,
                    userId = "u2",
                    displayName = "Luigi",
                    totalPoints = 10.0,
                    raceWins = 0,
                    bestRacePoints = 10.0,
                ),
            ),
        )

        val encoded = json.encodeToJsonElement(SeasonStandingsSnapshot.serializer(), snapshot)
        val decoded = json.decodeFromJsonElement(SeasonStandingsSnapshot.serializer(), encoded)

        assertEquals(snapshot, decoded)
        val firstRow = encoded.jsonObject["rows"]!!.jsonArray[0].jsonObject
        assertEquals(1, firstRow["position"]?.jsonPrimitive?.content?.toInt())
        assertEquals("Mario", firstRow["displayName"]?.jsonPrimitive?.content)
        assertEquals("42.5", firstRow["totalPoints"]?.jsonPrimitive?.content)
    }

    @Test
    fun `round trip della classifica dentro lo snapshot della stagione`() {
        val snapshot = SeasonStandingsSnapshot(
            rows = listOf(SeasonStandingsRow(1, "u1", "Mario", 42.5, 3, 18.0)),
        )
        val withStandings = sample.copy(
            standings = json.encodeToJsonElement(SeasonStandingsSnapshot.serializer(), snapshot),
        )

        val encoded = json.encodeToJsonElement(SeasonSnapshotDto.serializer(), withStandings)
        val decoded = json.decodeFromJsonElement(SeasonSnapshotDto.serializer(), encoded)
        val rows = json.decodeFromJsonElement(
            SeasonStandingsSnapshot.serializer(),
            decoded.standings,
        ).rows

        assertEquals(1, rows.size)
        assertEquals(1, rows[0].position)
        assertEquals("Mario", rows[0].displayName)
        assertEquals(42.5, rows[0].totalPoints, 0.0)
    }

    // ---------------------------------------------------------- mergePastSeasons

    @Test
    fun `lo snapshot vince sulla stagione disattivata per nome e classifica`() {
        val seasons = listOf(
            SeasonDto(id = "s1", leagueId = "l1", name = "2026 (vecchio nome)", isActive = false),
        )
        val snapshots = listOf(sample.copy(seasonId = "s1", seasonName = "2026"))

        val merged = mergePastSeasons(seasons, snapshots)

        assertEquals(1, merged.size)
        assertEquals("2026", merged[0].name)
        assertEquals("2026-10-24T10:00:00+00:00", merged[0].archivedAt)
        assertEquals(standings, merged[0].standingsJson)
    }

    @Test
    fun `le stagioni disattivate senza snapshot restanno nello storico senza classifica`() {
        val seasons = listOf(
            SeasonDto(id = "s1", leagueId = "l1", name = "2024", isActive = false),
            SeasonDto(id = "s2", leagueId = "l1", name = "2026", isActive = true),
        )

        val merged = mergePastSeasons(seasons, emptyList())

        assertEquals(listOf("s1"), merged.map { it.seasonId })
        assertNull(merged[0].archivedAt)
        assertNull(merged[0].standingsJson)
    }

    @Test
    fun `le stagioni attive non finiscono nello storico`() {
        val seasons = listOf(
            SeasonDto(id = "s1", leagueId = "l1", name = "2026", isActive = true),
        )

        assertTrue(mergePastSeasons(seasons, emptyList()).isEmpty())
    }

    @Test
    fun `ordinamento per data di archiviazione decrescente, senza data in coda`() {
        val seasons = listOf(SeasonDto(id = "s3", leagueId = "l1", name = "senza data", isActive = false))
        val snapshots = listOf(
            sample.copy(seasonId = "s1", archivedAt = "2025-01-01T00:00:00+00:00"),
            sample.copy(seasonId = "s2", archivedAt = "2026-01-01T00:00:00+00:00"),
        )

        val merged = mergePastSeasons(seasons, snapshots)

        assertEquals(listOf("s2", "s1", "s3"), merged.map { it.seasonId })
    }
}
