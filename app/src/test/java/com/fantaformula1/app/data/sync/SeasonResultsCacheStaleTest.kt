package com.fantaformula1.app.data.sync

import org.junit.Assert.assertFalse
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
}
