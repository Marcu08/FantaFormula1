package com.fantaformula1.app.ui.predictions

import com.fantaformula1.app.core.domain.PredictionEntry
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.QuoteKeys
import com.fantaformula1.app.core.domain.RacePrediction
import com.fantaformula1.app.core.domain.RaceQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0013: una scelta a quota non deve mai sparire senza un errore esplicito. */
class QuoteSaveResolutionTest {

    private val dnfDraft = DraftEntry(
        type = PredictionType.DRIVER_DNF,
        driverIds = listOf("PER"),
        booleanValue = true,
    )

    @Test
    fun `quota mancante blocca il salvataggio invece di scartare la voce`() {
        val resolved = resolveEntriesForSave(
            drafts = listOf(dnfDraft),
            quotes = emptyList(),
            mine = null,
        )

        assertTrue(resolved.entries.isEmpty())
        assertEquals(listOf(PredictionType.DRIVER_DNF), resolved.missingQuoteTypes)
    }

    @Test
    fun `quota parziale blocca solo la scelta non coperta`() {
        val quotes = listOf(
            RaceQuote(PredictionType.DRIVER_DNF, QuoteKeys.dnf("PER", true), null, 5.0),
        )
        val resolved = resolveEntriesForSave(
            drafts = listOf(
                dnfDraft,
                DraftEntry(
                    type = PredictionType.HEAD_TO_HEAD,
                    driverIds = listOf("LEC", "VER"),
                ),
            ),
            quotes = quotes,
            mine = null,
        )

        assertEquals(listOf(PredictionType.DRIVER_DNF), resolved.entries.map { it.type })
        assertEquals(listOf(PredictionType.HEAD_TO_HEAD), resolved.missingQuoteTypes)
    }

    @Test
    fun `una voce vintage identica viene conservata`() {
        val previous = PredictionEntry(
            type = PredictionType.DRIVER_DNF,
            driverIds = listOf("PER"),
            booleanValue = true,
            quota = 4.0,
        )
        val resolved = resolveEntriesForSave(
            drafts = listOf(dnfDraft),
            quotes = emptyList(),
            mine = RacePrediction(raceId = "r1", entries = listOf(previous)),
        )

        assertEquals(listOf(previous), resolved.entries)
        assertTrue(resolved.missingQuoteTypes.isEmpty())
    }

    @Test
    fun `una scelta modificata senza quota non viene conservata come vintage`() {
        val previous = PredictionEntry(
            type = PredictionType.DRIVER_DNF,
            driverIds = listOf("PER"),
            booleanValue = false,
            quota = 1.2,
        )
        val resolved = resolveEntriesForSave(
            drafts = listOf(dnfDraft), // booleanValue = true, scelta diversa
            quotes = emptyList(),
            mine = RacePrediction(raceId = "r1", entries = listOf(previous)),
        )

        assertTrue(resolved.entries.isEmpty())
        assertEquals(listOf(PredictionType.DRIVER_DNF), resolved.missingQuoteTypes)
    }

    @Test
    fun `la quota e la soglia del server sostituiscono i valori locali`() {
        val quotes = listOf(
            RaceQuote(PredictionType.DRIVER_POINTS_OVER_UNDER, QuoteKeys.points("VER", true), 12.5, 1.6),
        )
        val draft = DraftEntry(
            type = PredictionType.DRIVER_POINTS_OVER_UNDER,
            driverIds = listOf("VER"),
            threshold = "9.0",
            over = true,
        )

        val resolved = resolveEntriesForSave(listOf(draft), quotes, mine = null)

        assertEquals(1, resolved.entries.size)
        assertEquals(12.5, resolved.entries.single().threshold!!, 0.001)
        assertEquals(1.6, resolved.entries.single().quota!!, 0.001)
        assertTrue(resolved.missingQuoteTypes.isEmpty())
    }

    @Test
    fun `i tipi senza quota non sono toccati`() {
        val winner = DraftEntry(type = PredictionType.WINNER, driverIds = listOf("VER"))
        val resolved = resolveEntriesForSave(listOf(winner), emptyList(), mine = null)

        assertEquals(listOf(PredictionType.WINNER), resolved.entries.map { it.type })
        assertTrue(resolved.missingQuoteTypes.isEmpty())
    }
}
