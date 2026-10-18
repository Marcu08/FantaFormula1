package com.fantaformula1.app.data.openf1

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping gara interna -> sessione OpenF1 "Race": la chiave e' la DEADLINE
 * (istante della Qualifying dello stesso weekend), fallback sui paesi
 * (alias Jolpica->ISO3). Niente "latest", niente round usato come chiave.
 */
class OpenF1SessionMatcherTest {

    private fun session(
        meeting: Int,
        key: Int,
        type: String,
        country: String,
        code: String,
        start: String,
    ) = OpenF1Session(
        sessionKey = key,
        meetingKey = meeting,
        sessionName = type,
        sessionType = type,
        countryName = country,
        countryCode = code,
        location = "",
        circuitShortName = "",
        dateStart = start,
        year = 2026,
        isCancelled = false,
    )

    /** Weekend Australia: meeting con FP/Quali/Race (sessione Race != round 1). */
    private fun australiaWeekend() = listOf(
        session(1, 100, "Practice", "Australia", "AUS", "2026-03-06T02:30:00+01:00"),
        session(1, 101, "Qualifying", "Australia", "AUS", "2026-03-07T06:00:00+01:00"),
        // Il round Jolpica 1 NON e' la session_key OpenF1: il matcher non guarda i round.
        session(1, 11234, "Race", "Australia", "AUS", "2026-03-08T05:00:00+01:00"),
    )

    @Test
    fun `deadline coincidente con la qualifica identifica la sessione`() {
        val deadline = Instant.parse("2026-03-07T05:00:00Z") // = 06:00 +01:00
        val match = OpenF1SessionMatcher.match(
            raceSeason = 2026,
            raceCountry = "Australia",
            raceName = "Australian Grand Prix",
            deadline = deadline,
            sessions = australiaWeekend(),
        )
        assertEquals(OpenF1SessionMatcher.Match.Ok(11234), match)
    }

    @Test
    fun `paesi con nomi diversi tra le fonti via alias ISO3`() {
        val match = OpenF1SessionMatcher.match(
            raceSeason = 2026,
            raceCountry = "UK",
            raceName = "British Grand Prix",
            deadline = null,
            sessions = listOf(session(7, 900, "Race", "United Kingdom", "GBR", "2026-07-05T15:00:00+01:00")),
        )
        assertEquals(OpenF1SessionMatcher.Match.Ok(900), match)
    }

    @Test
    fun `paese ambiguo USA senza deadline resta a mano`() {
        val sessions = listOf(
            session(12, 11275, "Race", "United States", "USA", "2026-05-03T19:00:00-04:00"), // Miami
            session(13, 11290, "Race", "United States", "USA", "2026-10-18T20:00:00-05:00"), // COTA
        )
        val match = OpenF1SessionMatcher.match(
            raceSeason = 2026,
            raceCountry = "USA",
            raceName = "Miami Grand Prix",
            deadline = null,
            sessions = sessions,
        )
        assertTrue(match is OpenF1SessionMatcher.Match.Failed)
    }

    @Test
    fun `paese ambiguo USA si risolve con le deadline dei due weekend`() {
        val sessions = listOf(
            // Weekend Miami: qualifica 02/05, gara 03/05.
            session(12, 11275, "Qualifying", "United States", "USA", "2026-05-02T22:00:00Z"),
            session(12, 11275, "Race", "United States", "USA", "2026-05-03T23:00:00Z"),
            // Weekend COTA: qualifica 17/10, gara 18/10.
            session(13, 11290, "Qualifying", "United States", "USA", "2026-10-17T21:00:00Z"),
            session(13, 11290, "Race", "United States", "USA", "2026-10-18T20:00:00Z"),
        )
        val match = OpenF1SessionMatcher.match(
            raceSeason = 2026,
            raceCountry = "USA",
            raceName = "Miami Grand Prix",
            deadline = Instant.parse("2026-05-02T22:00:00Z"), // = qualifica Miami
            sessions = sessions,
        )
        assertEquals(OpenF1SessionMatcher.Match.Ok(11275), match)
    }

    @Test
    fun `la sessione sprint stesso meeting non rende ambigua la gara`() {
        // REGRESSIONE dal collaudo live (Cina): OpenF1 marca Sprint con
        // session_type="Race"; il matcher deve scegliere SOLO session_name="Race".
        val sessions = listOf(
            session(2, 11240, "Sprint", "China", "CHN", "2026-03-14T04:00:00+01:00"),
            session(2, 11245, "Race", "China", "CHN", "2026-03-15T08:00:00+01:00"),
        )
        val match = OpenF1SessionMatcher.match(
            raceSeason = 2026,
            raceCountry = "China",
            raceName = "Chinese Grand Prix",
            deadline = null, // il fallback paese deve bastare: Cina e' unica
            sessions = sessions,
        )
        assertEquals(OpenF1SessionMatcher.Match.Ok(11245), match)
    }

    @Test
    fun `paese senza corrispondenza finisce a mano`() {
        val match = OpenF1SessionMatcher.match(
            raceSeason = 2026,
            raceCountry = "Pluto",
            raceName = "GP di Pluto",
            deadline = null,
            sessions = australiaWeekend(),
        )
        assertTrue(match is OpenF1SessionMatcher.Match.Failed)
    }

    @Test
    fun `numero round non deve essere un identificatore`() {
        // Sessione 11234 per il round 1: il matcher risponde dalla deadline
        // e dal paese, MAI dal numero di round (che qui coincide per caso).
        val match = OpenF1SessionMatcher.match(
            raceSeason = 2026,
            raceCountry = "Australia",
            raceName = "Australian Grand Prix",
            deadline = Instant.parse("2026-03-07T05:00:00Z"),
            sessions = australiaWeekend(),
        )
        assertEquals(OpenF1SessionMatcher.Match.Ok(11234), match)
    }
}
