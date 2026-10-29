package com.fantaformula1.app.data.repository

import com.fantaformula1.app.core.domain.PredictionEntry
import com.fantaformula1.app.core.domain.PredictionType
import com.fantaformula1.app.core.domain.RaceQuote
import com.fantaformula1.app.core.domain.withPublishedQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0013: mapping righe `race_quotes` -> quota/soglia del pronostico e stato
 * "quote non disponibili" quando la gara non ha righe.
 */
class RaceQuoteMappingTest {

    private val quotes = listOf(
        RaceQuote(PredictionType.DRIVER_DNF, "PER|true", null, 5.0),
        RaceQuote(PredictionType.DRIVER_DNF, "PER|false", null, 1.5),
        RaceQuote(PredictionType.DRIVER_POINTS_OVER_UNDER, "VER|true", 12.5, 1.6),
        RaceQuote(PredictionType.DRIVER_POINTS_OVER_UNDER, "VER|false", 12.5, 1.9),
        RaceQuote(PredictionType.HEAD_TO_HEAD, "LEC|VER|LEC", null, 0.8),
    )

    @Test
    fun `righe valide mappate, righe non giocabili scartate`() {
        val rows = listOf(
            RaceQuoteRowDto("DRIVER_DNF", "PER|true", null, 5.0),
            RaceQuoteRowDto("DRIVER_POINTS_OVER_UNDER", "VER|true", 12.5, 1.6),
            // Tipo sconosciuto e tipo senza quota: scartati.
            RaceQuoteRowDto("SBAGLIATO", "X|true", null, 1.0),
            RaceQuoteRowDto("WINNER", "VER|true", null, 1.0),
            // Punti senza soglia: scartati (dato incompleto).
            RaceQuoteRowDto("DRIVER_POINTS_OVER_UNDER", "VER|false", null, 1.9),
        )
        val mapped = mapQuoteRows(rows)
        assertEquals(2, mapped.size)
        assertEquals(5.0, mapped[0].quota, 0.001)
        assertEquals(12.5, mapped[1].threshold!!, 0.001)
    }

    @Test
    fun `nessuna riga = quote non ancora pubblicate`() {
        assertEquals(RaceQuoteLoad.NotPublished, quoteLoadFromRows(emptyList()))
        // Una sola scelta non basta: il set e' incompleto.
        assertEquals(
            RaceQuoteLoad.NotPublished,
            quoteLoadFromRows(listOf(RaceQuoteRowDto("DRIVER_DNF", "PER|true", null, 5.0))),
        )
    }

    @Test
    fun `un set con i tre tipi a quota e' pubblicato`() {
        val rows = listOf(
            RaceQuoteRowDto("DRIVER_DNF", "PER|true", null, 5.0),
            RaceQuoteRowDto("DRIVER_DNF", "PER|false", null, 1.5),
            RaceQuoteRowDto("DRIVER_POINTS_OVER_UNDER", "VER|true", 12.5, 1.6),
            RaceQuoteRowDto("DRIVER_POINTS_OVER_UNDER", "VER|false", 12.5, 1.9),
            RaceQuoteRowDto("HEAD_TO_HEAD", "LEC|VER|LEC", null, 0.8),
        )
        assertTrue(quoteLoadFromRows(rows) is RaceQuoteLoad.Published)
    }

    @Test
    fun `applica quota e soglia del server alla voce`() {
        val dnf = PredictionEntry(
            type = PredictionType.DRIVER_DNF,
            driverIds = listOf("PER"),
            booleanValue = true,
        )
        val appliedDnf = dnf.withPublishedQuote(quotes)!!
        assertEquals(5.0, appliedDnf.quota!!, 0.001)

        val points = PredictionEntry(
            type = PredictionType.DRIVER_POINTS_OVER_UNDER,
            driverIds = listOf("VER"),
            threshold = 9.0, // valore locale sbagliato: vince quello del server
            over = true,
        )
        val appliedPoints = points.withPublishedQuote(quotes)!!
        assertEquals(12.5, appliedPoints.threshold!!, 0.001)
        assertEquals(1.6, appliedPoints.quota!!, 0.001)

        val h2h = PredictionEntry(
            type = PredictionType.HEAD_TO_HEAD,
            driverIds = listOf("LEC", "VER"),
        )
        assertEquals(0.8, h2h.withPublishedQuote(quotes)!!.quota!!, 0.001)
    }

    @Test
    fun `quota assente per la scelta - voce non inviabile`() {
        val missing = PredictionEntry(
            type = PredictionType.DRIVER_DNF,
            driverIds = listOf("HAM"),
            booleanValue = true,
        )
        assertNull(missing.withPublishedQuote(quotes))

        // I tipi senza quota restano invariati.
        val winner = PredictionEntry(type = PredictionType.WINNER, driverIds = listOf("VER"))
        assertEquals(winner, winner.withPublishedQuote(emptyList()))
    }

    @Test
    fun `riconosce gli errori di schema mancante`() {
        assertTrue(isMissingSchemaError("PGRST205: Could not find the table 'public.race_quotes'"))
        assertTrue(isMissingSchemaError("PGRST202: Could not find the function public.publish_race_quotes"))
        assertTrue(isMissingSchemaError("42P01: relation \"public.race_quotes\" does not exist"))
        assertTrue(!isMissingSchemaError("permission denied for table race_quotes"))
        assertTrue(!isMissingSchemaError(null))
    }
}
