package com.fantaformula1.app.data.sync

import com.fantaformula1.app.core.domain.DriverRaceResult
import com.fantaformula1.app.core.domain.RaceResultData
import com.fantaformula1.app.data.f1api.model.F1Driver
import com.fantaformula1.app.data.f1api.model.F1Race
import com.fantaformula1.app.data.f1api.model.F1Result
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test dell'ottimizzazione del rinfresco: cache fresca = ZERO richieste;
 * cache 1-10 + 3 round nuovi = SOLO 3 richieste di round; fallimento su un
 * round = gli altri restano scaricati; merge incrementale preserva la cache.
 */
class SeasonResultsStoreOptimizationTest {

    private val today = LocalDate.now(ZoneOffset.UTC)

    /** Gara pastata se [daysAgo] > 0, futura se negativa. */
    private fun calendarRace(round: Int, daysAgo: Int) = F1Race(
        season = "2026",
        round = round.toString(),
        raceName = "GP $round",
        date = today.minusDays(daysAgo.toLong()).toString(),
    )

    /** Calendario 2026: round 1-13 gia' disputati, 14 futuro. */
    private fun fakeCalendar(): List<F1Race> =
        (1..14).map { round ->
            calendarRace(round, daysAgo = if (round <= 13) 15 - round else -3)
        }

    private fun cacheWith(vararg rounds: Int) = SeasonResultsCache(
        season = 2026,
        fetchedAtEpochMs = 0L,
        results = rounds.map { round ->
            RealSeasonResult(
                round = round,
                result = RaceResultData(
                    raceId = "r$round",
                    drivers = listOf(
                        DriverRaceResult(
                            driverId = "VER",
                            position = 1,
                            pointsScored = 25.0,
                        ),
                    ),
                ),
            )
        },
    )

    /** F1Result per un pilota noto (VER = max_verstappen). */
    private fun fakeF1Result() = F1Result(
        position = "1",
        positionText = "1",
        points = "25",
        status = "Finished",
        driver = F1Driver(driverId = "max_verstappen"),
    )

    /** Fake remoto: conteggia le richieste e puo' rompere round scelti. */
    private class FakeRemote(
        private val calendar: List<F1Race>,
        private val failRounds: Set<Int> = emptySet(),
        private val result: F1Result,
    ) : SeasonResultsRemote {
        val roundRequests = mutableListOf<Int>()

        override suspend fun calendar(season: Int): List<F1Race> = calendar

        override suspend fun raceResults(season: Int, round: Int): List<F1Result> {
            if (round in failRounds) {
                throw IllegalStateException("rete giu' per il round $round")
            }
            roundRequests += round
            return listOf(result)
        }
    }

    @Test
    fun `cache fresca significa ZERO richieste di alcun tipo`() {
        val maxAge = 6 * 60 * 60 * 1000L
        val fresh = cacheWith(1, 2, 3).copy(fetchedAtEpochMs = 1_000_000L)
        // Seconda apertura entro 6 ore: shouldRefresh = false -> il chiamante
        // non avvia NESSUNA richiesta (nemmeno il calendario).
        assertFalse(shouldRefreshSeasonCache(fresh, force = false, 1_000_001L, maxAge))
        assertTrue(shouldRefreshSeasonCache(fresh, force = false, 1_000_000L + maxAge, maxAge))
        assertTrue(shouldRefreshSeasonCache(fresh, force = true, 1_000_001L, maxAge))
    }

    @Test
    fun `piano con cache 1-10 e 3 round nuovi siano solo 11 12 13`() {
        val cached = cacheWith(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        assertEquals(listOf(11, 12, 13), refreshPlan(cached, fakeCalendar()))
    }

    @Test
    fun `piano vuoto quando la cache copre tutti i round passati`() {
        val cached = cacheWith(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)
        assertEquals(emptyList<Int>(), refreshPlan(cached, fakeCalendar()))
    }

    @Test
    fun `date non riconoscibili e round futuri sono esclusi dal piano`() {
        val calendar = listOf(
            F1Race(season = "2026", round = "1", raceName = "GP 1", date = today.minusDays(5).toString()),
            // Data non riconoscibile = non pasato (comportamento conservativo).
            F1Race(season = "2026", round = "2", raceName = "GP 2", date = null),
            F1Race(season = "2026", round = "3", raceName = "GP 3", date = today.minusDays(2).toString()),
            F1Race(season = "2026", round = "4", raceName = "GP 4", date = today.plusDays(7).toString()),
        )
        assertEquals(listOf(1, 3), refreshPlan(null, calendar))
    }

    @Test
    fun `con cache 1-10 vengono fatte SOLO 3 richieste di round`() = runBlocking {
        val remote = FakeRemote(fakeCalendar(), result = fakeF1Result())
        val cached = cacheWith(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val plan = refreshPlan(cached, remote.calendar(2026))
        val fetched = downloadMissingRounds(remote, 2026, plan, timeoutMs = 10_000L)
        // NON si richiedono i 13 round gia' in cache: solo i 3 mancanti.
        assertEquals(listOf(11, 12, 13), remote.roundRequests)
        assertEquals(listOf(11, 12, 13), fetched.map { it.round })
    }

    @Test
    fun `fallimento su un round non butta gli altri gia scaricati`() = runBlocking {
        val remote = FakeRemote(fakeCalendar(), failRounds = setOf(12), result = fakeF1Result())
        val cached = cacheWith(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val plan = refreshPlan(cached, remote.calendar(2026))
        val fetched = downloadMissingRounds(remote, 2026, plan, 10_000L)
        // Il round 12 e' caduto: restano 11 e 13 (e in cache c'e' gia' 1-10).
        assertEquals(listOf(11, 13), fetched.map { it.round })
        val merged = mergeFetched(cached, fetched, 2026, nowEpochMs = 99L)
        val rounds = merged.results.map { it.round }
        // La cache merged NON perde i round 1-10 e 11, 13; il 12 resta assente.
        assertEquals((1..11).toList() + listOf(13), rounds)
        assertFalse(rounds.contains(12))
        assertEquals(99L, merged.fetchedAtEpochMs)
    }

    @Test
    fun `merge incrementale non riscrive un round gia in cache`() {
        val cached = cacheWith(5, 6)
        val fetched = listOf(
            RealSeasonResult(4, RaceResultData(raceId = "r4")),
        )
        val merged = mergeFetched(cached, fetched, 2026, 42L)
        assertEquals(listOf(4, 5, 6), merged.results.map { it.round })
        assertEquals(42L, merged.fetchedAtEpochMs)
        assertEquals(2026, merged.season)
    }
}
