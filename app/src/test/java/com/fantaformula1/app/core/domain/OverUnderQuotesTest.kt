package com.fantaformula1.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test delle quote a VALORE ATTESO costante (blocco 0009): la quota e'
 * 0.5/p limitata in [0.5; 3.0], "non ritira" non paga mai piu' di "ritira",
 * quota e conteggio sopra/sotto monotoni.
 */
class OverUnderQuotesTest {

    @Test
    fun `quota sempre dentro i limiti per ogni probabilita`() {
        val probabilities = listOf(-1.0, 0.0, 0.0001, 0.05, 0.1, 0.2, 0.25, 0.4, 0.5, 0.6, 0.75, 0.9, 1.0, 2.0)
        probabilities.forEach { p ->
            val q = OverUnderQuotes.quotaFor(p)
            assertTrue("p=$p, q=$q fuori range", q in OverUnderQuotes.QUOTA_MIN..OverUnderQuotes.QUOTA_MAX)
        }
    }

    @Test
    fun `valore atteso 0_5 quando la quota non e' clampata`() {
        // Non clampata <-> p in [0.2, 1] (0.5/p <= 3.0) e p >= 0.01.
        val notClamped = listOf(0.2, 0.25, 0.4, 0.5, 0.6, 0.75, 0.9)
        notClamped.forEach { p ->
            val q = OverUnderQuotes.quotaFor(p)
            assertEquals("p=$p", OverUnderQuotes.EV_TARGET, p * q, 0.0001)
        }
    }

    @Test
    fun `p molto basse o alte vengono clampate`() {
        // p ~ 0 (o negativa, dato errato) -> quota al TETTO, non al minimo.
        assertEquals(OverUnderQuotes.QUOTA_MAX, OverUnderQuotes.quotaFor(0.0), 0.0001)
        assertEquals(OverUnderQuotes.QUOTA_MAX, OverUnderQuotes.quotaFor(-1.0), 0.0001)
        assertEquals(OverUnderQuotes.QUOTA_MAX, OverUnderQuotes.quotaFor(0.001), 0.0001)
        // p >= 1 -> quota al minimo.
        assertEquals(OverUnderQuotes.QUOTA_MIN, OverUnderQuotes.quotaFor(1.0), 0.0001)
    }

    @Test
    fun `quota limbo con 0_5 e 3_0 nota`() {
        assertEquals(1.0, OverUnderQuotes.quotaFor(0.5), 0.0001) // p=0.5 -> x1.0
        assertEquals(2.0, OverUnderQuotes.quotaFor(0.25), 0.0001) // p=0.25 -> x2.0
        // p=0.1: 0.5/0.1 = 5 -> clamp al tetto.
        assertEquals(3.0, OverUnderQuotes.quotaFor(0.1), 0.0001)
    }

    @Test
    fun `dnf no non paga mai piu' di dnf si (clamp rate a 0_5)`() {
        val rates = listOf(0.03, 0.10, 0.15, 0.25, 0.40, 0.50) // dominio ammesso nel clamp
        rates.forEach { rate ->
            val (qSi, qNo) = OverUnderQuotes.dnfQuotas(rate)
            assertTrue("rate=$rate: qNo=$qNo > qSi=$qSi", qNo <= qSi)
        }
    }

    @Test
    fun `dnfQuotas monotone rate piu' alto paga meno il si e piu' il no`() {
        val rates = listOf(0.03, 0.10, 0.20, 0.35, 0.50)
        rates.zipWithNext().forEach { (a, b) ->
            val (siA, noA) = OverUnderQuotes.dnfQuotas(a)
            val (siB, noB) = OverUnderQuotes.dnfQuotas(b)
            assertTrue("qSi non decrescente a=$a b=$b", siB <= siA)
            assertTrue("qNo non crescente a=$a b=$b", noB >= noA)
        }
    }

    @Test
    fun `dnf rate non clampati rispettano il valore atteso`() {
        // rate 0.25 (p non clampata: >= 0.2): si paga x2.0 e EV = 0.5.
        val (qSi, qNo) = OverUnderQuotes.dnfQuotas(0.25)
        assertEquals(2.0, qSi, 0.0001)
        assertEquals(0.5, 0.25 * qSi, 0.0001)
        assertTrue(qSi > qNo)
    }

    @Test
    fun `dnf rate bassissima si clampa al tetto e paga comunque piu' del no`() {
        val (qSi, qNo) = OverUnderQuotes.dnfQuotas(0.05)
        assertEquals(3.0, qSi, 0.0001) // 0.5/0.05 = 10 -> tetto
        assertTrue(qSi > qNo)
    }

    @Test
    fun `pointsOverUnderQuotas monotone piu' gare sopra la soglia, quota over scende`() {
        var prevOver = Double.MAX_VALUE
        var prevUnder = 0.0
        for (overCount in 0..10) {
            val (qOver, qUnder) = OverUnderQuotes.pointsOverUnderQuotas(overCount, 10)
            assertTrue(qOver in OverUnderQuotes.QUOTA_MIN..OverUnderQuotes.QUOTA_MAX)
            assertTrue(qUnder in OverUnderQuotes.QUOTA_MIN..OverUnderQuotes.QUOTA_MAX)
            assertTrue("qOver deve scendere (overCount=$overCount)", qOver <= prevOver)
            assertTrue("qUnder deve salire (overCount=$overCount)", qUnder >= prevUnder)
            prevOver = qOver
            prevUnder = qUnder
        }
    }

    @Test
    fun `punti over under con pochi dati prior Beta 1_5`() {
        // n=0, overCount=0: p_over = 1.5/3 = 0.5 -> quote x1.0 e x1.0.
        val (qOver, qUnder) = OverUnderQuotes.pointsOverUnderQuotas(0, 0)
        assertEquals(1.0, qOver, 0.0001)
        assertEquals(1.0, qUnder, 0.0001)
    }
}
