package com.fantaformula1.app.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sema cache: la funzione e' PURA (top-level, senza Android) quindi
 * testabile su JVM. Datos: 6 ore di vita massima (SeasonResultsStore.FRESH_MS).
 */
class SeasonResultsCacheStaleTest {

    private fun cache(ageMs: Long) = SeasonResultsCache(
        season = 2026,
        fetchedAtEpochMs = 1_000_000L,
        results = emptyList(),
    ).let { it.copy(fetchedAtEpochMs = it.fetchedAtEpochMs - ageMs) }

    @Test
    fun `cache assente = stanca (serve un primo download)`() {
        assertTrue(isStaleSeasonCache(null, nowEpochMs = 10_000L, maxAgeMs = 6 * 60 * 60 * 1000L))
    }

    @Test
    fun `cache fresca dopo poco tempo non e' stanca`() {
        val maxAge = 6 * 60 * 60 * 1000L
        assertFalse(isStaleSeasonCache(cache(1), 1_000_000L + 1, maxAge))
        assertFalse(isStaleSeasonCache(cache(maxAge - 1), 1_000_000L, maxAge))
    }

    @Test
    fun `cache esattamente a 6 ore e oltre e' stanca`() {
        val maxAge = 6 * 60 * 60 * 1000L
        assertTrue(isStaleSeasonCache(cache(maxAge), 1_000_000L, maxAge))
        assertTrue(isStaleSeasonCache(cache(maxAge + 1), 1_000_000L, maxAge))
    }

    // ---------------------------------------------------------------
    // 30 minuti di backoff dopo un ciclo PARZIALE (round mancanti)
    // ---------------------------------------------------------------
    private val maxAge = SeasonResultsStore.FRESH_MS
    private val retry = SeasonResultsStore.PARTIAL_RETRY_MS

    @Test
    fun `ciclo parziale timestamp entro 30 min silenzio poi retry`() {
        // Cache assente: il salvataggio parziale usa il timestamp ANTICIPATO
        // (now - (6h - 30min)) → la cache "muore" 30 minuti dopo il ciclo.
        val now = 1_792_000_000_000L
        val stored = backdatedForPartialRetry(now, maxAge, retry)
        assertEquals(now - (maxAge - retry), stored)
        // A 29'59" dal ciclo (now + 30min - 1ms): NON stanca → zero richieste.
        assertFalse(isStaleSeasonCache(
            cache = SeasonResultsCache(2026, stored, emptyList()),
            nowEpochMs = stored + maxAge - 1,
            maxAgeMs = maxAge,
        ))
        // A 30' esatti (now + 30min) la cache e' stanca → il rinfresco riparte
        // e richiede i round mancanti.
        assertTrue(isStaleSeasonCache(
            cache = SeasonResultsCache(2026, stored, emptyList()),
            nowEpochMs = stored + maxAge,
            maxAgeMs = maxAge,
        ))
    }

    @Test
    fun `annullamento a meta con cache assente riprende subito al prossimo apertura`() {
        // I salvataggi INTERMEDI (annullamento) usano 0L quando la cache era
        // assente: qualsiasi apertura successiva (tempo reale 2026) vede la
        // cache stanca e richiede i ROUND MANCANTI, senza attendere 30 minuti.
        val intermediates = SeasonResultsCache(2026, fetchedAtEpochMs = 0L, results = emptyList())
        assertTrue(isStaleSeasonCache(intermediates, nowEpochMs = 1_792_000_000_000L, maxAgeMs = maxAge))
    }
}
