package com.fantaformula1.app.data.quotes

import com.fantaformula1.app.core.domain.DriverSeasonStats
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.QuoteKeys
import com.fantaformula1.app.core.domain.Race
import com.fantaformula1.app.core.domain.RaceStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0013: decisione PURA di pubblicazione (prossima gara aperta, nessun
 * pronostico, quote mancanti all'apertura) e costruzione dell'insieme quote
 * con il codice esistente.
 */
class QuotePlanningTest {

    private val now: Instant = Instant.parse("2026-10-27T12:00:00Z")

    private fun race(
        id: String,
        round: Int,
        deadlineHoursFromNow: Long = 48,
        status: RaceStatus = RaceStatus.SCHEDULED,
    ) = Race(
        id = id,
        seasonId = "s1",
        leagueId = "l1",
        round = round,
        name = "Gara $round",
        isSprint = false,
        deadline = now.plusSeconds(deadlineHoursFromNow * 3600),
        status = status,
    )

    @Test
    fun `prossima gara aperta - round piu basso, future e chiuse escluse`() {
        val races = listOf(
            race("r3", round = 3),
            race("r1", round = 1, deadlineHoursFromNow = -2), // deadline passata
            race("r2", round = 2, status = RaceStatus.COMPLETED),
            race("r4", round = 4),
        )
        assertEquals("r3", nextOpenRaceForQuotes(races, now)?.id)
    }

    @Test
    fun `nessuna gara aperta - null`() {
        val races = listOf(
            race("r1", round = 1, deadlineHoursFromNow = -2),
            race("r2", round = 2, status = RaceStatus.CLOSED),
        )
        assertNull(nextOpenRaceForQuotes(races, now))
    }

    @Test
    fun `all'apertura si pubblica solo se mancano le quote e non ci sono pronostici`() {
        val open = race("r1", round = 1)
        assertTrue(
            shouldPublishQuotes(
                race = open, now = now, hasPredictions = false,
                hasQuotes = false, requireMissingQuotes = true,
            ),
        )
        assertFalse(
            shouldPublishQuotes(
                race = open, now = now, hasPredictions = false,
                hasQuotes = true, requireMissingQuotes = true,
            ),
        )
        // Dopo i risultati si ripubblica anche se le quote esistono...
        assertTrue(
            shouldPublishQuotes(
                race = open, now = now, hasPredictions = false,
                hasQuotes = true, requireMissingQuotes = false,
            ),
        )
        // ...ma MAI se esistono pronostici (immutabilita' server) o la gara
        // non e' piu' aperta.
        assertFalse(
            shouldPublishQuotes(
                race = open, now = now, hasPredictions = true,
                hasQuotes = false, requireMissingQuotes = false,
            ),
        )
        assertFalse(
            shouldPublishQuotes(
                race = race("r2", round = 2, deadlineHoursFromNow = -2),
                now = now, hasPredictions = false, hasQuotes = false,
                requireMissingQuotes = false,
            ),
        )
    }

    @Test
    fun `buildRaceQuotes - tutte le scelte con il codice esistente`() {
        val quotes = buildRaceQuotes(DriverSeasonStats.empty, listOf("LEC", "VER"))

        // 2 piloti: 4 DNF + 4 punti + 2 testa a testa (una coppia, due lati).
        assertEquals(10, quotes.size)

        val dnf = quotes.filter { it.type == PredictionType.DRIVER_DNF }
        assertTrue(dnf.any { it.key == QuoteKeys.dnf("LEC", true) })
        assertTrue(dnf.any { it.key == QuoteKeys.dnf("VER", false) })
        assertTrue(dnf.all { it.threshold == null && it.quota > 0.0 })

        val points = quotes.filter { it.type == PredictionType.DRIVER_POINTS_OVER_UNDER }
        assertTrue(points.all { it.threshold != null && it.threshold!! > 0.0 })
        assertTrue(points.any { it.key == QuoteKeys.points("LEC", true) })

        val h2h = quotes.filter { it.type == PredictionType.HEAD_TO_HEAD }
        assertEquals(
            setOf(QuoteKeys.headToHead("LEC", "VER"), QuoteKeys.headToHead("VER", "LEC")),
            h2h.map { it.key }.toSet(),
        )
        // La coppia e' canonica in entrambe le chiavi.
        assertTrue(h2h.all { it.key.startsWith("LEC|VER|") })
        assertTrue(quotes.all { it.quota.isFinite() && it.quota >= 0.5 })
    }
}
