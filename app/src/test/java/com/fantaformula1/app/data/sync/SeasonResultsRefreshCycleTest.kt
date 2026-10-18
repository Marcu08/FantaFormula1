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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test d'integrazione del CICLO di rinfresco [runRefreshCycle], testato su
 * JVM senza Context/DataStore: la persistenza e' una lambda registrata.
 * Scenari chiave: un ciclo PARZIALE (un round senza risultati) NON deve
 * perdere i round gia' scaricati (salvati round per round), ne' con cache
 * iniziale assente ne' con cache gia' presente.
 */
class SeasonResultsRefreshCycleTest {

    private val now = 1_792_000_000_000L
    private val today = LocalDate.now(ZoneOffset.UTC)

    /** Calendario di 5 round tutti disputati + 1 futuro (non nel piano). */
    private fun fakeCalendar(): List<F1Race> =
        (1..6).map { round ->
            F1Race(
                season = "2026",
                round = round.toString(),
                raceName = "GP $round",
                date = if (round <= 5) today.minusDays(10L * (6 - round)).toString()
                else today.plusDays(7).toString(),
            )
        }

    private fun cacheWith(vararg rounds: Int, timestamp: Long = 42L) = SeasonResultsCache(
        season = 2026,
        fetchedAtEpochMs = timestamp,
        results = rounds.map { round ->
            RealSeasonResult(
                round = round,
                result = RaceResultData(
                    raceId = "r$round",
                    drivers = listOf(
                        DriverRaceResult(driverId = "VER", position = 1, pointsScored = 25.0),
                    ),
                ),
            )
        },
    )

    private class FakeRemote(
        private val calendar: List<F1Race>,
        private val emptyRounds: Set<Int> = emptySet(),
    ) : SeasonResultsRemote {
        val roundRequests = mutableListOf<Int>()

        override suspend fun calendar(season: Int): List<F1Race> = calendar

        override suspend fun raceResults(season: Int, round: Int): List<F1Result> {
            if (round in emptyRounds) return emptyList() // round senza risultati
            roundRequests += round
            return listOf(
                F1Result(
                    position = "1",
                    positionText = "1",
                    points = "25",
                    status = "Finished",
                    driver = F1Driver(driverId = "max_verstappen"),
                ),
            )
        }
    }

    private class SaveCounter {
        val saved = mutableListOf<SeasonResultsCache>()
        suspend fun save(cache: SeasonResultsCache) {
            saved += cache
        }

        val last: SeasonResultsCache get() = saved.last()
    }

    // ---------------------------------------------------------------
    // Cache ASSENTE + round 3 senza risultati: niente round perso,
    // timestamp ANTICIPATO (retry dopo 30 minuti, non 6 ore).
    // ---------------------------------------------------------------

    @Test
    fun `ciclo parziale con cache assente i round riusciti restano e il timestamp e' anticipato`() = runBlocking {
        val remote = FakeRemote(fakeCalendar(), emptyRounds = setOf(3))
        val sink = SaveCounter()

        val result = requireNotNull(
            runRefreshCycle(
                cached = null,
                remote = remote,
                nowEpochMs = now,
                save = { sink.save(it) },
            ),
        )
        val rounds = result.results.map { it.round }

        // I round 1,2,4,5 sono in cache: il 3 senza risultati NON tocca gli
        // altri e il salvataggio finale NON elimina i round gia' salvati.
        assertEquals(listOf(1, 2, 4, 5), rounds)
        assertEquals(2026, result.season)
        // Timestamp ANTICIPATO: now - (6h - 30min) → retry fra ~30 minuti.
        assertEquals(
            now - (SeasonResultsStore.FRESH_MS - SeasonResultsStore.PARTIAL_RETRY_MS),
            result.fetchedAtEpochMs,
        )
        // Saltato il round 3: gli altri 4 sono stati tutti richiesti.
        assertEquals(listOf(1, 2, 4, 5), remote.roundRequests)
        // Persistenza incrementale round per round: 4 salvataggi intermedi
        // (timestamp 0L) + il finale anticipato = 5 salvataggi.
        assertEquals(5, sink.saved.size)
        assertEquals(0L, sink.saved[0].fetchedAtEpochMs)
        assertEquals(listOf(1), sink.saved[0].results.map { it.round })
        // L'ULTIMO salvataggio intermedio (prima del finale) ha TUTTI i round
        // riusciti: nessun round perso fra i salvataggi e la chiusura del ciclo.
        assertEquals(listOf(1, 2, 4, 5), sink.saved[3].results.map { it.round }.sorted())
        assertEquals(result, sink.last)
    }

    // ---------------------------------------------------------------
    // Stesso scenario con cache GIA' PRESENTE: nessun round perso
    // (quelli cache + quelli nuovo scaricati nel ciclo).
    // ---------------------------------------------------------------

    @Test
    fun `ciclo parziale con cache presente nessun round perso`() = runBlocking {
        val remote = FakeRemote(fakeCalendar(), emptyRounds = setOf(3))
        // Cache precedente (es. da un ciclo interrotto): round 1 e 2 in cache.
        val cached = cacheWith(1, 2)
        val sink = SaveCounter()

        val result = requireNotNull(runRefreshCycle(cached, remote, now) { sink.save(it) })

        val rounds = result.results.map { it.round }
        assertEquals(listOf(1, 2, 4, 5), rounds)
        // Il round 3 senza risultati non arriva nemmeno al mapping; 1 e 2 non
        // riscaricati (gia' in cache), 4 e 5 si.
        assertEquals(listOf(4, 5), remote.roundRequests)
        assertEquals(
            now - (SeasonResultsStore.FRESH_MS - SeasonResultsStore.PARTIAL_RETRY_MS),
            result.fetchedAtEpochMs,
        )
        // La cache iniziale non e' la base del salvataggio finale: il finale
        // contiene ANCHE i round scaricati nel ciclo (3,4,5 -> 4 e 5).
        assertTrue(sink.saved.size >= 3)
        assertEquals(result, sink.last)
    }

    // ---------------------------------------------------------------
    // Ciclo COMPLETO (tutti i round trovati): timestamp = now.
    // ---------------------------------------------------------------

    @Test
    fun `ciclo completo avanza il timestamp a now`() = runBlocking {
        val remote = FakeRemote(fakeCalendar())
        val sink = SaveCounter()

        val result = requireNotNull(runRefreshCycle(cacheWith(1, 2), remote, now) { sink.save(it) })

        assertEquals(listOf(1, 2, 3, 4, 5), result.results.map { it.round })
        assertEquals(now, result.fetchedAtEpochMs)
        assertEquals(result, sink.last)
    }
}
